package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The streaming services and stores of Sweden as TMDB's watch providers name them (JustWatch data), and what the
 * launcher knows about each: its key (the lowercase id {@link ServiceId} has or will get), name, TMDB provider ids,
 * the Android TV app packages that open it, whether it streams with a subscription, for free or sells and rents, and
 * the names price sources give it. Pure Java and unit-tested (WatchProvidersTest).
 *
 * <p>The TMDB ids were read from {@code /watch/providers/movie} and {@code /tv} with {@code watch_region=SE} on
 * 2026-10-30. The packages were checked on Google Play: each one exists and lists TV as a device, except
 * {@code com.hbo.hbonow}, the old HBO Max app ServiceTable already tries. TMDB's add-on channels ("HBO Max Amazon
 * Channel", "Apple TV Amazon Channel") and niche services are left out, so their offers are dropped; Blockbuster
 * (423) closed in 2025 but is still in TMDB's data, so it is dropped on purpose.
 */
final class WatchProviders {

    /** The region every Phase 2 lookup uses (docs/rework.md, Region). */
    static final String REGION = "SE";

    static final class Entry {
        final String key;
        final String name;
        /** The launcher's service for this entry, or null until ServiceId has one (Stage 2 adds them). */
        final ServiceId service;
        final List<Integer> tmdbIds;
        final List<String> packages;
        final boolean subscription;
        final boolean free;
        final boolean store;
        /** Normalised names ({@link #norm}) price sources use for this store; empty for a service that sells nothing. */
        final List<String> storeNames;

        Entry(String key, String name, ServiceId service, int[] tmdbIds, String[] packages, boolean subscription,
              boolean free, boolean store, String[] storeNames) {
            this.key = key;
            this.name = name;
            this.service = service;
            List<Integer> ids = new ArrayList<>(tmdbIds.length);
            for (int id : tmdbIds) ids.add(id);
            this.tmdbIds = Collections.unmodifiableList(ids);
            this.packages = Collections.unmodifiableList(Arrays.asList(packages));
            this.subscription = subscription;
            this.free = free;
            this.store = store;
            List<String> names = new ArrayList<>(storeNames.length);
            for (String n : storeNames) names.add(norm(n));
            this.storeNames = Collections.unmodifiableList(names);
        }

        @Override
        public String toString() {
            return key;
        }
    }

    private static final int SUB = 1, FREE = 2, STORE = 4;

    private final Map<String, Entry> byKey = new LinkedHashMap<>();
    private final Map<Integer, Entry> byTmdb = new HashMap<>();
    private final Map<String, Entry> byStoreName = new HashMap<>();

