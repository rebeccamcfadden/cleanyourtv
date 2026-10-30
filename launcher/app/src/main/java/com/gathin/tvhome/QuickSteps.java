package com.gathin.tvhome;

/**
 * The stepping rows of the quick-settings dropdown (design_preview/src/screens/quickSettings.ts): OK moves Motion or
 * the sleep timer to its next value in place, wrapping round, and small dots show which of them is on. Pure Java,
 * unit-tested (QuickStepsTest).
 */
final class QuickSteps {
    private QuickSteps() {}

    /** The sleep timer's steps in minutes, Off first (the prototype's SLEEP_STEPS). */
    static final int[] SLEEP_MINUTES = {0, 30, 60, 90, 120};

    /** The step after {@code index} of {@code count}, back to the first after the last; the first for a bad index. */
    static int next(int index, int count) {
        if (count <= 0) return 0;
        if (index < 0 || index >= count - 1) return 0;
        return index + 1;
    }

    /** Which sleep step {@code minutes} is, or 0 (Off) for a value that is not one. */
    static int sleepIndex(int minutes) {
        for (int i = 0; i < SLEEP_MINUTES.length; i++) if (SLEEP_MINUTES[i] == minutes) return i;
        return 0;
    }

    /** OK on Sleep timer: Off, 30, 60, 90, 120 minutes, then Off again. */
    static int nextSleep(int minutes) {
        return SLEEP_MINUTES[next(sleepIndex(minutes), SLEEP_MINUTES.length)];
    }
}
