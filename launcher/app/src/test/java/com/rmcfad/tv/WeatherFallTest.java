package com.rmcfad.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public class WeatherFallTest {
    private static WeatherFall.Fall of(Weather.Condition c, SeasonCalendar.Period p) {
        return WeatherFall.of(c, p);
    }

    @Test
    public void theRealWeatherFirst() {
        assertEquals(WeatherFall.Fall.SNOW, of(Weather.Condition.SNOW, SeasonCalendar.Period.SNOW));
        assertEquals(WeatherFall.Fall.SNOW, of(Weather.Condition.SNOW_SHOWERS, SeasonCalendar.Period.CHRISTMAS));
        assertEquals(WeatherFall.Fall.RAIN, of(Weather.Condition.RAIN, SeasonCalendar.Period.MIDSUMMER));
        assertEquals(WeatherFall.Fall.RAIN, of(Weather.Condition.DRIZZLE, SeasonCalendar.Period.SNOW));
        assertEquals(WeatherFall.Fall.RAIN, of(Weather.Condition.THUNDERSTORM, SeasonCalendar.Period.BLOOM));
        // A clear day in a snowy period: nothing falls, the picture's own still flakes are enough.
        assertEquals(WeatherFall.Fall.NONE, of(Weather.Condition.CLEAR, SeasonCalendar.Period.SNOW));
        assertEquals(WeatherFall.Fall.NONE, of(Weather.Condition.OVERCAST, SeasonCalendar.Period.SPRING_RAIN));
        assertEquals(WeatherFall.Fall.NONE, of(Weather.Condition.UNKNOWN, SeasonCalendar.Period.SNOW));
    }

    @Test
    public void noSnowOverASummerPicture() {
        assertEquals(WeatherFall.Fall.NONE, of(Weather.Condition.SNOW, SeasonCalendar.Period.MIDSUMMER));
        assertEquals(WeatherFall.Fall.NONE, of(Weather.Condition.SNOW, SeasonCalendar.Period.BLOOM));
        assertEquals(WeatherFall.Fall.SNOW, of(Weather.Condition.SNOW, SeasonCalendar.Period.AUTUMN));
        assertFalse(WeatherFall.cold(SeasonCalendar.Period.PRIDE));
    }

    @Test
    public void theSeasonWithoutTheWeather() {
        assertEquals(WeatherFall.Fall.SNOW, of(null, SeasonCalendar.Period.SNOW));
        assertEquals(WeatherFall.Fall.SNOW, of(null, SeasonCalendar.Period.CHRISTMAS));
        assertEquals(WeatherFall.Fall.RAIN, of(null, SeasonCalendar.Period.SPRING_RAIN));
        assertEquals(WeatherFall.Fall.NONE, of(null, SeasonCalendar.Period.LATE_SUMMER));
        assertEquals(WeatherFall.Fall.NONE, of(null, SeasonCalendar.Period.HALLOWEEN));
    }
}