    WatchProviders() {
        // Subscriptions the launcher already knows.
        put("netflix", "Netflix", ServiceId.NETFLIX, ids(8, 175, 1796), SUB, pk("com.netflix.ninja"));
        put("prime", "Prime Video", ServiceId.PRIME, ids(119), SUB, pk("com.amazon.amazonvideo.livingroom"));
        put("disney", "Disney+", ServiceId.DISNEY, ids(337), SUB, pk("com.disney.disneyplus"));
        put("max", "HBO Max", ServiceId.MAX, ids(1899, 384), SUB, pk("com.wbd.stream", "com.hbo.hbonow"));
        // TMDB calls Apple's subscription "Apple TV" since Apple renamed it in 2025; the launcher keeps "Apple TV+".
        put("appletvplus", "Apple TV+", ServiceId.APPLETVPLUS, ids(350), SUB, pk("com.apple.atve.androidtv.appletv"));
        put("hulu", "Hulu", ServiceId.HULU, ids(15), SUB, pk("com.hulu.livingroomplus"));
        // Stores the launcher already knows. Google Play Movies (3) is not in TMDB's SE list today.
        put("appletv", "Apple TV", ServiceId.APPLETV, ids(2), STORE, pk("com.apple.atve.androidtv.appletv"),
                "Apple TV", "Apple TV Store", "iTunes", "Apple iTunes", "AppleTV", "apple");
        put("googletv", "Google TV", ServiceId.GOOGLETV, ids(3), STORE, pk("com.google.android.videos"),
                "Google Play", "Google Play Movies", "Google Play Movies & TV", "Google TV");
        put("youtube", "YouTube", ServiceId.YOUTUBE, ids(192), FREE | STORE, pk("com.google.android.youtube.tv"),
                "YouTube", "YouTube Movies");
        // New for Sweden: no ServiceId yet.
        put("amazonvideo", "Amazon Video", null, ids(10), STORE, pk("com.amazon.amazonvideo.livingroom"),
                "Amazon Video", "Amazon", "Prime Video", "Amazon Prime Video", "prime", "Prime Video Store");
        put("viaplay", "Viaplay", null, ids(76), SUB | STORE, pk("com.viaplay.android"),
                "Viaplay", "Viaplay Store", "Viaplay Rent & Buy");
        put("skyshowtime", "SkyShowtime", null, ids(1773), SUB, pk("com.skyshowtime.skyshowtime.google"));
        put("svtplay", "SVT Play", null, ids(493), FREE, pk("se.svt.android.svtplay"));
        put("tv4play", "TV4 Play", null, ids(1944), SUB | FREE, pk("se.tv4.tv4playtab"));
        put("sfanytime", "SF Anytime", null, ids(426), STORE, pk("com.sfanytime.tv"), "SF Anytime", "SFAnytime");
        put("rakuten", "Rakuten TV", null, ids(35), STORE, pk("tv.wuaki.apptv"), "Rakuten TV", "Rakuten");
        put("tele2play", "Tele2 Play", null, ids(497), SUB | STORE, pk("com.tivo.android.comhem"),
                "Tele2 Play", "Tele2", "Comhem Play");
        // Not a TMDB provider in SE; kept so a price source that names it still maps.
        put("teliaplay", "Telia Play", null, ids(), SUB | STORE, pk("com.teliasonera.telia.playplus"),
                "Telia Play", "Telia", "Telia Filmbutiken");
        put("hayu", "Hayu", null, ids(223), SUB, pk("com.upst.hayu"));
        put("mubi", "MUBI", null, ids(11), SUB, pk("com.mubi"));
        put("crunchyroll", "Crunchyroll", null, ids(283), SUB, pk("com.crunchyroll.crunchyroid"));
        put("britbox", "BritBox", null, ids(151), SUB, pk("com.britbox.tv"));
        put("curiositystream", "Curiosity Stream", null, ids(190), SUB, pk("com.curiosity.curiositystream.androidtv"));
        put("plutotv", "Pluto TV", null, ids(300), FREE, pk("tv.pluto.android"));
        put("plex", "Plex", null, ids(538), FREE, pk("com.plexapp.android"));
        put("cineasterna", "Cineast", null, ids(496), FREE, pk("com.cineasterna.tv"));
    }

    private static int[] ids(int... ids) {
        return ids;
    }

    private static String[] pk(String... packages) {
        return packages;
    }

    private void put(String key, String name, ServiceId service, int[] tmdbIds, int kinds, String[] packages,
                     String... storeNames) {
        Entry e = new Entry(key, name, service, tmdbIds, packages, (kinds & SUB) != 0, (kinds & FREE) != 0,
                (kinds & STORE) != 0, storeNames);
        byKey.put(key, e);
        for (int id : tmdbIds) byTmdb.put(id, e);
        for (String n : e.storeNames) byStoreName.put(n, e);
    }

    /** Every entry, subscriptions first, in the order above. */
    List<Entry> all() {
        return Collections.unmodifiableList(new ArrayList<>(byKey.values()));
    }

    /** The entry for a TMDB provider id, or null for one the launcher leaves out. */
    Entry byTmdbId(int providerId) {
        return byTmdb.get(providerId);
    }

    Entry byKey(String key) {
        return key == null ? null : byKey.get(key.trim().toLowerCase(Locale.ROOT));
    }

    /** The entry for a launcher service, or null. */
    Entry of(ServiceId service) {
        return service == null ? null : byKey.get(service.id);
    }

    /**
     * The store a price source means by {@code name} ("iTunes", "Amazon", "SF Anytime"; case, spaces and punctuation
     * ignored), or null. Only for rent and buy: "Prime Video" here is Amazon's store, not the subscription.
     */
    Entry storeForName(String name) {
        return name == null ? null : byStoreName.get(norm(name));
    }

    /** Lowercase letters and digits only: "Google Play Movies & TV" becomes "googleplaymoviestv". */
    static String norm(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) b.append(c);
        }
        return b.toString();
    }

    /** The currency prices are in for a region ("SE" gives "SEK"), or null for one the launcher doesn't price. */
    static String currency(String region) {
        if (region == null) return null;
        switch (region.trim().toUpperCase(Locale.ROOT)) {
            case "SE":
                return "SEK";
            case "NO":
                return "NOK";
            case "DK":
                return "DKK";
            case "FI":
            case "DE":
            case "FR":
            case "NL":
                return "EUR";
            case "GB":
                return "GBP";
            case "US":
                return "USD";
            default:
                return null;
        }
    }
}
