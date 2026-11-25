package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class WarmthTest {
    private static final int SUNRISE = 8 * 60, SUNSET = 16 * 60;

    @Test public void neutralByDayFullAtNight() {
        assertEquals(0f, Warmth.amount(12 * 60, SUNRISE, SUNSET), 0f);
        assertEquals(0f, Warmth.amount(SUNRISE + 60, SUNRISE, SUNSET), 0f);
        assertEquals(0f, Warmth.amount(SUNSET - 61, SUNRISE, SUNSET), 0f);
        assertEquals(1f, Warmth.amount(SUNSET + 60, SUNRISE, SUNSET), 0f);
        assertEquals(1f, Warmth.amount(23 * 60, SUNRISE, SUNSET), 0f);
        assertEquals(1f, Warmth.amount(3 * 60, SUNRISE, SUNSET), 0f);
        assertEquals(1f, Warmth.amount(SUNRISE - 1, SUNRISE, SUNSET), 0f);
    }

    @Test public void rampsOverTheHourEitherSideOfSunsetAndTheHourAfterSunrise() {
        assertEquals(0f, Warmth.amount(SUNSET - 60, SUNRISE, SUNSET), 0f);
        assertEquals(0.5f, Warmth.amount(SUNSET, SUNRISE, SUNSET), 0f);
        assertEquals(0.75f, Warmth.amount(SUNSET + 30, SUNRISE, SUNSET), 0f);
        assertEquals(1f, Warmth.amount(SUNRISE, SUNRISE, SUNSET), 0f);
        assertEquals(0.5f, Warmth.amount(SUNRISE + 30, SUNRISE, SUNSET), 0f);
    }

    @Test public void stepsOfOneTwentieth() {
        // 7 minutes into the evening ramp is 7/120 = 0.058: the nearest twentieth, 0.05.
        assertEquals(0.05f, Warmth.amount(SUNSET - 53, SUNRISE, SUNSET), 0f);
    }

    @Test public void dayLeavesTheColourAlone() {
        assertEquals(0xFF2266CC, Warmth.shift(0xFF2266CC, 0f));
    }

    @Test public void nightMovesTwelvePercentTowardsAmber() {
        // Black: 12 % of the way to 0xFFB46A is 255*.12=30.6, 180*.12=21.6, 106*.12=12.72, rounded 31, 22, 13.
        assertEquals(0xFF1F160D, Warmth.shift(0xFF000000, 1f));
        // White: 255, 255-75*.12=246, 255-149*.12=237.12.
        assertEquals(0xFFFFF6ED, Warmth.shift(0xFFFFFFFF, 1f));
        // Alpha is kept; half the night is half the shift (6 %): 0x20 + (255-32)*.06 = 45.38, 0x40 + (180-64)*.06 = 70.96,
        // 0xC0 + (106-192)*.06 = 186.84.
        assertEquals(0x802D47BB, Warmth.shift(0x802040C0, 0.5f));
    }

    @Test public void neverMoreThanTwelvePercent() {
        assertEquals(Warmth.shift(0xFF000000, 1f), Warmth.shift(0xFF000000, 3f));
    }
}
