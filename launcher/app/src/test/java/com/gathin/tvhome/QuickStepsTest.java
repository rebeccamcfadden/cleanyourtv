package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/** The quick-settings dropdown's stepping rows: wrapping round, and the sleep timer's steps. */
public class QuickStepsTest {

    @Test
    public void nextWrapsRound() {
        assertEquals(1, QuickSteps.next(0, 3));
        assertEquals(2, QuickSteps.next(1, 3));
        assertEquals(0, QuickSteps.next(2, 3));
    }

    @Test
    public void aBadIndexStartsAgain() {
        assertEquals(0, QuickSteps.next(-1, 3));
        assertEquals(0, QuickSteps.next(7, 3));
        assertEquals(0, QuickSteps.next(0, 0));
        assertEquals(0, QuickSteps.next(0, 1));
    }

    @Test
    public void sleepStepsOffThenEveryHalfHourToTwoHours() {
        int minutes = 0;
        int[] seen = new int[6];
        for (int i = 0; i < seen.length; i++) {
            seen[i] = minutes;
            minutes = QuickSteps.nextSleep(minutes);
        }
        assertArrayEquals(new int[]{0, 30, 60, 90, 120, 0}, seen);
    }

    @Test
    public void sleepDotsFollowTheValue() {
        assertEquals(0, QuickSteps.sleepIndex(0));
        assertEquals(2, QuickSteps.sleepIndex(60));
        assertEquals(4, QuickSteps.sleepIndex(120));
        assertEquals(0, QuickSteps.sleepIndex(45));
        assertEquals(30, QuickSteps.nextSleep(45));
    }
}
