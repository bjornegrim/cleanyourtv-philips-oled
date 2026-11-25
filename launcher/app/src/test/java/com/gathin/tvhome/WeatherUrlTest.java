package com.gathin.tvhome;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WeatherUrlTest {

    @Test
    public void aGoodPlaceMakesARequest() {
        String url = WeatherUrl.of(" 48.85", "2.35 ");
        assertNotNull(url);
        assertTrue(url.contains("latitude=48.85&longitude=2.35&"));
        // WeatherIcon picks the sun or the moon from it.
        assertTrue(url.contains("is_day"));
    }

    @Test
    public void anEmptyOrBadPlaceMakesNoRequest() {
        assertNull(WeatherUrl.of("", ""));
        assertNull(WeatherUrl.of("48.85", ""));
        assertNull(WeatherUrl.of("abc", "2.35"));
        assertNull(WeatherUrl.of("NaN", "2.35"));
        assertNull(WeatherUrl.of("91", "2.35"));
        assertNull(WeatherUrl.of("48.85", "181"));
    }
}
