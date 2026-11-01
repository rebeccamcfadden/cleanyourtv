package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.Test;

public class DayPhaseTest {
    private static final ZoneId SWEDEN = ZoneId.of("Europe/Stockholm");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final double STOCKHOLM_LAT = 59.33, STOCKHOLM_LON = 18.07;
    private static final double GOTHENBURG_LAT = 57.71, GOTHENBURG_LON = 11.97;

    private static ZonedDateTime at(int y, int m, int d, int h, int min, ZoneId zone) {
        return ZonedDateTime.of(y, m, d, h, min, 0, 0, zone);
    }

    /** Sunrise is 40 minutes after dawn starts; sunset 50 minutes after sunset starts. */
    private static LocalTime sunrise(DayPhase.Day d, ZoneId zone) {
        return d.edges[0].plusSeconds(DayPhase.DAWN_BEFORE_MIN * 60).atZone(zone).toLocalTime();
    }

    private static LocalTime sunset(DayPhase.Day d, ZoneId zone) {
        return d.edges[2].plusSeconds(DayPhase.SUNSET_BEFORE_MIN * 60).atZone(zone).toLocalTime();
    }

    private static void near(LocalTime expected, LocalTime actual) {
        long off = Math.abs(Duration.between(expected, actual).toMinutes());
        assertTrue("expected about " + expected + " but was " + actual, off <= 3);
    }

    @Test
    public void midsummerInStockholm() {
        DayPhase.Day d = DayPhase.day(LocalDate.of(2026, 6, 21), SWEDEN, STOCKHOLM_LAT, STOCKHOLM_LON);
        assertNull(d.always);
        near(LocalTime.of(3, 31), sunrise(d, SWEDEN));
        near(LocalTime.of(22, 8), sunset(d, SWEDEN));
    }

    @Test
    public void midwinterInGothenburg() {
        DayPhase.Day d = DayPhase.day(LocalDate.of(2026, 12, 21), SWEDEN, GOTHENBURG_LAT, GOTHENBURG_LON);
        near(LocalTime.of(8, 56), sunrise(d, SWEDEN));
        near(LocalTime.of(15, 23), sunset(d, SWEDEN));
    }

    @Test
    public void newYorkInSpring() {
        DayPhase.Day d = DayPhase.day(LocalDate.of(2026, 4, 1), NEW_YORK, 40.71, -74.01);
        near(LocalTime.of(6, 39), sunrise(d, NEW_YORK));
        near(LocalTime.of(19, 19), sunset(d, NEW_YORK));
    }

    @Test
    public void phasesThroughADay() {
        double lat = GOTHENBURG_LAT, lon = GOTHENBURG_LON;
        assertEquals(DayPhase.NIGHT, DayPhase.at(at(2026, 12, 21, 7, 0, SWEDEN), lat, lon));
        assertEquals(DayPhase.DAWN, DayPhase.at(at(2026, 12, 21, 8, 40, SWEDEN), lat, lon));
        assertEquals(DayPhase.DAY, DayPhase.at(at(2026, 12, 21, 12, 0, SWEDEN), lat, lon));
        assertEquals(DayPhase.SUNSET, DayPhase.at(at(2026, 12, 21, 15, 0, SWEDEN), lat, lon));
        assertEquals(DayPhase.NIGHT, DayPhase.at(at(2026, 12, 21, 16, 30, SWEDEN), lat, lon));
    }

    @Test
    public void nextChangeIsTheNextEdge() {
        double lat = GOTHENBURG_LAT, lon = GOTHENBURG_LON;
        ZonedDateTime noon = at(2026, 12, 21, 12, 0, SWEDEN);
        DayPhase.Day d = DayPhase.day(noon.toLocalDate(), SWEDEN, lat, lon);
        assertEquals(d.edges[2], DayPhase.nextChange(noon, lat, lon).toInstant());
        ZonedDateTime late = at(2026, 12, 21, 23, 0, SWEDEN);
        DayPhase.Day tomorrow = DayPhase.day(LocalDate.of(2026, 12, 22), SWEDEN, lat, lon);
        assertEquals(tomorrow.edges[0], DayPhase.nextChange(late, lat, lon).toInstant());
        // The edge itself belongs to the phase it starts.
        assertEquals(DayPhase.SUNSET, DayPhase.at(d.edges[2].atZone(SWEDEN), lat, lon));
    }

    @Test
    public void polarDayAndNight() {
        double tromsoLat = 69.65, tromsoLon = 18.96;
        ZoneId oslo = ZoneId.of("Europe/Oslo");
        assertEquals(DayPhase.DAY, DayPhase.at(at(2026, 6, 21, 1, 0, oslo), tromsoLat, tromsoLon));
        assertEquals(DayPhase.NIGHT, DayPhase.at(at(2026, 12, 21, 12, 0, oslo), tromsoLat, tromsoLon));
        assertEquals(at(2026, 6, 22, 0, 0, oslo), DayPhase.nextChange(at(2026, 6, 21, 13, 0, oslo), tromsoLat,
                tromsoLon));
    }

    @Test
    public void shortDayHandsDawnStraightToSunset() {
        // Just south of the polar circle in midwinter: about an hour of sun.
        ZoneId oslo = ZoneId.of("Europe/Oslo");
        DayPhase.Day d = DayPhase.day(LocalDate.of(2026, 12, 21), oslo, 67.0, 14.1);
        assertNull(d.always);
        assertEquals(d.edges[1], d.edges[2]);
        assertTrue(d.edges[0].isBefore(d.edges[1]) && d.edges[2].isBefore(d.edges[3]));
    }

    @Test
    public void withoutAPlaceTheFixedTimes() {
        DayPhase.Day d = DayPhase.day(LocalDate.of(2026, 3, 1), SWEDEN, Double.NaN, Double.NaN);
        assertEquals(LocalTime.of(6, 20), d.edges[0].atZone(SWEDEN).toLocalTime());
        assertEquals(LocalTime.of(19, 40), d.edges[3].atZone(SWEDEN).toLocalTime());
        assertEquals(DayPhase.DAY, DayPhase.at(at(2026, 3, 1, 12, 0, SWEDEN), Double.NaN, Double.NaN));
    }

    @Test
    public void slugs() {
        assertEquals("dawn", DayPhase.DAWN.slug());
        assertEquals("sunset", DayPhase.SUNSET.slug());
    }
}
