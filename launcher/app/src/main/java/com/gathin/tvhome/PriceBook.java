package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import java.io.File;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Each TMDB title's prices and the services' own ids for it (BACKLOG.md, Streaming availability and prices; Title
 * deep links), looked up on demand: when a title preview opens, or a list shows a title that can be rented or bought,
 * and only while Home is showing ({@link #setActive}). One low-priority thread runs a {@link PriceLookup} per title,
 * previews first ({@link PriceQueue}: a preview moves its title's waiting list lookup ahead). Answers are kept on
 * disk ({@link CachedHttp}): prices a day, the ids a month, and stale ones offline. When a title is due again is
 * {@link PriceLookup#needsLookup}. What it found lives here, on the main thread; {@link TmdbCatalog} merges it into
 * the title's offers.
 */
final class PriceBook {
    /** Hears, on the main thread, that a title's prices or ids came in. */
    interface Listener {
        void priced(String id);
    }

    /** Answers on disk: a few thousand small JSON files at most. */
    private static final long DISK_MAX = 4L << 20;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final PriceQueue queue = new PriceQueue();
    private final Listener listener;
    private final PriceLookup lookup;
    private final CachedHttp[] https;
    private final Map<String, PriceLookup.Entry> entries = new HashMap<>();
    /** Titles queued or being looked up (main thread). */
    private final Set<String> inFlight = new HashSet<>();
    private final Map<String, List<Runnable>> waiting = new HashMap<>();
    private volatile boolean active = true;
    private Thread worker;

    PriceBook(Context ctx, String watchmodeKey, String streamingKey, Listener listener) {
        this.listener = listener;
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
        CachedHttp wikidataHttp = http(WikidataIds.ID, disk, PriceCachePolicy.DEEP_LINK_TTL, ledgers, zone);
        CachedHttp itunesHttp = http(ItunesPrices.ID, disk, PriceCachePolicy.PRICE_TTL, ledgers, zone);
        CachedHttp watchmodeHttp = http(WatchmodePrices.ID, disk, PriceCachePolicy.PRICE_TTL, ledgers, zone);
        CachedHttp streamingHttp = http(StreamingAvailabilityPrices.ID, disk, PriceCachePolicy.PRICE_TTL, ledgers, zone);
        https = new CachedHttp[]{wikidataHttp, itunesHttp, watchmodeHttp, streamingHttp};
        lookup = new PriceLookup(wikidataHttp, new WatchmodePrices(watchmodeKey), watchmodeHttp,
                new StreamingAvailabilityPrices(streamingKey), streamingHttp, new ItunesPrices(), itunesHttp);
    }

    private static CachedHttp http(String id, DiskCache disk, long ttl, CachedHttp.LedgerStore ledgers, ZoneId zone) {
        return new CachedHttp(id, disk, ttl, PriceCachePolicy.minIntervalMs(id), CachedHttp.NET, ledgers,
                System::currentTimeMillis, zone);
    }

    /** What is known for a title, or null. */
    PriceLookup.Entry get(String id) {
        return entries.get(id);
    }

    /** Home is showing or not: nothing new starts while it is hidden. */
    void setActive(boolean active) {
        this.active = active;
    }

    /**
     * Asks for t's ids and, with {@code needPrices}, its prices, unless {@link PriceLookup#needsLookup} says what is
     * known will do. {@code done} runs on the main thread once they are in (at once when no lookup is due); never
     * when the lookup doesn't start. A preview asking for a title whose list lookup is still waiting moves it ahead.
     */
    void want(Title t, boolean preview, boolean needPrices, Runnable done) {
        Kind kind = TmdbApi.kindOf(t.id);
        if (kind == null) return;
        if (!PriceLookup.needsLookup(entries.get(t.id), needPrices, preview, System.currentTimeMillis())) {
            if (done != null) done.run();
            return;
        }
        if (!active) return;
        if (done != null) {
            List<Runnable> list = waiting.get(t.id);
            if (list == null) waiting.put(t.id, list = new ArrayList<>());
            list.add(done);
        }
        if (inFlight.contains(t.id)) {
            if (preview) queue.boost(t.id, needPrices);
            return;
        }
        inFlight.add(t.id);
        queue.add(t.id, kind, t.name, t.year, needPrices,
                preview ? PriceCachePolicy.Trigger.PREVIEW : PriceCachePolicy.Trigger.LIST);
        startWorker();
    }

    private void startWorker() {
        if (worker != null) return;
        worker = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            while (true) {
                PriceQueue.Job job;
                try {
                    job = queue.take();
                } catch (InterruptedException e) {
                    return;
                }
                run(job);
            }
        }, "prices");
        worker.setDaemon(true);
        worker.start();
    }

    /** On the price thread. */
    private void run(PriceQueue.Job job) {
        String id = job.id;
        if (!active) {
            // Home went away while it waited: drop it; the screen asks again when it shows.
            main.post(() -> {
                inFlight.remove(id);
                waiting.remove(id);
            });
            return;
        }
        for (CachedHttp h : https) h.trigger = job.trigger;
        PriceLookup.Result r = lookup.run(job.kind, TmdbApi.number(id), job.name, job.year, job.needPrices);
        long now = System.currentTimeMillis();
        main.post(() -> {
            inFlight.remove(id);
            entries.put(id, PriceLookup.Entry.after(entries.get(id), r, job.needPrices, job.trigger, now));
            listener.priced(id);
            List<Runnable> list = waiting.remove(id);
            if (list != null) for (Runnable done : list) done.run();
        });
    }
}