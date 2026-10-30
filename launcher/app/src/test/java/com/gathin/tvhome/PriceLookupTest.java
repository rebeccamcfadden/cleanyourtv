package com.gathin.tvhome;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * One title's lookup with fake networks, and when it is due again: a price step that fails is retried after an hour
 * even when the ids came, and a list's spent budget doesn't keep the preview from asking.
 */
public class PriceLookupTest {

    private static final long H = PriceCachePolicy.HOUR;
    private static final long NOW = 1_800_000_000_000L;
    private static final PriceCachePolicy.Trigger LIST = PriceCachePolicy.Trigger.LIST;
    private static final PriceCachePolicy.Trigger PREVIEW = PriceCachePolicy.Trigger.PREVIEW;

    private final List<String> asked = new ArrayList<>();

    /** Wikidata answers from "its cache" (the recorded Arrival answer). */
    private final HttpGet wikidata = (url, headers) -> {
        asked.add("wikidata");
        return Fixtures.read("wikidata_movie_329865.json");
    };
    private final HttpGet itunesOk = (url, headers) -> {
        asked.add("itunes");
        return Fixtures.read("itunes_lookup_se.json");
    };
    private final HttpGet itunesDown = (url, headers) -> {
        asked.add("itunes");
        throw new IOException("offline");
    };
    private final HttpGet watchmodeSpent = (url, headers) -> {
        asked.add("watchmode");
        throw new CachedHttp.OverBudget(WatchmodePrices.ID);
    };
    private final HttpGet watchmodeOk = (url, headers) -> {
        asked.add("watchmode");
        return Fixtures.read("watchmode_movie_handmade.json");
    };
    private final HttpGet never = (url, headers) -> {
        throw new AssertionError("not asked: " + url);
    };

    private PriceLookup lookup(String watchmodeKey, HttpGet watchmodeHttp, HttpGet itunesHttp) {
        return new PriceLookup(wikidata, new WatchmodePrices(watchmodeKey), watchmodeHttp,
                new StreamingAvailabilityPrices(""), never, new ItunesPrices(), itunesHttp);
    }

    private PriceLookup.Result arrival(PriceLookup l) {
        return l.run(Kind.MOVIE, 329865, "Arrival", 2016, true);
    }

    @Test
    public void allWell() {
        PriceLookup.Result r = arrival(lookup("", never, itunesOk));
        assertTrue(r.idsOk && r.pricesOk && !r.budgetBlocked);
        assertEquals(4, r.prices.size());
        PriceLookup.Entry e = PriceLookup.Entry.after(null, r, true, LIST, NOW);
        assertEquals(NOW, e.idsAt);
        assertEquals(NOW, e.pricedAt);
        assertEquals(0, e.failedAt);
        assertFalse(PriceLookup.needsLookup(e, true, true, NOW + 23 * H));
        assertTrue(PriceLookup.needsLookup(e, true, false, NOW + 24 * H));
    }

    @Test
    public void idsFromTheCacheButItunesDownIsRetriedAfterAnHourNotADay() {
        PriceLookup.Result r = arrival(lookup("", never, itunesDown));
        assertTrue(r.idsOk);
        assertFalse(r.pricesOk);
        PriceLookup.Entry e = PriceLookup.Entry.after(null, r, true, LIST, NOW);
        // The ids count as fresh; the prices don't.
        assertEquals(NOW, e.idsAt);
        assertEquals(0, e.pricedAt);
        assertEquals(NOW, e.failedAt);
        assertFalse(PriceLookup.needsLookup(e, true, false, NOW + 59 * 60_000L));
        assertFalse(PriceLookup.needsLookup(e, true, true, NOW + 59 * 60_000L));
        assertTrue(PriceLookup.needsLookup(e, true, false, NOW + H));
        // A preview that only wants the links is satisfied by the ids.
        assertFalse(PriceLookup.needsLookup(e, false, true, NOW + 10 * 60_000L));

        // The retry an hour later succeeds: prices and both stamps are current, and the failure is gone.
        PriceLookup.Entry next = PriceLookup.Entry.after(e, arrival(lookup("", never, itunesOk)), true, LIST, NOW + H);
        assertEquals(NOW + H, next.pricedAt);
        assertEquals(0, next.failedAt);
        assertEquals(4, next.prices.size());
    }

    @Test
    public void aListsSpentBudgetLetsThePreviewAskAgain() {
        PriceLookup.Result r = arrival(lookup("key", watchmodeSpent, itunesOk));
        assertTrue(r.budgetBlocked);
        assertFalse(r.pricesOk);
        // iTunes still answered, so the Apple prices are kept meanwhile.
        assertEquals(4, r.prices.size());
        PriceLookup.Entry e = PriceLookup.Entry.after(null, r, true, LIST, NOW);
        assertTrue(e.listBlocked);
        assertEquals(0, e.pricedAt);
        assertEquals(4, e.prices.size());
        // Another list card waits for the retry; the preview asks now, under its own allowance.
        assertFalse(PriceLookup.needsLookup(e, true, false, NOW + 60_000L));
        assertTrue(PriceLookup.needsLookup(e, true, true, NOW + 60_000L));

        asked.clear();
        PriceLookup.Result again = arrival(lookup("key", watchmodeOk, never));
        assertEquals(java.util.Arrays.asList("wikidata", "watchmode"), asked);
        assertTrue(again.pricesOk);
        PriceLookup.Entry priced = PriceLookup.Entry.after(e, again, true, PREVIEW, NOW + 60_000L);
        assertFalse(priced.listBlocked);
        assertEquals(NOW + 60_000L, priced.pricedAt);
        assertFalse(PriceLookup.needsLookup(priced, true, true, NOW + 2 * H));
    }

    @Test
    public void aPreviewsOwnSpentBudgetWaitsAnHour() {
        PriceLookup.Entry e = PriceLookup.Entry.after(null, arrival(lookup("key", watchmodeSpent, itunesOk)), true,
                PREVIEW, NOW);
        assertFalse(e.listBlocked);
        assertFalse(PriceLookup.needsLookup(e, true, true, NOW + 60_000L));
        assertTrue(PriceLookup.needsLookup(e, true, true, NOW + H));
    }

    @Test
    public void wikidataDownKeepsTheIdsItHad() {
        PriceLookup.Entry old = PriceLookup.Entry.after(null, arrival(lookup("", never, itunesOk)), true, LIST, NOW);
        PriceLookup down = new PriceLookup((url, headers) -> {
            throw new IOException("offline");
        }, new WatchmodePrices(""), never, new StreamingAvailabilityPrices(""), never, new ItunesPrices(), itunesDown);
        PriceLookup.Result r = down.run(Kind.MOVIE, 329865, "Arrival", 2016, true);
        assertFalse(r.idsOk);
        PriceLookup.Entry e = PriceLookup.Entry.after(old, r, true, LIST, NOW + 25 * H);
        assertEquals("80117799", e.ids.first(WikidataIds.NETFLIX));
        assertEquals(NOW, e.idsAt);
        assertEquals(4, e.prices.size());
        assertEquals(NOW + 25 * H, e.failedAt);
        assertFalse(PriceLookup.needsLookup(e, true, true, NOW + 25 * H + 60_000L));
    }

    @Test
    public void noLookupYetMeansOneIsDue() {
        assertTrue(PriceLookup.needsLookup(null, false, false, NOW));
    }
}
