package com.gathin.tvhome;

/**
 * clock_weather_scenes and clock_night_mode: what the screensaver's sky shows, as the numbers AuroraView's shaders take.
 * The weather picks the scene (aurora on a clear night, snow, rain, fog or overcast), the time of day against the sun's
 * times sets the light (a cold dark gradient by day, a warm horizon at dawn and dusk, the aurora strongest in the middle
 * of the night), and night mode (23:00 to 06:00) turns the whole picture warm and dim. Ported from
 * work/design-forslag/13-skarmslackare-vader.html. A change of scene fades over 2 s.
 * Pure maths for the JVM tests; thread-safe: ClockScene sets the scene on the main thread, AuroraView reads it on its
 * GL thread.
 */
final class WeatherScene {

    /** The scenes, by weather and daylight. */
    static final int AURORA = 0, CLEAR = 1, SNOW = 2, RAIN = 3, FOG = 4;

    /** Night mode, in minutes of the day: from 23:00 until 06:00. */
    static final int NIGHT_FROM = 23 * 60, NIGHT_TO = 6 * 60;

    /** The sun's times as given, the sun never rising (polar night), or never setting (midnight sun). */
    static final int SUN_NORMAL = 0, POLAR_NIGHT = 1, MIDNIGHT_SUN = 2;

    static final long FADE_MS = 2_000L;

    // Indices into a scene's numbers.
    static final int TOP = 0, HOR = 3, FOG_COL = 6, BREATH = 9, SUN_LO = 10, SUN_HI = 13, SUN_X = 16, AURORA_AMOUNT = 17,
            STARS = 18, SNOW_AMOUNT = 19, RAIN_AMOUNT = 20, FOG_LOW = 21, FOG_DECK = 22, NIGHT = 23, SIZE = 24;

    private static final float[] NIGHT_SKY = {0.004f, 0.006f, 0.012f}, NIGHT_FOG = {0.200f, 0.225f, 0.260f};
    private static final float[] DAY_TOP = {0.008f, 0.016f, 0.036f}, DAY_HOR = {0.060f, 0.085f, 0.115f},
            DAY_FOG = {0.250f, 0.285f, 0.330f};
    private static final float[] DUSK_TOP = {0.008f, 0.014f, 0.048f}, DUSK_HOR = {0.040f, 0.024f, 0.050f},
            DUSK_FOG = {0.300f, 0.210f, 0.200f}, DUSK_LO = {0.30f, 0.105f, 0.030f}, DUSK_HI = {0.075f, 0.028f, 0.060f};
    private static final float NIGHT_BREATH = 0.025f, DAY_BREATH = 0.015f, DUSK_BREATH = 0.010f;

    private final float[] from = new float[SIZE], to = new float[SIZE];
    private long start = -FADE_MS;

    /** Starts on scene, shown at once. */
    WeatherScene(float[] scene) {
        System.arraycopy(scene, 0, from, 0, SIZE);
        System.arraycopy(scene, 0, to, 0, SIZE);
    }

    /** Fade to scene from whatever shows at now; the same scene again changes nothing. */
    synchronized void fadeTo(float[] scene, long now) {
        if (java.util.Arrays.equals(scene, to)) return;
        current(now, from);
        System.arraycopy(scene, 0, to, 0, SIZE);
        start = now;
    }

    /** The scene at now into out. */
    synchronized void current(long now, float[] out) {
        float f = Math.max(0f, Math.min(1f, (now - start) / (float) FADE_MS));
        f = f * f * (3 - 2 * f);
        for (int i = 0; i < SIZE; i++) out[i] = from[i] + (to[i] - from[i]) * f;
        // the sun's side does not slide across the sky: it changes at the half
        if (Math.abs(from[SUN_X] - to[SUN_X]) > 0.5f) out[SUN_X] = f < 0.5f ? from[SUN_X] : to[SUN_X];
    }

    /** The scene for an Open-Meteo WMO weather code (-1: no reading yet) and whether it is day. */
    static int pick(int code, boolean day) {
        switch (code) {
            case 3: case 45: case 48:
                return FOG;   // overcast and fog
            case 51: case 53: case 55: case 56: case 57: case 61: case 63: case 65: case 66: case 67:
            case 80: case 81: case 82: case 95: case 96: case 99:
                return RAIN;
            case 71: case 73: case 75: case 77: case 85: case 86:
                return SNOW;
            default:
                return day ? CLEAR : AURORA;   // clear, mainly clear, partly cloudy, no reading
        }
    }

    /** Whether night mode is on at this minute of the day (0 to 1439). */
    static boolean nightMode(int minute) {
        return minute >= NIGHT_FROM || minute < NIGHT_TO;
    }

    /** Whether it is day at this minute, by the sun's times (minutes of the day) or the polar state. */
    static boolean isDay(int minute, int sunrise, int sunset, int sun) {
        if (sun != SUN_NORMAL) return sun == MIDNIGHT_SUN;
        return minute >= sunrise && minute < sunset;
    }

