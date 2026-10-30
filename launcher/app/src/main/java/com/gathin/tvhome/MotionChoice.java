package com.gathin.tvhome;

/**
 * Launcher settings' Motion (design_preview/src/core/settings.ts; the quick-settings dropdown's Motion row): Full,
 * Reduced or Off, saved on the TV under {@link #KEY}. Older settings had only the Animations switch ("animations")
 * and config.xml's reduced_motion; without a saved Motion they decide it, so a TV keeps what it had. Pure Java,
 * unit-tested (MotionChoiceTest); MotionPrefs reads and writes it.
 *
 * <p>The launcher has one reduced-motion mode (Ui.reducedMotion()), so Reduced and Off both use it for now; the
 * spec's difference (Reduced keeps the fades) is in BACKLOG.md.
 */
enum MotionChoice {
    FULL("full"),
    REDUCED("reduced"),
    OFF("off");

    /** The preference key, and its values {@code full}, {@code reduced} and {@code off}. */
    static final String KEY = "motion";

    final String code;

    MotionChoice(String code) {
        this.code = code;
    }

    /** The choice saved as {@code code}, or null for none or an unknown value. */
    static MotionChoice parse(String code) {
        if (code == null) return null;
        for (MotionChoice m : values()) if (m.code.equals(code.trim())) return m;
        return null;
    }

    /**
     * A saved choice wins, Full included (it turns off config.xml's reduced_motion on this TV). Without one, the
     * Animations switch off is Off, reduced_motion is Reduced, and anything else is Full.
     */
    static MotionChoice resolve(String saved, boolean animations, boolean configReduced) {
        MotionChoice m = parse(saved);
        if (m != null) return m;
        if (!animations) return OFF;
        return configReduced ? REDUCED : FULL;
    }

    /** Whether the launcher's reduced motion applies (Config.reducedMotion()). */
    boolean reduced() {
        return this != FULL;
    }

    /** What the old Animations switch shows: on unless Off. */
    boolean animations() {
        return this != OFF;
    }

    /** OK on the Motion row: Full, Reduced, Off, then Full again. */
    MotionChoice next() {
        return values()[QuickSteps.next(ordinal(), values().length)];
    }
}
