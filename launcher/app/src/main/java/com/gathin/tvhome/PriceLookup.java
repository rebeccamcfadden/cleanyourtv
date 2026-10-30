package com.gathin.tvhome;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One title's price and link lookup, and when it is due again, as pure Java (PriceLookupTest), for {@link PriceBook}
 * to run on its thread. {@link #run} asks Wikidata for the services' ids and the iTunes ids, then the price sources in
 * {@link PriceCachePolicy}'s order: Watchmode, Streaming Availability when Watchmode can't be asked, iTunes when
 * neither gave an Apple TV Store price. The ids and the prices are stamped apart ({@link Entry}), so a lookup whose
 * ids came (often from their month-long disk cache) but whose prices failed is retried after
 * {@link PriceCachePolicy#FAILURE_RETRY}, not a day later; and prices a list could not ask for because its half of a
 * keyed source's budget was used up are asked again when the title's preview opens.
 */
final class PriceLookup {

    /** What one run found, and how each step went. */
    static final class Result {
        final WikidataIds ids;
        final boolean idsOk;
        final List<StorePrice> prices;
        /** Every price step that ran answered (a source may still have had nothing). */
        final boolean pricesOk;
        /** A keyed source was out of budget, and no other keyed source answered instead. */
        final boolean budgetBlocked;

        Result(WikidataIds ids, boolean idsOk, List<StorePrice> prices, boolean pricesOk, boolean budgetBlocked) {
            this.ids = ids;
            this.idsOk = idsOk;
            this.prices = Collections.unmodifiableList(new ArrayList<>(prices));
            this.pricesOk = pricesOk;
            this.budgetBlocked = budgetBlocked;
        }
    }

    /** What is known about one title: its ids and prices, when each last came in, and the last failure. */
    static final class Entry {
        final WikidataIds ids;
        final List<StorePrice> prices;
        /** When the ids came in, 0 for never. */
        final long idsAt;
        /** When the prices came in complete, 0 for never. */
        final long pricedAt;
        /** When the last lookup left something missing, 0 when it didn't. */
        final long failedAt;
        /** What was missing was only a list's share of a keyed source's budget: a preview may ask again at once. */
        final boolean listBlocked;

        Entry(WikidataIds ids, List<StorePrice> prices, long idsAt, long pricedAt, long failedAt, boolean listBlocked) {
            this.ids = ids;
            this.prices = Collections.unmodifiableList(new ArrayList<>(prices));
            this.idsAt = idsAt;
            this.pricedAt = pricedAt;
            this.failedAt = failedAt;
            this.listBlocked = listBlocked;
        }

        /**
         * The entry after a run at {@code now}: each part from the run when its step answered, else from {@code old}
         * (which may be null). Prices a failed run still found (Apple's, with Watchmode out of budget) are kept.
         */
        static Entry after(Entry old, Result r, boolean needPrices, PriceCachePolicy.Trigger trigger, long now) {
            WikidataIds ids = r.idsOk ? r.ids : old != null ? old.ids : WikidataIds.none();
            long idsAt = r.idsOk ? now : old != null ? old.idsAt : 0;
            boolean pricesOk = needPrices && r.pricesOk;
            long pricedAt = pricesOk ? now : old != null ? old.pricedAt : 0;
            List<StorePrice> prices = needPrices && (pricesOk || !r.prices.isEmpty()) ? r.prices
                    : old != null ? old.prices : Collections.emptyList();
            boolean failed = !r.idsOk || (needPrices && !r.pricesOk);
            boolean listBlocked = needPrices && r.budgetBlocked && trigger == PriceCachePolicy.Trigger.LIST;
            return new Entry(ids, prices, idsAt, pricedAt, failed ? now : 0, listBlocked);
        }
    }

    /**
     * Whether a title needs a lookup at {@code now}: when its ids, or its prices if they are wanted, are missing or
     * older than {@link PriceCachePolicy#PRICE_TTL}, and no failure in the last {@link PriceCachePolicy#FAILURE_RETRY}
     * holds it back. A preview is held back only by a failure that wasn't a list's spent budget.
     */
    static boolean needsLookup(Entry e, boolean needPrices, boolean preview, long now) {
        if (e == null) return true;
        boolean idsFresh = PriceCachePolicy.fresh(e.idsAt, now, PriceCachePolicy.PRICE_TTL);
        boolean pricesFresh = !needPrices || PriceCachePolicy.fresh(e.pricedAt, now, PriceCachePolicy.PRICE_TTL);
        if (idsFresh && pricesFresh) return false;
        if (PriceCachePolicy.fresh(e.failedAt, now, PriceCachePolicy.FAILURE_RETRY)) return preview && e.listBlocked;
        return true;
    }

    private final PriceSource watchmode, streaming, itunes;
    private final HttpGet wikidataHttp, watchmodeHttp, streamingHttp, itunesHttp;

    PriceLookup(HttpGet wikidataHttp, PriceSource watchmode, HttpGet watchmodeHttp, PriceSource streaming,
                HttpGet streamingHttp, PriceSource itunes, HttpGet itunesHttp) {
        this.wikidataHttp = wikidataHttp;
        this.watchmode = watchmode;
        this.watchmodeHttp = watchmodeHttp;
        this.streaming = streaming;
        this.streamingHttp = streamingHttp;
        this.itunes = itunes;
        this.itunesHttp = itunesHttp;
    }

    /** Blocking: the ids, then (with {@code needPrices}) the prices. Never throws. */
    Result run(Kind kind, int number, String name, int year, boolean needPrices) {
        WikidataIds ids = WikidataIds.none();
        boolean idsOk = false;
        try {
            ids = WikidataIds.fetch(kind, number, wikidataHttp);
            idsOk = true;
        } catch (IOException | RuntimeException e) {
            // No ids: no links, and iTunes falls back to its search.
        }
        List<StorePrice> prices = new ArrayList<>();
        boolean pricesOk = true, blocked = false;
        if (needPrices) {
            PriceTitle pt = new PriceTitle(number, kind, name, null, year, ids.itunesIds(kind));
            boolean keyedOk = false, keyedFailed = false;
            for (PriceSource s : new PriceSource[]{watchmode, streaming}) {
                if (keyedOk || !s.configured()) continue;
                try {
                    prices.addAll(s.prices(pt, s == watchmode ? watchmodeHttp : streamingHttp));
                    keyedOk = true;
                } catch (CachedHttp.OverBudget e) {
                    blocked = true;
                } catch (IOException | RuntimeException e) {
                    keyedFailed = true;
                }
            }
            if (keyedOk) blocked = false;
            pricesOk = keyedOk || (!blocked && !keyedFailed);
            if (PriceCachePolicy.needsItunes(prices)) {
                try {
                    prices.addAll(itunes.prices(pt, itunesHttp));
                } catch (IOException | RuntimeException e) {
                    pricesOk = false;
                }
            }
        }
        return new Result(ids, idsOk, prices, pricesOk, blocked);
    }
}