    /** clock_aurora alone: the plain night sky with the full aurora and stars, as before the weather scenes. */
    static float[] plain(boolean nightMode) {
        float[] s = new float[SIZE];
        put(s, TOP, NIGHT_SKY);
        put(s, HOR, NIGHT_SKY);
        put(s, FOG_COL, NIGHT_FOG);
        s[BREATH] = NIGHT_BREATH;
        s[SUN_X] = 0.42f;
        s[AURORA_AMOUNT] = 1f;
        s[STARS] = 1f;
        s[NIGHT] = nightMode ? 1f : 0f;
        return s;
    }

    /**
     * The numbers for scene at hour (0 to 24, local), with the sun's times in hours and its polar state.
     * Weather: cloud, snow, rain, low fog and overcast deck per scene, as the mockup's table.
     */
    static float[] scene(int scene, double hour, double sunrise, double sunset, int sun, boolean nightMode) {
        float cloud = scene == AURORA || scene == CLEAR ? 0f : 1f;
        float snow = scene == SNOW ? 1f : 0f, rain = scene == RAIN ? 1f : 0f;
        float fog = scene == SNOW ? 0.22f : scene == RAIN ? 0.4f : scene == FOG ? 1f : 0f;
        float deck = scene == SNOW ? 0.5f : scene == RAIN ? 0.6f : scene == FOG ? 1f : 0f;

        double day, dusk, dawn, u;
        if (sun == POLAR_NIGHT) {
            day = 0;
            dusk = dawn = 0;
            u = wrap24(hour - 12) / 24;   // the night's middle at midnight
        } else if (sun == MIDNIGHT_SUN) {
            day = 1;
            dusk = dawn = 0;
            u = 2;   // no night
        } else {
            day = smooth(-0.4, 0.5, Math.min(wrap(hour - sunrise), -wrap(hour - sunset)));
            // dawn and dusk: strongest with the sun a few degrees under the horizon
            dusk = Math.exp(-sq(wrap(hour - sunset - 0.25) / 0.85));
            dawn = Math.exp(-sq(wrap(hour - sunrise + 0.25) / 0.85));
            // how far into the night, 0 at sunset, 1 at sunrise
            u = wrap24(hour - sunset) / (24 - (sunset - sunrise));
        }
        double tw = Math.max(dusk, dawn);
        double night = u <= 1 ? smooth(0.06, 0.28, u) * smooth(0.96, 0.74, u) * (0.55 + 0.45 * Math.sin(Math.PI * u)) : 0;

        float[] s = new float[SIZE];
        float[] top = mix(mix(NIGHT_SKY, DAY_TOP, day), DUSK_TOP, tw * 0.9);
        float[] hor = mix(mix(NIGHT_SKY, DAY_HOR, day), DUSK_HOR, tw * 0.9);
        float[] fogCol = mix(mix(NIGHT_FOG, DAY_FOG, day), DUSK_FOG, tw * 0.8);
        double breath = lerp(lerp(NIGHT_BREATH, DAY_BREATH, day), DUSK_BREATH, tw);
        put(s, TOP, grey(top, cloud));
        put(s, HOR, grey(hor, cloud));
        put(s, FOG_COL, grey(fogCol, cloud));
        s[BREATH] = (float) (breath * (1 - 0.3 * cloud));
        double glow = tw * (1 - 0.55 * cloud);
        for (int i = 0; i < 3; i++) {
            s[SUN_LO + i] = (float) (DUSK_LO[i] * glow);
            s[SUN_HI + i] = (float) (DUSK_HI[i] * glow);
        }
        s[SUN_X] = dusk >= dawn ? 0.42f : -0.42f;
        float clear = 1 - cloud;
        s[AURORA_AMOUNT] = (float) (night * clear);
        s[STARS] = (float) (clear * (1 - day) * (1 - 0.75 * tw) * (0.4 + 0.6 * smooth(0, 0.15, u <= 1 ? u : 0)));
        s[SNOW_AMOUNT] = snow;
        s[RAIN_AMOUNT] = rain;
        s[FOG_LOW] = fog;
        s[FOG_DECK] = deck;
        s[NIGHT] = nightMode ? 1f : 0f;
        return s;
    }

    /** Hours from an event, -12 to 12. */
    private static double wrap(double h) {
        return ((h % 24) + 36) % 24 - 12;
    }

    /** Hours after an event, 0 to 24. */
    private static double wrap24(double h) {
        return ((h % 24) + 24) % 24;
    }

    private static double smooth(double e0, double e1, double x) {
        double t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
        return t * t * (3 - 2 * t);
    }

    private static double sq(double x) {
        return x * x;
    }

    private static double lerp(double a, double b, double f) {
        return a + (b - a) * f;
    }

    private static float[] mix(float[] a, float[] b, double f) {
        return new float[]{(float) lerp(a[0], b[0], f), (float) lerp(a[1], b[1], f), (float) lerp(a[2], b[2], f)};
    }

    /** Clouds: greyer and a little darker. */
    private static float[] grey(float[] c, float cloud) {
        float l = 0.3f * c[0] + 0.55f * c[1] + 0.15f * c[2];
        float[] g = mix(c, new float[]{l * 0.95f, l, l * 1.1f}, 0.6 * cloud);
        for (int i = 0; i < 3; i++) g[i] *= 1 - 0.2f * cloud;
        return g;
    }

    private static void put(float[] s, int at, float[] rgb) {
        System.arraycopy(rgb, 0, s, at, 3);
    }
}
