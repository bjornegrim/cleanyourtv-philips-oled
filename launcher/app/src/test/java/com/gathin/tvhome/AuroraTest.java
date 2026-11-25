package com.gathin.tvhome;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** clock_aurora's colours: the crown partner of each palette colour and the 8 s fade into the shader's uniforms. */
public class AuroraTest {

    private static final int BLUE = 0xFF2B5CFF, TEAL = 0xFF00A39A, VIOLET = 0xFF8A3FFC, ORANGE = 0xFFE0661B, PINK = 0xFFD6336C;

    @Test
    public void eachPaletteColourHasItsPartner() {
        assertEquals(VIOLET, Aurora.crown(BLUE));
        assertEquals(BLUE, Aurora.crown(TEAL));
        assertEquals(PINK, Aurora.crown(VIOLET));
        assertEquals(PINK, Aurora.crown(ORANGE));
        assertEquals(VIOLET, Aurora.crown(PINK));
        assertEquals(0xFF123456, Aurora.crown(0xFF123456));
    }

    @Test
    public void mixIsLinearLightLikeArgbEvaluator() {
        // Expected values worked out separately: ((a^2.2 + f (b^2.2 - a^2.2))^(1/2.2)) per channel, rounded.
        assertEquals(0xFF1F85D4, Aurora.mix(BLUE, TEAL, 0.5f));
        assertEquals(0xFFA13CE2, Aurora.mix(VIOLET, PINK, 0.25f));
        assertEquals(BLUE, Aurora.mix(BLUE, TEAL, 0f));
        assertEquals(TEAL, Aurora.mix(BLUE, TEAL, 1f));
        assertEquals(TEAL, Aurora.mix(BLUE, TEAL, 1.5f));
    }

    @Test
    public void uniformsFollowTheGlowThroughItsFade() {
        Aurora aurora = new Aurora(BLUE);
        assertArrayEquals(rgb(BLUE, VIOLET), colors(aurora, 0), 0f);   // shown at once, even at uptime 0
        aurora.fadeTo(TEAL, 1_000);
        assertArrayEquals(rgb(BLUE, VIOLET), colors(aurora, 1_000), 0f);
        assertArrayEquals(rgb(0xFF1F85D4, Aurora.mix(VIOLET, BLUE, 0.5f)), colors(aurora, 5_000), 0f);
        assertArrayEquals(rgb(TEAL, BLUE), colors(aurora, 9_000), 0f);
        assertArrayEquals(rgb(TEAL, BLUE), colors(aurora, 600_000), 0f);
    }

    @Test
    public void aNewFadeStartsFromWhatShows() {
        Aurora aurora = new Aurora(BLUE);
        aurora.fadeTo(TEAL, 0);
        aurora.fadeTo(VIOLET, 4_000);
        assertArrayEquals(rgb(0xFF1F85D4, Aurora.mix(VIOLET, BLUE, 0.5f)), colors(aurora, 4_000), 0f);
        assertArrayEquals(rgb(VIOLET, PINK), colors(aurora, 12_000), 0f);
    }

    private static float[] colors(Aurora aurora, long now) {
        float[] out = new float[6];
        aurora.colors(now, out);
        return out;
    }

    private static float[] rgb(int base, int crown) {
        return new float[]{((base >> 16) & 0xFF) / 255f, ((base >> 8) & 0xFF) / 255f, (base & 0xFF) / 255f,
                ((crown >> 16) & 0xFF) / 255f, ((crown >> 8) & 0xFF) / 255f, (crown & 0xFF) / 255f};
    }
}
