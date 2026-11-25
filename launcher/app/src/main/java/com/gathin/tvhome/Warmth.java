package com.gathin.tvhome;

/**
 * time_warmth: how far the aura and the spotlight glow lean towards a warm amber at this time of day. Neutral by day;
 * up over the hour either side of sunset, full through the night, back down in the hour after sunrise. Pure maths,
 * so it runs in JVM tests.
 */
final class Warmth {
    /** At full night a colour moves this share of the way to WARM. */
    static final float MAX = 0.12f;
    /** The warm end, as 0xRRGGBB: a soft amber, like a lamp. */
    static final int WARM = 0xFFB46A;
    /** The amount moves in steps of 1/20, so the glow is retinted at most every few minutes, never per frame. */
    private static final int STEPS = 20;

    private Warmth() {
    }

    /** 0 by day to 1 at night, for a minute of the day and that day's sunrise and sunset in minutes of the day. */
    static float amount(int minute, int sunrise, int sunset) {
        float a;
        if (minute < sunrise || minute >= sunset + 60) a = 1f;
        else if (minute >= sunset - 60) a = (minute - (sunset - 60)) / 120f;
        else if (minute < sunrise + 60) a = 1f - (minute - sunrise) / 60f;
        else a = 0f;
        return Math.round(Math.max(0f, Math.min(1f, a)) * STEPS) / (float) STEPS;
    }

    /** The colour moved {@code amount * MAX} of the way to WARM; alpha kept. amount 0 returns it unchanged. */
    static int shift(int argb, float amount) {
        float t = MAX * Math.max(0f, Math.min(1f, amount));
        if (t == 0f) return argb;
        int r = mix((argb >> 16) & 0xFF, (WARM >> 16) & 0xFF, t);
        int g = mix((argb >> 8) & 0xFF, (WARM >> 8) & 0xFF, t);
        int b = mix(argb & 0xFF, WARM & 0xFF, t);
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    private static int mix(int from, int to, float t) {
        return Math.round(from + (to - from) * t);
    }
}
