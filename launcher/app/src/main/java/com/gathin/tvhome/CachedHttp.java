package com.gathin.tvhome;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * The {@link HttpGet} the price sources and the Wikidata lookup use on the TV, one per source: answers are kept on
 * disk ({@link DiskCache}, keyed by URL only, so an API key in a header never reaches a file name) for as long as
 * {@link PriceCachePolicy} says, and a stale copy is used when the network fails, so what was seen once still shows
 * offline. Before going to the network it checks the source's monthly budget ({@link PriceCachePolicy#allowed}) and
 * keeps its minimum gap between requests. A 404 is kept too, as "not known". Blocking: the price thread only. Pure
 * Java (java.net), unit-tested with a fake network (CachedHttpTest).
 */
final class CachedHttp implements HttpGet {

    /** What the network answered. */
    static final class Response {
        final int code;
        final byte[] body;

        Response(int code, byte[] body) {
            this.code = code;
            this.body = body;
        }
    }

    interface Fetcher {
        Response fetch(String url, Map<String, String> headers) throws IOException;
    }

    /** Where the ledgers live between runs: the app's preferences on the TV. */
    interface LedgerStore {
        String load(String sourceId);

        void save(String sourceId, String encoded);
    }

    /** No room left in the source's budget today, and nothing kept: the caller tries another source or none. */
    static final class OverBudget extends IOException {
        OverBudget(String sourceId) {
            super(sourceId + ": over budget");
        }
    }

    private static final int CONNECT_MS = 8000, READ_MS = 12000;

    private final String sourceId;
    private final DiskCache cache;
    private final long ttl;
    private final long minInterval;
    private final Fetcher fetcher;
    private final LedgerStore ledgers;
    private final LongSupplier clock;
    private final ZoneId zone;
    private long lastRequest;
    /** Why the lookup is made; set by the price thread before each one. */
    volatile PriceCachePolicy.Trigger trigger = PriceCachePolicy.Trigger.PREVIEW;

    CachedHttp(String sourceId, DiskCache cache, long ttl, long minInterval, Fetcher fetcher, LedgerStore ledgers,
               LongSupplier clock, ZoneId zone) {
        this.sourceId = sourceId;
        this.cache = cache;
        this.ttl = ttl;
        this.minInterval = minInterval;
        this.fetcher = fetcher;
        this.ledgers = ledgers;
        this.clock = clock;
        this.zone = zone;
    }

    @Override
    public synchronized String get(String url, Map<String, String> headers) throws IOException {
        DiskCache.Entry kept = cache.read(url, ttl);
        if (kept != null && kept.fresh) return text(kept.bytes);
        int cost = PriceCachePolicy.requestCost(sourceId);
        int budget = PriceCachePolicy.monthlyBudget(sourceId);
        LocalDate today = Instant.ofEpochMilli(clock.getAsLong()).atZone(zone).toLocalDate();
        PriceCachePolicy.Ledger ledger = PriceCachePolicy.Ledger.decode(ledgers.load(sourceId), today);
        if (!PriceCachePolicy.allowed(ledger, cost, budget, trigger, today)) {
            if (kept != null) return text(kept.bytes);
            throw new OverBudget(sourceId);
        }
        long wait = lastRequest + minInterval - clock.getAsLong();
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            }
        }
        Response r;
        try {
            r = fetcher.fetch(url, headers);
        } catch (IOException e) {
            if (kept != null) return text(kept.bytes);
            throw e;
        } finally {
            // Counted whatever the answer: a failed request may still have used a credit.
            lastRequest = clock.getAsLong();
            ledger.record(cost, today);
            ledgers.save(sourceId, ledger.encode());
        }
        if (r.code == 200 && r.body != null && r.body.length > 0) {
            cache.write(url, r.body);
            return new String(r.body, StandardCharsets.UTF_8);
        }
        if (r.code == 404) {
            cache.write(url, new byte[0]);
            return null;
        }
        if (kept != null) return text(kept.bytes);
        throw new IOException(sourceId + " answered " + r.code);
    }

    /** A kept answer: an empty file is a kept 404. */
    private static String text(byte[] bytes) {
        return bytes.length == 0 ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    /** The real network: HttpURLConnection with short timeouts. */
    static final Fetcher NET = (url, headers) -> {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(CONNECT_MS);
        c.setReadTimeout(READ_MS);
        c.setUseCaches(false);
        c.setRequestProperty("Accept", "application/json");
        for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
        try {
            int code = c.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                InputStream err = c.getErrorStream();
                if (err != null) err.close();
                return new Response(code, null);
            }
            try (InputStream in = c.getInputStream()) {
                return new Response(code, DiskCache.readAll(in));
            }
        } catch (IOException e) {
            c.disconnect();
            throw e;
        }
    };
}
