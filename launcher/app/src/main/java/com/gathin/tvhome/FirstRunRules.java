package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * First run's rules (design_preview/README.md, First run; src/screens/setup.ts), kept apart from the views so they
 * are tested on the JVM: which steps show, when a row counts as done, where focus goes back to, the footer's step
 * and Next, the keys on the checklist, and whether the checklist opens by itself on this start.
 */
final class FirstRunRules {
    private FirstRunRules() {}

    /** The checklist's rows, in order, as SettingsSection ids. */
    static final List<String> STEPS = Collections.unmodifiableList(Arrays.asList(
            "account", "services", "stores", "weather"));

    /** The steps this build shows: STEPS among the sections present (Account is left out without a TMDB token). */
    static List<String> steps(Collection<String> present) {
        List<String> out = new ArrayList<>();
        for (String id : STEPS) if (present.contains(id)) out.add(id);
        return out;
    }

    /**
     * When a row counts as done. Pending the project lead (BACKLOG.md, First run): for now, as in the prototype, once
     * its pane was opened, whatever was chosen there.
     */
    static boolean done(String id, Set<String> opened) {
        return opened.contains(id);
    }

    static int doneCount(List<String> steps, Set<String> opened) {
        int n = 0;
        for (String id : steps) if (done(id, opened)) n++;
        return n;
    }

    static boolean allDone(List<String> steps, Set<String> opened) {
        return doneCount(steps, opened) == steps.size();
    }

    /** Where focus goes back to from a step: the first row not done, or null for Start when every row is. */
    static String nextFocus(List<String> steps, Set<String> opened) {
        for (String id : steps) if (!done(id, opened)) return id;
        return null;
    }

    /** The "N" of "Step N of M" for a step (1-based); 0 when it is not one of the steps. */
    static int stepNumber(List<String> steps, String id) {
        return steps.indexOf(id) + 1;
    }

    /** The step the footer's Next opens after id; null on the last step, whose button is Done. */
    static String nextStep(List<String> steps, String id) {
        int i = steps.indexOf(id);
        return i < 0 || i + 1 >= steps.size() ? null : steps.get(i + 1);
    }

    /**
     * Whether the account's value is only "Not signed in": the checklist then shows no value (its pill says
     * Optional), and the split's row says Optional instead (settings.ts paintValues).
     */
    static boolean placeholder(String id, String value, String notSignedIn) {
        return "account".equals(id) && value != null && value.equals(notSignedIn);
    }

    /**
     * Whether a row's value only repeats its pill (Weather's "Default" beside the Default pill): the checklist leaves
     * it out while the pill shows.
     */
    static boolean repeatsPill(String value, String pill) {
        return value != null && pill != null && value.equalsIgnoreCase(pill);
    }

    /**
     * Whether the checklist opens by itself on this start. Pending the project lead (BACKLOG.md, First run): only
     * when config.xml's first_run_checklist (or a pushed config.json) asks, and only the first time.
     */
    static boolean showOnStart(boolean configured, boolean shownBefore) {
        return configured && !shownBefore;
    }

    enum Move {
        /** The Shell's own handling: spatial moves, OK. */
        PASS,
        /** Nothing: → on a row (the help pane has nothing to focus), ← and → on Start. */
        STAY,
        /** ↓ from the last row. */
        START,
        /** Back: First run goes Home. */
        HOME
    }

    /** A key on the checklist, with focus on Start or on a row (the last one or not). */
    static Move onKey(Screen.Key key, boolean onStart, boolean onLastRow) {
        if (key == Screen.Key.BACK) return Move.HOME;
        if (onStart) return key == Screen.Key.LEFT || key == Screen.Key.RIGHT ? Move.STAY : Move.PASS;
        if (key == Screen.Key.RIGHT) return Move.STAY;
        if (key == Screen.Key.DOWN && onLastRow) return Move.START;
        return Move.PASS;
    }
}
