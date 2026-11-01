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
    public void nothingSavedOrConfiguredRunsNothing() {
        // A new install starts with every feature off; First run's Features step turns them on (2026-11-01).
        Features f = Features.resolve(null, null);
        for (Features.Feature x : Features.Feature.values()) assertFalse(x.id, f.on(x));
        assertEquals(0, f.onCount());
        assertTrue(f.same(Features.ALL_OFF));
    }

    @Test
    public void theConfigSwitchesOnAndTheHouseholdsChoiceWins() {
        Features f = Features.resolve(saved("feature_pick", false, "feature_weather", true),
                config("tmdb", true, "pick", true, "prices", false));
        assertTrue(f.on(Features.Feature.TMDB));
        assertFalse(f.on(Features.Feature.PICK));
        assertTrue(f.on(Features.Feature.WEATHER));
        assertFalse(f.on(Features.Feature.PRICES));
        assertFalse(f.on(Features.Feature.THEMES));
        assertEquals(2, f.onCount());
    }

    @Test
    public void phoneNotesIsOffWhileTmdbAccountIs() {
        Features f = Features.resolve(saved("feature_tmdb", false, "feature_notes", true), null);
        assertTrue(f.switchedOn(Features.Feature.NOTES));
        assertFalse(f.on(Features.Feature.NOTES));
        assertSame(Features.Feature.TMDB, f.blockedBy(Features.Feature.NOTES));
        assertNull(f.blockedBy(Features.Feature.WEATHER));
        assertEquals(0, f.onCount());
        // Back on with TMDB account, Phone notes keeps its own switch.
        Features back = Features.resolve(saved("feature_tmdb", true, "feature_notes", true), null);
        assertTrue(back.on(Features.Feature.NOTES));
        assertNull(back.blockedBy(Features.Feature.NOTES));
        assertEquals(2, back.onCount());
    }

    @Test
    public void anythingButABooleanIsIgnored() {
        Features f = Features.resolve(saved("feature_prices", "on", "feature_themes", 1),
                config("prices", true));
        assertTrue(f.on(Features.Feature.PRICES));
        assertFalse(f.on(Features.Feature.THEMES));
    }

    @Test
    public void aTvFromBeforeFeaturesKeepsThemOnAndANewInstallStartsOff() {
        // Decided on the first start with F14: updated means it ran the launcher before, and finished First run.
        assertTrue(Features.keepAllOn(null, false));
        assertFalse(Features.keepAllOn(null, true));
        // Kept after that: a fresh install updated later stays off, a kept TV stays on.
        assertFalse(Features.keepAllOn(false, false));
        assertTrue(Features.keepAllOn(true, true));
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
        Features a = Features.resolve(saved("feature_tmdb", false), config("notes", true));
        // Notes switched off too: what runs is the same, since TMDB account being off already stops it.
        Features b = Features.resolve(saved("feature_tmdb", false, "feature_notes", false), null);
        assertTrue(a.same(b));
        assertTrue(a.same(Features.ALL_OFF));
        assertFalse(a.same(Features.ALL_ON));
        assertEquals(6, Features.ALL_ON.onCount());
    }

    @Test
    public void currentIsWhatWasApplied() {
        assertFalse(Features.isOn(Features.Feature.PICK));
        Features.apply(Features.resolve(saved("feature_pick", true), null));
        assertTrue(Features.isOn(Features.Feature.PICK));
        Features.apply(null);
        assertSame(Features.ALL_OFF, Features.current());
    }
}