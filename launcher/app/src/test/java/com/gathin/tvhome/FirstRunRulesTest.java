package com.gathin.tvhome;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** First run's rules (design_preview/src/screens/setup.ts): steps, done rows, focus, the footer and the keys. */
public class FirstRunRulesTest {
    private static final List<String> FOUR = FirstRunRules.STEPS;
    private static final List<String> THREE = Arrays.asList("services", "stores", "weather");

    private static Set<String> opened(String... ids) {
        return new HashSet<>(Arrays.asList(ids));
    }

    @Test
    public void theStepsFollowTheSettingsOrderAndLeaveOutWhatIsMissing() {
        assertEquals(FOUR, FirstRunRules.steps(SettingsNav.ORDER));
        // Without a TMDB token Launcher settings has no Account section: three steps.
        List<String> noAccount = Arrays.asList("services", "stores", "weather", "apps", "theme", "about");
        assertEquals(THREE, FirstRunRules.steps(noAccount));
    }

    @Test
    public void theStepsAreTheSettingsSectionsOfTheSameName() {
        for (String id : FirstRunRules.STEPS) assertTrue(id, SettingsNav.ORDER.contains(id));
    }

    @Test
    public void aRowIsDoneOnceItsPaneWasOpened() {
        assertFalse(FirstRunRules.done("services", opened()));
        assertTrue(FirstRunRules.done("services", opened("services")));
        assertFalse(FirstRunRules.done("stores", opened("services")));
    }

    @Test
    public void doneCountsOnlyTheStepsShown() {
        assertEquals(0, FirstRunRules.doneCount(THREE, opened()));
        assertEquals(1, FirstRunRules.doneCount(THREE, opened("account", "weather")));
        assertFalse(FirstRunRules.allDone(THREE, opened("account", "weather")));
        assertTrue(FirstRunRules.allDone(THREE, opened("services", "stores", "weather")));
        assertFalse(FirstRunRules.allDone(FOUR, opened("services", "stores", "weather")));
    }

    @Test
    public void focusGoesBackToTheNextRowNotDoneThenStart() {
        assertEquals("account", FirstRunRules.nextFocus(FOUR, opened()));
        assertEquals("stores", FirstRunRules.nextFocus(FOUR, opened("account", "services")));
        // Not "the row after the one just done": the first not done, wherever it is.
        assertEquals("account", FirstRunRules.nextFocus(FOUR, opened("services", "stores")));
        assertNull(FirstRunRules.nextFocus(FOUR, opened("account", "services", "stores", "weather")));
        assertNull(FirstRunRules.nextFocus(THREE, opened("services", "stores", "weather")));
    }

    @Test
    public void theFooterCountsStepsAndOffersTheNextOrDone() {
        assertEquals(2, FirstRunRules.stepNumber(FOUR, "services"));
        assertEquals(1, FirstRunRules.stepNumber(THREE, "services"));
        assertEquals(0, FirstRunRules.stepNumber(THREE, "account"));
        assertEquals("stores", FirstRunRules.nextStep(FOUR, "services"));
        assertEquals("services", FirstRunRules.nextStep(FOUR, "account"));
        assertNull(FirstRunRules.nextStep(FOUR, "weather"));
        assertNull(FirstRunRules.nextStep(THREE, "weather"));
        assertNull(FirstRunRules.nextStep(THREE, "account"));
        assertNull(FirstRunRules.nextStep(Collections.emptyList(), "weather"));
    }

    @Test
    public void onlyTheAccountsNotSignedInIsAPlaceholder() {
        assertTrue(FirstRunRules.placeholder("account", "Not signed in", "Not signed in"));
        assertFalse(FirstRunRules.placeholder("account", "Waiting…", "Not signed in"));
        assertFalse(FirstRunRules.placeholder("account", "evening_cinema", "Not signed in"));
        assertFalse(FirstRunRules.placeholder("weather", "Not signed in", "Not signed in"));
        assertFalse(FirstRunRules.placeholder("account", null, "Not signed in"));
    }

    @Test
    public void aValueThatOnlyRepeatsThePillIsLeftOut() {
        assertTrue(FirstRunRules.repeatsPill("Default", "Default"));
        assertFalse(FirstRunRules.repeatsPill("Stockholm", "Default"));
        assertFalse(FirstRunRules.repeatsPill("5 ticked", "Suggested"));
        assertFalse(FirstRunRules.repeatsPill(null, "Default"));
    }

    @Test
    public void itOpensByItselfOnlyWhenConfiguredAndOnlyOnce() {
        assertFalse(FirstRunRules.showOnStart(false, false));
        assertTrue(FirstRunRules.showOnStart(true, false));
        assertFalse(FirstRunRules.showOnStart(true, true));
        assertFalse(FirstRunRules.showOnStart(false, true));
    }

    @Test
    public void rightOnARowStaysAndDownFromTheLastRowReachesStart() {
        assertSame(FirstRunRules.Move.STAY, FirstRunRules.onKey(Screen.Key.RIGHT, false, false));
        assertSame(FirstRunRules.Move.STAY, FirstRunRules.onKey(Screen.Key.RIGHT, false, true));
        assertSame(FirstRunRules.Move.START, FirstRunRules.onKey(Screen.Key.DOWN, false, true));
        assertSame(FirstRunRules.Move.PASS, FirstRunRules.onKey(Screen.Key.DOWN, false, false));
        assertSame(FirstRunRules.Move.PASS, FirstRunRules.onKey(Screen.Key.UP, false, false));
        assertSame(FirstRunRules.Move.PASS, FirstRunRules.onKey(Screen.Key.OK, false, false));
    }

    @Test
    public void leftAndRightOnStartStayAndUpLeavesIt() {
        assertSame(FirstRunRules.Move.STAY, FirstRunRules.onKey(Screen.Key.LEFT, true, false));
        assertSame(FirstRunRules.Move.STAY, FirstRunRules.onKey(Screen.Key.RIGHT, true, false));
        assertSame(FirstRunRules.Move.PASS, FirstRunRules.onKey(Screen.Key.UP, true, false));
        assertSame(FirstRunRules.Move.PASS, FirstRunRules.onKey(Screen.Key.OK, true, false));
    }

    @Test
    public void backGoesHomeFromAnywhere() {
        assertSame(FirstRunRules.Move.HOME, FirstRunRules.onKey(Screen.Key.BACK, false, false));
        assertSame(FirstRunRules.Move.HOME, FirstRunRules.onKey(Screen.Key.BACK, true, false));
    }
}
