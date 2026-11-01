package com.rmcfad.tv;

import org.junit.After;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** F14: which features run, from the household's switches over this TV's config. */
public class FeaturesTest {

    @After
    public void reset() {
        Features.apply(null);
    }

    private static Map<String, Object> saved(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static Map<String, Boolean> config(Object... kv) {
        Map<String, Boolean> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], (Boolean) kv[i + 1]);
        return m;
    }

    @Test
    public void nothingSavedOrConfiguredRunsEverything() {
        Features f = Features.resolve(null, null);
        for (Features.Feature x : Features.Feature.values()) assertTrue(x.id, f.on(x));
        assertEquals(6, f.onCount());
    }

    @Test
    public void theConfigSwitchesOffAndTheHouseholdsChoiceWins() {
        Features f = Features.resolve(saved("feature_weather", true), config("weather", false, "pick", false));
        assertTrue(f.on(Features.Feature.WEATHER));
        assertFalse(f.on(Features.Feature.PICK));
        assertEquals(5, f.onCount());
    }

    @Test
    public void phoneNotesIsOffWhileTmdbAccountIs() {
        Features f = Features.resolve(saved("feature_tmdb", false), null);
        assertTrue(f.switchedOn(Features.Feature.NOTES));
        assertFalse(f.on(Features.Feature.NOTES));
        assertSame(Features.Feature.TMDB, f.blockedBy(Features.Feature.NOTES));
        assertNull(f.blockedBy(Features.Feature.WEATHER));
        assertEquals(4, f.onCount());
        // Back on with TMDB account, Phone notes keeps its own switch.
        Features back = Features.resolve(saved("feature_tmdb", true), null);
        assertTrue(back.on(Features.Feature.NOTES));
        assertNull(back.blockedBy(Features.Feature.NOTES));
    }

    @Test
    public void anythingButABooleanIsIgnored() {
        Features f = Features.resolve(saved("feature_prices", "off", "feature_themes", 0), null);
        assertTrue(f.on(Features.Feature.PRICES));
        assertTrue(f.on(Features.Feature.THEMES));
    }

    @Test
    public void parseReadsIdsAndKeysAreStable() {
        assertSame(Features.Feature.NOTES, Features.Feature.parse(" Notes "));
        assertNull(Features.Feature.parse("music"));
        assertNull(Features.Feature.parse(null));
        assertEquals("feature_tmdb", Features.Feature.TMDB.key());
    }

    @Test
    public void sameComparesWhatRuns() {
        Features a = Features.resolve(saved("feature_tmdb", false), null);
        // Notes switched off too: what runs is the same, since TMDB account being off already stops it.
        Features b = Features.resolve(saved("feature_tmdb", false, "feature_notes", false), null);
        assertTrue(a.same(b));
        assertFalse(a.same(Features.ALL_ON));
    }

    @Test
    public void currentIsWhatWasApplied() {
        assertTrue(Features.isOn(Features.Feature.PICK));
        Features.apply(Features.resolve(saved("feature_pick", false), null));
        assertFalse(Features.isOn(Features.Feature.PICK));
        Features.apply(null);
        assertSame(Features.ALL_ON, Features.current());
    }
}
