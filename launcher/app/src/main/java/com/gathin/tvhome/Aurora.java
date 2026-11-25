package com.gathin.tvhome;

/**
 * clock_aurora's colours, kept apart from the GL code so they can be tested on the JVM. The curtains' foot takes the
 * clock glow's colour and their crown a partner colour, as a real aurora turns from green to violet higher up; both
 * fade over the same 8 s as ClockScene's glow, in linear light like the glow's ArgbEvaluator.
 * Thread-safe: ClockScene sets the colour on the main thread, AuroraView reads it on its GL thread.
 */
final class Aurora {

    /** ClockScene's GLOW_STEPS x GLOW_STEP_MS. */
    static final long FADE_MS = 8_000L;

    private int fromBase, toBase, fromCrown, toCrown;
    /** When the current fade began; -FADE_MS makes the first colour show at once (uptime starts at 0). */
    private long start = -FADE_MS;

    Aurora(int color) {
        fromBase = toBase = color;
        fromCrown = toCrown = crown(color);
    }

    /** Start a fade to color from whatever shows at now, even halfway through the previous fade. */
    synchronized void fadeTo(int color, long now) {
        float f = fraction(now);
        fromBase = mix(fromBase, toBase, f);
        fromCrown = mix(fromCrown, toCrown, f);
        toBase = color;
        toCrown = crown(color);
        start = now;
    }

    /** The colours at now as {base r, g, b, crown r, g, b}, 0 to 1, into out. */
    synchronized void colors(long now, float[] out) {
        float f = fraction(now);
        rgb(mix(fromBase, toBase, f), out, 0);
        rgb(mix(fromCrown, toCrown, f), out, 3);
    }

    private float fraction(long now) {
        return Math.max(0f, Math.min(1f, (now - start) / (float) FADE_MS));
    }

    /** The crown's partner for each of ClockScene's palette colours; any other colour crowns itself. */
    static int crown(int color) {
        switch (color) {
            case 0xFF2B5CFF: return 0xFF8A3FFC;   // blue to violet
            case 0xFF00A39A: return 0xFF2B5CFF;   // teal to blue
            case 0xFF8A3FFC: return 0xFFD6336C;   // violet to pink
            case 0xFFE0661B: return 0xFFD6336C;   // orange to pink
            case 0xFFD6336C: return 0xFF8A3FFC;   // pink to violet
            default: return color;
        }
    }

    /** ArgbEvaluator's blend since API 26 (linear light, gamma 2.2), so the curtains match the glow step for step. */
    static int mix(int from, int to, float f) {
        if (f <= 0f) return from;
        if (f >= 1f) return to;
        int c = Math.round(((from >>> 24) + f * ((to >>> 24) - (from >>> 24))));
        for (int shift = 16; shift >= 0; shift -= 8) {
            double a = Math.pow(((from >> shift) & 0xFF) / 255.0, 2.2);
            double b = Math.pow(((to >> shift) & 0xFF) / 255.0, 2.2);
            c = (c << 8) | (int) Math.round(Math.pow(a + f * (b - a), 1.0 / 2.2) * 255.0);
        }
        return c;
    }

    private static void rgb(int color, float[] out, int offset) {
        out[offset] = ((color >> 16) & 0xFF) / 255f;
        out[offset + 1] = ((color >> 8) & 0xFF) / 255f;
        out[offset + 2] = (color & 0xFF) / 255f;
    }
}
