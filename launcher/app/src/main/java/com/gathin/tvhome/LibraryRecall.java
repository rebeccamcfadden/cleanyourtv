package com.gathin.tvhome;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * What Movies and TV Shows keep while they are left (the project lead, 2026-11-01, F2): the filters, the chosen list
 * chip and the sort start cleared each time the TV is turned on, are kept while the household leaves the page and
 * comes back, and are cleared again after an hour away. Each page keeps its own.
 *
 * <p>"Turned on" is the launcher's process starting, which a boot always does: {@link LibraryPage} holds one of these
 * in a static field, so it lasts as long as the process and no longer. That also carries a page's choices across the
 * activity being made again (a theme or a setting changing), within the hour. The clock is injected; the page's is
 * {@code SystemClock.elapsedRealtime()}, which counts on through standby. Pure Java, unit-tested.
 */
final class LibraryRecall {

    /** How long away still keeps the page's choices: an hour; longer clears them. */
    static final long AWAY_MS = 60 * 60 * 1000L;

    /** A page's choices as they were when it was left. */
    static final class Saved {
        /** {@link LibraryFilters#save}. */
        final String filters;
        /** {@link ShownList#key}. */
        final String list;
        final LibrarySort sort;
        final long leftAt;

        Saved(String filters, String list, LibrarySort sort, long leftAt) {
            this.filters = filters;
            this.list = list;
            this.sort = sort;
            this.leftAt = leftAt;
        }
    }

    private final LongSupplier clock;
    private final Map<Kind, Saved> saved = new EnumMap<>(Kind.class);

    LibraryRecall(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * The page is left: keep its choices and the time. Leaving again before coming back (the tab, then standby) keeps
     * the first time, so the hour counts from when it was last on screen.
     */
    void left(Kind kind, String filters, String list, LibrarySort sort) {
        Saved was = saved.get(kind);
        long at = was != null ? was.leftAt : clock.getAsLong();
        saved.put(kind, new Saved(filters, list, sort, at));
    }

    /**
     * Back on the page, or the page made again: what it had set if it was left an hour ago or less, else null (start
     * again: no filters, All lists, A–Z). Either way the page is on screen again, so nothing stays kept.
     */
    Saved back(Kind kind) {
        Saved s = saved.remove(kind);
        if (s == null) return null;
        long away = clock.getAsLong() - s.leftAt;
        return away >= 0 && away <= AWAY_MS ? s : null;
    }

    /** Whether the page was left and not yet back. */
    boolean isLeft(Kind kind) {
        return saved.containsKey(kind);
    }
}
