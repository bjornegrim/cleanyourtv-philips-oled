package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class WeatherIconTest {
    private static void codes(int icon, boolean day, int... codes) {
        for (int code : codes) assertEquals("WMO " + code, icon, WeatherIcon.forCode(code, day));
    }

    @Test public void clearAndPartlyFollowDayAndNight() {
        codes(R.drawable.ic_weather_sun, true, 0, 1);
        codes(R.drawable.ic_weather_moon, false, 0, 1);
        codes(R.drawable.ic_weather_partly_day, true, 2);
        codes(R.drawable.ic_weather_partly_night, false, 2);
    }

    @Test public void everyOpenMeteoGroupHasItsIcon() {
        for (boolean day : new boolean[]{true, false}) {
            codes(R.drawable.ic_weather_cloud, day, 3);
            codes(R.drawable.ic_weather_fog, day, 45, 48);
            codes(R.drawable.ic_weather_drizzle, day, 51, 53, 55, 56, 57);
            codes(R.drawable.ic_weather_rain, day, 61, 63, 65, 66, 67, 80, 81, 82);
            codes(R.drawable.ic_weather_snow, day, 71, 73, 75, 77, 85, 86);
            codes(R.drawable.ic_weather_thunder, day, 95, 96, 99);
        }
    }

    @Test public void noReadingNoIconAndAnUnknownCodeIsACloud() {
        assertEquals(0, WeatherIcon.forCode(-1, true));
        assertEquals(R.drawable.ic_weather_cloud, WeatherIcon.forCode(42, true));
    }
}
