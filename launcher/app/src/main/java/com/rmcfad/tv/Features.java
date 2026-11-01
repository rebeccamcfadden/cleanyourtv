package com.rmcfad.tv;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Launcher settings › Features (F14, the project lead 2026-11-01; design_preview's Features pane): one build, with six
 * features switched on or off at run time. A feature that is off never starts: no page, no network call, no server.
 * Each switch is the household's choice saved on the TV, else this TV's config (config.xml {@code feature_<id>}, or a
 * pushed config.json's {@code "features": {"<id>": true}}, as a family install sets it), else off: a new install starts
 * with every feature off, and First run's Features step turns them on (the project lead, 2026-11-01). A TV that ran the
 * launcher before F14 keeps them all on ({@link #keepAllOn}). A feature that needs another (Phone notes needs TMDB
 * account) is off while that one is. Pure Java, unit-tested (FeaturesTest); FeaturePrefs reads and saves the switches,
 * and {@link #current} is what the launcher was built with.
 */
final class Features {

    enum Feature {
        /** TMDB sign-in and the TMDB catalogue: Movies, TV Shows and Search need it (and a sign-in). */
        TMDB("tmdb", null),
        /** Phone notes: the inbox on port 8765 and the import pill. Puts a phone's list on TMDB. */
        NOTES("notes", TMDB),
        /** Pick for me's button and pages. */
        PICK("pick", null),
        /** Rent and buy prices (Watchmode, Movie of the Night, Apple); without them offers say where, not how much. */
        PRICES("prices", null),
        /** Open-Meteo's weather on Home, its Launcher settings and quick settings rows. */
        WEATHER("weather", null),
        /** Downloaded backdrops and theme fonts, and the themes that need them (Landscapes, Seasonal). */
        THEMES("themes", null);

        final String id;
        final Feature needs;

        Feature(String id, Feature needs) {
            this.id = id;
            this.needs = needs;
        }

        /** The preference and config key, "feature_tmdb". */
        String key() {
            return KEY_PREFIX + id;
        }

        static Feature parse(String id) {
            if (id == null) return null;
            String s = id.trim().toLowerCase(Locale.ROOT);
            for (Feature f : values()) if (f.id.equals(s)) return f;
            return null;
        }
    }

    static final String KEY_PREFIX = "feature_";

    /** Every feature on. */
    static final Features ALL_ON = every(true);

    /** Every feature off: a new install with no config and no choices, and the launcher before it is built. */
    static final Features ALL_OFF = every(false);

    private static Features every(boolean on) {
        Map<Feature, Boolean> m = new EnumMap<>(Feature.class);
        for (Feature f : Feature.values()) m.put(f, on);
        return new Features(m);
    }

    /** Each feature's own switch; a feature not in the map is off. */
    private final Map<Feature, Boolean> switches;

    private Features(Map<Feature, Boolean> switches) {
        this.switches = switches;
    }

    /**
     * The switches in effect: each saved choice ({@code feature_<id>} booleans), else the config's ({@code config}, by
     * feature id), else off.
     */
    static Features resolve(Map<String, ?> saved, Map<String, Boolean> config) {
        Map<Feature, Boolean> out = new EnumMap<>(Feature.class);
        for (Feature f : Feature.values()) {
            Object s = saved == null ? null : saved.get(f.key());
            Boolean c = config == null ? null : config.get(f.id);
            if (s instanceof Boolean) out.put(f, (Boolean) s);
            else if (c != null) out.put(f, c);
        }
        return new Features(out);
    }

    /** The feature's own switch, whatever it needs. */
    boolean switchedOn(Feature f) {
        return Boolean.TRUE.equals(switches.get(f));
    }

    /** Whether the feature runs: switched on, and so is what it needs. */
    boolean on(Feature f) {
        return switchedOn(f) && (f.needs == null || on(f.needs));
    }

    /** The feature it needs, when that one is off; else null. */
    Feature blockedBy(Feature f) {
        return f.needs != null && !on(f.needs) ? f.needs : null;
    }

    /** How many run, for the section's value "5 of 6 on". */
    int onCount() {
        int n = 0;
        for (Feature f : Feature.values()) if (on(f)) n++;
        return n;
    }

    boolean same(Features o) {
        for (Feature f : Feature.values()) if (on(f) != o.on(f)) return false;
        return true;
    }

    /**
     * Whether this TV keeps every feature on where the household saved no switch (the project lead, 2026-11-01): a TV
     * that ran the launcher before F14 had them all, and finished First run without a Features step. Decided once, on
     * the first start with F14 ({@code decided} null): a launcher updated rather than freshly installed is such a TV;
     * a fresh install starts with them off, and stays that way after later updates.
     */
    static boolean keepAllOn(Boolean decided, boolean freshInstall) {
        return decided != null ? decided : !freshInstall;
    }

    private static Features current = ALL_OFF;

    /** The switches the launcher was built with; set when MainActivity builds it, and by services that start alone. */
    static Features current() {
        return current;
    }

    static void apply(Features f) {
        current = f == null ? ALL_OFF : f;
    }

    /** Shorthand for {@code current().on(f)}. */
    static boolean isOn(Feature f) {
        return current.on(f);
    }
}
