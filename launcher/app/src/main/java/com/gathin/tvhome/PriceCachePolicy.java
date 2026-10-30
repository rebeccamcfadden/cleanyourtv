package com.gathin.tvhome;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * When to ask the price sources and Wikidata again, as pure logic (PriceCachePolicyTest). Prices are fetched only on
 * demand, when a title preview opens or a list shows the title, never in the background; an answer is kept 24 hours
 * (Watchmode's free plan allows at most 30 days), a deep link a month, and after a failure (offline, a 5xx) the title
 * waits an hour. Each keyed source has a monthly budget, under its free plan's quota, spread over the days left in the
 * month: a list may use half of a day's share, an opened preview all of it, and whatever a day leaves over carries to
 * the next. The order: Watchmode, then Streaming Availability when Watchmode can't be asked, then iTunes only when
 * neither found an Apple TV price.
 */
final class PriceCachePolicy {
    private PriceCachePolicy() {}

    static final long HOUR = 3_600_000L;
    static final long PRICE_TTL = 24 * HOUR;
    static final long DEEP_LINK_TTL = 30 * 24 * HOUR;
    static final long FAILURE_RETRY = HOUR;

    /** Why a lookup is wanted. There is no background reason, on purpose. */
    enum Trigger { PREVIEW, LIST }

    /**
     * The monthly budget for a source, in its own units (Watchmode counts credits, 2 per TMDB-id lookup, of 2,500 on
     * the free plan; Streaming Availability requests, of 1,000), or 0 for a keyless source that has none.
     */
    static int monthlyBudget(String sourceId) {
        switch (sourceId) {
            case WatchmodePrices.ID:
                return 2000;
            case StreamingAvailabilityPrices.ID:
                return 900;
            default:
                return 0;
        }
    }

    /** What one request costs against a source's budget: Watchmode charges 2 credits for a TMDB-id lookup. */
    static int requestCost(String sourceId) {
        return WatchmodePrices.ID.equals(sourceId) ? 2 : 1;
    }

    /** The least time between two requests to a keyless source: Apple asks for at most about 20 a minute. */
    static long minIntervalMs(String sourceId) {
        return ItunesPrices.ID.equals(sourceId) ? 3_000L : WikidataIds.ID.equals(sourceId) ? 1_000L : 0L;
    }

    /** Whether an answer fetched at {@code fetchedAt} (0 for never) is still good at {@code now}. */
    static boolean fresh(long fetchedAt, long now, long ttl) {
        return fetchedAt > 0 && now >= fetchedAt && now - fetchedAt < ttl;
    }

    /** Whether iTunes is still worth asking after the keyed sources answered. */
    static boolean needsItunes(List<StorePrice> found) {
        for (StorePrice p : found) if (ItunesPrices.STORE.equals(p.store)) return false;
        return true;
    }

    /**
     * One source's use this month and today. Kept as a short string ({@link #encode}) in the app's preferences by
     * Stage 2. Mutable; not thread-safe, so its owner serialises access.
     */
    static final class Ledger {
        LocalDate day;
        int monthUsed;
        int dayUsed;

        Ledger(LocalDate day, int monthUsed, int dayUsed) {
            this.day = day;
            this.monthUsed = monthUsed;
            this.dayUsed = dayUsed;
        }

        /** "2026-10-30|123|7"; the month is the day's. */
        String encode() {
            return day + "|" + monthUsed + "|" + dayUsed;
        }

        /** A ledger from {@link #encode}, or an empty one for today when the text is missing or broken. */
        static Ledger decode(String s, LocalDate today) {
            try {
                String[] p = s.split("\\|");
                return new Ledger(LocalDate.parse(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
            } catch (RuntimeException e) {
                return new Ledger(today, 0, 0);
            }
        }

        /** Starts a new day, and a new month, when {@code today} has moved on. */
        void roll(LocalDate today) {
            if (today.equals(day)) return;
            if (today.getYear() != day.getYear() || today.getMonthValue() != day.getMonthValue()) monthUsed = 0;
            dayUsed = 0;
            day = today;
        }

        void record(int cost, LocalDate today) {
            roll(today);
            monthUsed += cost;
            dayUsed += cost;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "%s month %d day %d", day, monthUsed, dayUsed);
        }
    }

    /** Today's share of what is left of the month's budget, the days left including today. */
    static int dailyAllowance(Ledger l, int budget, LocalDate today) {
        l.roll(today);
        int daysLeft = today.lengthOfMonth() - today.getDayOfMonth() + 1;
        int leftBeforeToday = budget - (l.monthUsed - l.dayUsed);
        return Math.max(0, leftBeforeToday / daysLeft);
    }

    /**
     * Whether a lookup costing {@code cost} may go out now. Unmetered ({@code budget} 0) always may; otherwise it must
     * fit the month, and today's use must stay within the day's allowance (half of it for a list).
     */
    static boolean allowed(Ledger l, int cost, int budget, Trigger trigger, LocalDate today) {
        if (budget <= 0) return true;
        int allowance = dailyAllowance(l, budget, today);
        if (l.monthUsed + cost > budget) return false;
        int cap = trigger == Trigger.LIST ? allowance / 2 : allowance;
        return l.dayUsed + cost <= cap;
    }
}
