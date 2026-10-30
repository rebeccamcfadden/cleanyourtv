package com.gathin.tvhome;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The price sources' HTTP on the TV: the disk cache, stale copies offline, kept 404s and the monthly budget. */
public class CachedHttpTest {

    private final File dir = new File("build/test-cached-http");
    /** 2026-10-01 12:00 UTC. */
    private long now = 1_790_856_000_000L;
    private final List<String> asked = new ArrayList<>();
    private final Map<String, String> ledgers = new HashMap<>();
    private CachedHttp.Response next;
    private boolean offline;

    private final CachedHttp.Fetcher fake = (url, headers) -> {
        asked.add(url);
        if (offline) throw new IOException("offline");
        return next;
    };

    private final CachedHttp.LedgerStore store = new CachedHttp.LedgerStore() {
        @Override
        public String load(String sourceId) {
            return ledgers.get(sourceId);
        }

        @Override
        public void save(String sourceId, String encoded) {
            ledgers.put(sourceId, encoded);
        }
    };

    private DiskCache cache;

    @Before
    public void setUp() {
        clean();
        cache = new DiskCache(dir, 1 << 20, () -> now);
    }

    @After
    public void clean() {
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) //noinspection ResultOfMethodCallIgnored
            f.delete();
        //noinspection ResultOfMethodCallIgnored
        dir.delete();
    }

    private CachedHttp http(String source) {
        return new CachedHttp(source, cache, PriceCachePolicy.PRICE_TTL, 0, fake, store, () -> now, ZoneOffset.UTC);
    }

    private static CachedHttp.Response ok(String body) {
        return new CachedHttp.Response(200, body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void aFreshAnswerIsNotAskedAgain() throws IOException {
        CachedHttp h = http(ItunesPrices.ID);
        next = ok("{\"a\":1}");
        assertEquals("{\"a\":1}", h.get("https://x/1", Collections.emptyMap()));
        now += 23 * PriceCachePolicy.HOUR;
        assertEquals("{\"a\":1}", h.get("https://x/1", Collections.emptyMap()));
        assertEquals(1, asked.size());
        now += 2 * PriceCachePolicy.HOUR;
        next = ok("{\"a\":2}");
        assertEquals("{\"a\":2}", h.get("https://x/1", Collections.emptyMap()));
        assertEquals(2, asked.size());
    }

    @Test
    public void offlineGivesTheStaleCopyOrFails() throws IOException {
        CachedHttp h = http(ItunesPrices.ID);
        next = ok("{\"a\":1}");
        h.get("https://x/1", Collections.emptyMap());
        now += 48 * PriceCachePolicy.HOUR;
        offline = true;
        assertEquals("{\"a\":1}", h.get("https://x/1", Collections.emptyMap()));
        try {
            h.get("https://x/never", Collections.emptyMap());
            fail();
        } catch (IOException expected) {
            // Nothing kept and no network.
        }
        // A server error is a failure too, with the stale copy when there is one.
        offline = false;
        next = new CachedHttp.Response(500, null);
        assertEquals("{\"a\":1}", h.get("https://x/1", Collections.emptyMap()));
    }

    @Test
    public void aNotFoundIsKept() throws IOException {
        CachedHttp h = http(StreamingAvailabilityPrices.ID);
        next = new CachedHttp.Response(404, null);
        assertNull(h.get("https://x/404", Collections.emptyMap()));
        assertNull(h.get("https://x/404", Collections.emptyMap()));
        assertEquals(1, asked.size());
    }

    @Test
    public void theBudgetStopsAKeyedSource() throws IOException {
        CachedHttp h = http(WatchmodePrices.ID);
        next = ok("[]");
        // 2,000 credits over October's 31 days: 64 today, 32 of them for lists, at 2 credits a lookup.
        h.trigger = PriceCachePolicy.Trigger.LIST;
        for (int i = 0; i < 16; i++) h.get("https://x/" + i, Collections.emptyMap());
        try {
            h.get("https://x/16", Collections.emptyMap());
            fail();
        } catch (CachedHttp.OverBudget expected) {
            // Lists have used their half.
        }
        h.trigger = PriceCachePolicy.Trigger.PREVIEW;
        for (int i = 16; i < 32; i++) h.get("https://x/" + i, Collections.emptyMap());
        try {
            h.get("https://x/32", Collections.emptyMap());
            fail();
        } catch (CachedHttp.OverBudget expected) {
            // The day's share is gone.
        }
        // What is kept still answers, and the ledger survives as text.
        assertEquals("[]", h.get("https://x/3", Collections.emptyMap()));
        assertEquals("2026-10-01|64|64", ledgers.get(WatchmodePrices.ID));
        // The next day has its own share.
        now += 24 * PriceCachePolicy.HOUR;
        h.get("https://x/33", Collections.emptyMap());
        assertEquals("2026-10-02|66|2", ledgers.get(WatchmodePrices.ID));
    }

    @Test
    public void keylessSourcesHaveNoBudget() throws IOException {
        CachedHttp h = http(ItunesPrices.ID);
        next = ok("{}");
        for (int i = 0; i < 300; i++) h.get("https://x/" + i, Collections.emptyMap());
        assertEquals(300, asked.size());
        assertTrue(ledgers.get(ItunesPrices.ID).endsWith("|300|300"));
    }
}
