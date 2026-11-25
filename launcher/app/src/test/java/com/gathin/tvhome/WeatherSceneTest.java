package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The screensaver's weather scenes: WMO code and daylight to scene, the night-mode window and the sun's polar cases. */
public class WeatherSceneTest {

    @Test
    public void clearSkiesAreAuroraByNightAndClearByDay() {
        for (int code : new int[]{0, 1, 2, -1}) {
            assertEquals(WeatherScene.AURORA, WeatherScene.pick(code, false));
            assertEquals(WeatherScene.CLEAR, WeatherScene.pick(code, true));
        }
    }

    @Test
    public void weatherPicksItsSceneDayAndNight() {
        for (boolean day : new boolean[]{true, false}) {
            for (int code : new int[]{3, 45, 48}) assertEquals(WeatherScene.FOG, WeatherScene.pick(code, day));
            for (int code : new int[]{51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82, 95, 96, 99}) {
                assertEquals(WeatherScene.RAIN, WeatherScene.pick(code, day));
            }
            for (int code : new int[]{71, 73, 75, 77, 85, 86}) assertEquals(WeatherScene.SNOW, WeatherScene.pick(code, day));
        }
    }

    @Test
    public void nightModeRunsFrom2300Until0600() {
        assertFalse(WeatherScene.nightMode(22 * 60 + 59));
        assertTrue(WeatherScene.nightMode(23 * 60));
        assertTrue(WeatherScene.nightMode(0));
        assertTrue(WeatherScene.nightMode(5 * 60 + 59));
        assertFalse(WeatherScene.nightMode(6 * 60));
        assertFalse(WeatherScene.nightMode(12 * 60));
    }

    @Test
    public void dayFollowsTheSunOrItsPolarState() {
        assertFalse(WeatherScene.isDay(8 * 60 + 17, 8 * 60 + 18, 15 * 60 + 30, WeatherScene.SUN_NORMAL));
        assertTrue(WeatherScene.isDay(8 * 60 + 18, 8 * 60 + 18, 15 * 60 + 30, WeatherScene.SUN_NORMAL));
        assertFalse(WeatherScene.isDay(15 * 60 + 30, 8 * 60 + 18, 15 * 60 + 30, WeatherScene.SUN_NORMAL));
        assertFalse(WeatherScene.isDay(12 * 60, 0, 0, WeatherScene.POLAR_NIGHT));
        assertTrue(WeatherScene.isDay(0, 0, 0, WeatherScene.MIDNIGHT_SUN));
    }

    @Test
    public void theAuroraPeaksInTheNightAndNeverShowsByDayOrUnderCloud() {
        float midnight = WeatherScene.scene(WeatherScene.AURORA, 0, 8.3, 15.5, WeatherScene.SUN_NORMAL, false)[WeatherScene.AURORA_AMOUNT];
        float evening = WeatherScene.scene(WeatherScene.AURORA, 17, 8.3, 15.5, WeatherScene.SUN_NORMAL, false)[WeatherScene.AURORA_AMOUNT];
        assertTrue(midnight > 0.9f);
        assertTrue(evening > 0f && evening < midnight);
        assertEquals(0f, WeatherScene.scene(WeatherScene.CLEAR, 12, 8.3, 15.5, WeatherScene.SUN_NORMAL, false)[WeatherScene.AURORA_AMOUNT], 0f);
        assertEquals(0f, WeatherScene.scene(WeatherScene.SNOW, 0, 8.3, 15.5, WeatherScene.SUN_NORMAL, false)[WeatherScene.AURORA_AMOUNT], 0f);
        assertEquals(0f, WeatherScene.scene(WeatherScene.AURORA, 0, 0, 0, WeatherScene.MIDNIGHT_SUN, false)[WeatherScene.AURORA_AMOUNT], 0f);
        assertTrue(WeatherScene.scene(WeatherScene.AURORA, 0, 0, 0, WeatherScene.POLAR_NIGHT, false)[WeatherScene.AURORA_AMOUNT] > 0.9f);
    }

    @Test
    public void aChangeOfSceneFadesOverTwoSeconds() {
        float[] a = WeatherScene.plain(false), b = WeatherScene.plain(true);
        WeatherScene scene = new WeatherScene(a);
        float[] out = new float[WeatherScene.SIZE];
        scene.current(0, out);
        assertEquals(0f, out[WeatherScene.NIGHT], 0f);
        scene.fadeTo(b, 1_000);
        scene.current(2_000, out);
        assertEquals(0.5f, out[WeatherScene.NIGHT], 1e-6f);   // halfway, and smoothstep(0.5) is 0.5
        scene.current(3_000, out);
        assertEquals(1f, out[WeatherScene.NIGHT], 0f);
    }
}
