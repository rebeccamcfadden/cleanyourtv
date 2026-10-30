package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Motion's three values, and how a TV without a saved one keeps the Animations switch and reduced_motion. */
public class MotionChoiceTest {

    @Test
    public void savedCodesParse() {
        assertEquals(MotionChoice.FULL, MotionChoice.parse("full"));
        assertEquals(MotionChoice.REDUCED, MotionChoice.parse(" reduced "));
        assertEquals(MotionChoice.OFF, MotionChoice.parse("off"));
        assertNull(MotionChoice.parse("Fast"));
        assertNull(MotionChoice.parse(null));
    }

    @Test
    public void withoutASavedChoiceTheOldSettingsDecide() {
        assertEquals(MotionChoice.FULL, MotionChoice.resolve(null, true, false));
        assertEquals(MotionChoice.REDUCED, MotionChoice.resolve(null, true, true));
        assertEquals(MotionChoice.OFF, MotionChoice.resolve(null, false, false));
        assertEquals(MotionChoice.OFF, MotionChoice.resolve(null, false, true));
        assertEquals(MotionChoice.FULL, MotionChoice.resolve("sideways", true, false));
    }

    @Test
    public void aSavedChoiceWins() {
        assertEquals(MotionChoice.FULL, MotionChoice.resolve("full", false, true));
        assertEquals(MotionChoice.REDUCED, MotionChoice.resolve("reduced", true, false));
        assertEquals(MotionChoice.OFF, MotionChoice.resolve("off", true, false));
    }

    @Test
    public void offIsTheOldAnimationsOffAndReducedIsReducedMotion() {
        assertFalse(MotionChoice.FULL.reduced());
        assertTrue(MotionChoice.FULL.animations());
        assertTrue(MotionChoice.REDUCED.reduced());
        assertTrue(MotionChoice.REDUCED.animations());
        assertTrue(MotionChoice.OFF.reduced());
        assertFalse(MotionChoice.OFF.animations());
    }

    @Test
    public void savingAndReadingBackGivesTheSameChoice() {
        for (MotionChoice m : MotionChoice.values()) {
            // What MotionPrefs writes (the code and the Animations switch) and Config reads back.
            assertEquals(m, MotionChoice.resolve(m.code, m.animations(), false));
            assertEquals(m, MotionChoice.resolve(null, m.animations(), m.reduced()));
        }
    }

    @Test
    public void okStepsFullReducedOffAndRoundAgain() {
        assertEquals(MotionChoice.REDUCED, MotionChoice.FULL.next());
        assertEquals(MotionChoice.OFF, MotionChoice.REDUCED.next());
        assertEquals(MotionChoice.FULL, MotionChoice.OFF.next());
    }
}
