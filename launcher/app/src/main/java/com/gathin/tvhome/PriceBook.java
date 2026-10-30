package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import java.io.File;
import java.io.IOException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Each TMDB title's prices and the services' own ids for it (BACKLOG.md, Streaming availability and prices; Title
 * deep links), looked up on demand: when a title preview opens, or a list shows a title that can be rented or bought,
 * and only while Home is showing ({@link #setActive}). One low-priority thread asks Wikidata for the ids (and the
 * iTunes ids), then Watchmode when its key is in the build, Streaming Availability when Watchmode can't be asked, and
 * Apple's iTunes when neither gave an Apple TV Store price ({@link PriceCachePolicy}). Answers are kept on disk
 * ({@link CachedHttp}): prices a day, the ids a month, and stale ones offline. What it found lives here, on the main
 * thread; {@link TmdbCatalog} merges it into the title's offers.
 */
final class PriceBook {
    /** Hears, on the main thread, that a title's prices or ids came in. */
    interface Listener {
        void priced(String id);
    }

    /** What was found for one title. */
    static final class Entry {
        final List<StorePrice> prices;
        final WikidataIds ids;
        final long fetchedAt;
        final long failedAt;
        /** Whether prices were asked for, not only the ids (a preview of a title with nothing to rent or buy). */
        final boolean priced;

        Entry(List<StorePrice> prices, WikidataIds ids, long fetchedAt, long failedAt, boolean priced) {
            this.prices = Collections.unmodifiableList(new ArrayList<>(prices));
            this.ids = ids;
            this.fetchedAt = fetchedAt;
            this.failedAt = failedAt;
            this.priced = priced;
        }
    }

    /** Answers on disk: a few thousand small JSON files at most. */
    private static final long DISK_MAX = 4L << 20;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor pool;
    private final AtomicLong order = new AtomicLong();
    private final Listener listener;
    private final Map<String, Entry> entries = new HashMap<>();
    private final Set<String> inFlight = new HashSet<>();
    private final Map<String, List<Runnable>> waiting = new HashMap<>();
    private volatile boolean active = true;

    private final ItunesPrices itunes = new ItunesPrices();
    private final WatchmodePrices watchmode;
    private final StreamingAvailabilityPrices streaming;
    private final CachedHttp wikidataHttp, itunesHttp, watchmodeHttp, streamingHttp;

    PriceBook(Context ctx, String watchmodeKey, String streamingKey, Listener listener) {
        this.listener = listener;
        watchmode = new WatchmodePrices(watchmodeKey);
        streaming = new StreamingAvailabilityPrices(streamingKey);
        Context app = ctx.getApplicationContext();
        DiskCache disk = new DiskCache(new File(app.getCacheDir(), "prices"), DISK_MAX, System::currentTimeMillis);
        SharedPreferences prefs = app.getSharedPreferences("prices", Context.MODE_PRIVATE);
        CachedHttp.LedgerStore ledgers = new CachedHttp.LedgerStore() {
            @Override
            public String load(String sourceId) {
                return prefs.getString("ledger_" + sourceId, null);
            }

            @Override
            public void save(String sourceId, String encoded) {
                prefs.edit().putString("ledger_" + sourceId, encoded).apply();
            }
        };
        ZoneId zone = ZoneId.systemDefault();
        wikidataHttp = http(WikidataIds.ID, disk, PriceCachePolicy.DEEP_LINK_TTL, ledgers, zone);
        itunesHttp = http(ItunesPrices.ID, disk, PriceCachePolicy.PRICE_TTL, ledgers, zone);
        watchmodeHttp = http(WatchmodePrices.ID, disk, PriceCachePolicy.PRICE_TTL, ledgers, zone);
        streamingHttp = http(StreamingAvailabilityPrices.ID, disk, PriceCachePolicy.PRICE_TTL, ledgers, zone);
        pool = new ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS, new PriorityBlockingQueue<>(), r -> {
            Thread t = new Thread(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                r.run();
            }, "prices");
            t.setDaemon(true);
            return t;
        });
        pool.allowCoreThreadTimeOut(true);
    }

    private static CachedHttp http(String id, DiskCache disk, long ttl, CachedHttp.LedgerStore ledgers, ZoneId zone) {
        return new CachedHttp(id, disk, ttl, PriceCachePolicy.minIntervalMs(id), CachedHttp.NET, ledgers,
                System::currentTimeMillis, zone);
    }

    /** What was found for a title, or null. */
    Entry get(String id) {
        return entries.get(id);
    }

    /** Home is showing or not: nothing new starts while it is hidden. */
    void setActive(boolean active) {
        this.active = active;
    }

    /**
     * Asks for t's ids and, with {@code needPrices}, its prices, unless what is known is fresh. {@code done} runs on
     * the main thread once they are in (at once when fresh); never when the lookup doesn't start.
     */
    void want(Title t, boolean preview, boolean needPrices, Runnable done) {
        Kind kind = TmdbApi.kindOf(t.id);
        if (kind == null) return;
        Entry e = entries.get(t.id);
        long now = System.currentTimeMillis();
        boolean fresh = e != null && !PriceCachePolicy.shouldFetch(e.fetchedAt, e.failedAt, now);
        if (fresh && (e.priced || !needPrices)) {
            if (done != null) done.run();
            return;
        }
        if (!active) return;
        if (done != null) {
            List<Runnable> list = waiting.get(t.id);
            if (list == null) waiting.put(t.id, list = new ArrayList<>());
            list.add(done);
        }
        if (!inFlight.add(t.id)) return;
        String id = t.id;
        int number = TmdbApi.number(id);
        String name = t.name;
        int year = t.year;
        PriceCachePolicy.Trigger trigger = preview ? PriceCachePolicy.Trigger.PREVIEW : PriceCachePolicy.Trigger.LIST;
        pool.execute(new Job(preview ? 0 : 1, order.incrementAndGet(), () -> {
            if (!active) {
                main.post(() -> {
                    inFlight.remove(id);
                    waiting.remove(id);
                });
                return;
            }
            Entry found = lookup(kind, number, name, year, needPrices, trigger);
            main.post(() -> {
                inFlight.remove(id);
                entries.put(id, found);
                listener.priced(id);
                List<Runnable> list = waiting.remove(id);
                if (list != null) for (Runnable r : list) r.run();
            });
        }));
    }

    /** On the price thread: the ids, then the prices in the order {@link PriceCachePolicy} gives. */
    private Entry lookup(Kind kind, int number, String name, int year, boolean needPrices,
                         PriceCachePolicy.Trigger trigger) {
        for (CachedHttp h : new CachedHttp[]{wikidataHttp, itunesHttp, watchmodeHttp, streamingHttp}) h.trigger = trigger;
        boolean failed = false, answered = false;
        WikidataIds ids = WikidataIds.none();
        try {
            ids = WikidataIds.fetch(kind, number, wikidataHttp);
            answered = true;
        } catch (IOException | RuntimeException e) {
            failed = true;
        }
        List<StorePrice> prices = new ArrayList<>();
        if (needPrices) {
            PriceTitle pt = new PriceTitle(number, kind, name, null, year, ids.itunesIds(kind));
            boolean keyed = false;
            if (watchmode.configured()) {
                try {
                    prices.addAll(watchmode.prices(pt, watchmodeHttp));
                    keyed = true;
                } catch (IOException | RuntimeException e) {
                    failed |= !(e instanceof CachedHttp.OverBudget);
                }
            }
            if (!keyed && streaming.configured()) {
                try {
                    prices.addAll(streaming.prices(pt, streamingHttp));
                } catch (IOException | RuntimeException e) {
                    failed |= !(e instanceof CachedHttp.OverBudget);
                }
            }
            if (PriceCachePolicy.needsItunes(prices)) {
                try {
                    prices.addAll(itunes.prices(pt, itunesHttp));
                    answered = true;
                } catch (IOException | RuntimeException e) {
                    failed = true;
                }
            }
        }
        long now = System.currentTimeMillis();
        // Nothing answered at all (offline with nothing kept): try again after PriceCachePolicy.FAILURE_RETRY.
        return new Entry(prices, ids, answered ? now : 0, failed ? now : 0, needPrices);
    }

    /** A lookup, run in priority order (a preview before a list), then in the order asked. */
    private static final class Job implements Runnable, Comparable<Job> {
        final int priority;
        final long seq;
        final Runnable r;

        Job(int priority, long seq, Runnable r) {
            this.priority = priority;
            this.seq = seq;
            this.r = r;
        }

        @Override
        public void run() {
            r.run();
        }

        @Override
        public int compareTo(Job o) {
            return priority != o.priority ? Integer.compare(priority, o.priority) : Long.compare(seq, o.seq);
        }
    }
}
