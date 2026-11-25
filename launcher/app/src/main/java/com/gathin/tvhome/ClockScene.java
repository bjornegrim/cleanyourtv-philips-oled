package com.gathin.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The clock screensaver's scene (layout dream_clock): the thin clock and date on black over a slow ambient
 * glow. Every minute the clock drifts to a new spot and the glow shifts colour, so nothing burns in; a
 * ClockRefresh keeps its TextClocks current where Android would not.
 * Shared by ClockDream (the system screensaver) and ClockActivity (Home left idle).
 */
final class ClockScene {

    private static final long MOVE_MS = 60_000L;
    /** The glow's colour change: 16 steps over 8 s. Animated per frame it redrew the full-screen gradient ~480 times. */
    private static final int GLOW_STEPS = 16;
    private static final long GLOW_STEP_MS = 500L;
    /** Deep, low-brightness glow colours; the glow is drawn at partial alpha on black. */
    private static final int[] PALETTE = {0xFF2B5CFF, 0xFF00A39A, 0xFF8A3FFC, 0xFFE0661B, 0xFFD6336C};
    /** clock_night_mode: warm red at low light, nothing that lights up the room. */
    private static final int NIGHT_RED = 0xFFB0402A;
    /** Night colours of the time, AM/PM, date and weather row, as the mockup (13-skarmslackare-vader). */
    private static final int[] NIGHT_TEXT = {0xFFB0402A, 0x99B0402A, 0xFF8A3322, 0xFF7A2D1F};
    private static final ArgbEvaluator NIGHT_MIX = new ArgbEvaluator();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final View content;
    private final ClockRefresh clockRefresh;
    private final ImageView ambient;
    private final GradientDrawable glow;
    private int colorIndex;
    private int glowColor;
    /** clock_wallpaper: a picture replaced the coloured glow. */
    private boolean wallpaperShown;
    /** clock_glow false: plain black behind the clock, whether or not a picture shows. */
    private boolean glowOff;
    /** clock_aurora: the sky behind the clock (AuroraView), only while the glow shows on black; it draws the glow too. */
    private AuroraView aurora;
    /** clock_weather_scenes: the sky follows the weather and the time of day, not only the plain aurora night. */
    private boolean weatherScenes;
    /** clock_night_mode: from 23:00 to 06:00 the clock turns warm red and the sky dim (WeatherScene.nightMode). */
    private boolean nightModeOn;
    /** Night mode's share now, 0 to 1, fading over 2 s; the clock's text views and their own colours. */
    private float night;
    private ValueAnimator nightFade;
    private final List<TextView> texts = new ArrayList<>();
    private final List<Integer> textColors = new ArrayList<>();
    private String sunLatitude = "", sunLongitude = "";
    /** clock_wallpaper: the picture start() decodes, its darkening, and the resources to draw it with. */
    private File wallpaperFile;
    private int wallpaperShade;
    private Resources wallpaperRes;
    /** One number per decode request: a result whose number is no longer the current one is dropped. Main thread only. */
    private int wallpaperGeneration;
    /** clock_wallpaper with a collection: the pictures, how long each lasts, and how the next one is picked. */
    private List<File> rotation;
    private long rotationMs;
    private long shownMs;
    private String order;
    private Map<String, Set<String>> moods;
    private String latitude, longitude;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            moveClock();
            shiftGlow();
            advanceWallpaper();
            updateNight(true);
            // Weather.code() and isDay() only move on a fetch; bind fetches when its reading is 20 minutes old
            Weather.bind(content.findViewById(R.id.dreamWeather));
            if (aurora != null) aurora.scene(sceneNow());
            handler.postDelayed(this, MOVE_MS);
        }
    };

    /** root is the inflated dream_clock layout. */
    ClockScene(View root) {
        content = root.findViewById(R.id.dreamContent);
        clockRefresh = new ClockRefresh(content);
        ambient = root.findViewById(R.id.dreamAmbient);
        glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ambient.setBackground(glow);
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                glow.setGradientRadius((r - l) * 0.55f));
        colorIndex = random.nextInt(PALETTE.length);
        setGlow(PALETTE[colorIndex]);
    }

    void start() {
        content.setAlpha(0f);
        content.post(() -> {
            placeClock();
            content.animate().alpha(1f).setDuration(1200).start();
        });
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, MOVE_MS);
        clockRefresh.start();
        updateNight(false);
        if (aurora != null) {
            aurora.scene(sceneNow());
            aurora.start();
        }
        if (wallpaperFile != null && !wallpaperShown) loadWallpaper();
    }

    void stop() {
        handler.removeCallbacksAndMessages(null);   // the tick and any glow steps still queued
        clockRefresh.stop();
        if (aurora != null) aurora.stop();
        if (nightFade != null) nightFade.cancel();
        content.animate().cancel();
        wallpaperGeneration++;   // a decode still running must not attach its picture to a stopped scene
    }

    /**
     * clock_wallpaper: put the picture Home is showing behind the clock, darkened by clock_wallpaper_dim, in
     * place of the glow, and carry a collection's rotation on while the screensaver is up. Decoded off the main
     * thread at screen size in RGB_565 (4 MB at 1080p), like Home's own; until then, or if it cannot be read,
     * the glow stays, unless clock_glow is false.
     * Call before start(), which starts the decode (again after a stop() that dropped an unfinished one).
     */
    void useHomeWallpaper(Context ctx) {
        Config cfg;
        try {
            cfg = Config.load(ctx);
        } catch (RuntimeException e) {
            return;
        }
        // clock_glow false: plain black behind the clock (OLED); the clock itself still moves every minute.
        glowOff = !cfg.clockGlow;
        if (glowOff) ambient.setBackground(null);
        nightModeOn = cfg.clockNightMode;
        weatherScenes = cfg.clockWeatherScenes;
        sunLatitude = ctx.getString(R.string.latitude);
        sunLongitude = ctx.getString(R.string.longitude);
        // clock_aurora: only where the glow shows on black, so not under a clock_wallpaper picture.
        if (cfg.clockAurora && !glowOff && aurora == null && (!cfg.clockWallpaper || cfg.wallpapers.isEmpty())) {
            aurora = new AuroraView(ctx, glowColor, sceneNow(), this::auroraFailed);
            ((ViewGroup) ambient.getParent()).addView(aurora, 0,
                    new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            // the sky draws the glow itself: a full-screen gradient redrawn with every frame cost more
            ambient.setBackground(null);
        }
        if (!cfg.clockWallpaper) return;
        // What Home plays, and Config puts the single wallpaper in it when a theme has no collection.
        // Reading cfg.wallpaper first held the screensaver on wallpaper.jpg while Home rotated.
        if (cfg.wallpapers.isEmpty()) {
            wallpaperFile = null;
        } else {
            // Start on the picture Home is showing. WallpaperPlayer records it under the same
            // preferences and the screensaver runs in the same process, so this is just a read.
            rotation = new ArrayList<>(cfg.wallpapers);
            String shown = ctx.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE)
                    .getString("image", "");
            File current = WallpaperRotation.find(rotation, shown);
            wallpaperFile = current != null ? current : rotation.get(0);
            // Home only rotates while it is visible, so the screensaver carries the rotation on
            // itself; without this it holds one picture for as long as it is up.
            rotationMs = cfg.wallpaperRotationMinutes * 60_000L;
            order = cfg.wallpaperOrder;
            moods = cfg.wallpaperMoods;
            latitude = moods.isEmpty() ? "" : ctx.getString(R.string.latitude);
            longitude = moods.isEmpty() ? "" : ctx.getString(R.string.longitude);
        }
        wallpaperShade = (cfg.clockWallpaperDim * 255 / 100) << 24;
        wallpaperRes = ctx.getResources();
    }

    private void loadWallpaper() {
        File file = wallpaperFile;
        int shade = wallpaperShade;
        Resources res = wallpaperRes;
        DisplayMetrics dm = res.getDisplayMetrics();
        int w = dm.widthPixels, h = dm.heightPixels;
        int generation = ++wallpaperGeneration;
        new Thread(() -> {
            Bitmap bmp = decode(file, w, h);
            if (bmp == null) return;
            handler.post(() -> {
                if (generation != wallpaperGeneration || wallpaperShown) {
                    bmp.recycle();   // stopped meanwhile (or already shown): this picture was never attached
                    return;
                }
                wallpaperShown = true;
                // As the image, not the background: a background is stretched to the view, so a picture of
                // another shape than the screen would be distorted. centerCrop scales and crops it like Home.
                ambient.setBackground(null);
                ambient.setImageDrawable(new LayerDrawable(new Drawable[]{
                        new BitmapDrawable(res, bmp), new ColorDrawable(shade)}));
            });
        }, "clock-wallpaper").start();
    }

    /** WallpaperPlayer's decode: sampled and scaled down to cover the screen, never bigger than 4K. */
    private static Bitmap decode(File file, int screenW, int screenH) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            int width = options.outWidth, height = options.outHeight;
            if (width <= 0 || height <= 0) return null;
            int sample = WallpaperSize.sampleSize(width, height, screenW, screenH);
            if (!WallpaperSize.accepted(width / sample, height / sample)) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(width / sample, height / sample, screenW, screenH);
            if (scale != null) {
                options.inScaled = true;
                options.inDensity = scale[0];
                options.inTargetDensity = scale[1];
            }
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            // sRGB at decode time, as WallpaperPlayer: a colour-profiled photo would be converted every frame.
            options.inPreferredColorSpace = android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB);
            return BitmapFactory.decodeFile(file.getPath(), options);
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }

    /** Step to the next picture once its turn is up; the tick already runs every minute. */
    private void advanceWallpaper() {
        if (rotation == null || rotationMs <= 0 || rotation.size() < 2) return;
        shownMs += MOVE_MS;
        if (shownMs < rotationMs) return;
        shownMs = 0;
        // The same pick as Home's, so a shuffled collection stays shuffled and wallpaper_moods still
        // decides what may show at this hour.
        File next = WallpaperRotation.pick(playable(), wallpaperFile.getName(), order, random);
        if (next.equals(wallpaperFile)) return;   // a mood group of one
        wallpaperFile = next;
        wallpaperShown = false;
        loadWallpaper();
    }

    /** WallpaperPlayer's filter: the pictures wallpaper_moods allows at this hour, or all of them. */
    private List<File> playable() {
        if (moods.isEmpty()) return rotation;
        LocalDateTime now = LocalDateTime.now();
        int[] sun = WallpaperMood.sun(now.toLocalDate(), latitude, longitude, ZoneId.systemDefault());
        return WallpaperMood.select(rotation, moods,
                WallpaperMood.timeOfDay(now.getHour() * 60 + now.getMinute(), sun[0], sun[1]));
    }

    private void moveClock() {
        content.animate().alpha(0f).setDuration(900).withLayer().withEndAction(() -> {
            placeClock();
            content.animate().alpha(1f).setDuration(1200).withLayer().start();
        }).start();
    }

    /** A random spot that keeps the whole clock on screen, with the glow centred behind it. */
    private void placeClock() {
        View parent = (View) content.getParent();
        int maxX = Math.max(0, parent.getWidth() - content.getWidth());
        int maxY = Math.max(0, parent.getHeight() - content.getHeight());
        float x = maxX * (0.1f + 0.8f * random.nextFloat());
        float y = maxY * (0.1f + 0.8f * random.nextFloat());
        content.setX(x);
        content.setY(y);
        if (parent.getWidth() > 0 && parent.getHeight() > 0) {
            float fx = (x + content.getWidth() / 2f) / parent.getWidth(), fy = (y + content.getHeight() / 2f) / parent.getHeight();
            glow.setGradientCenter(fx, fy);
            if (aurora != null) aurora.clock(fx, fy);
        }
    }

    private void shiftGlow() {
        if (wallpaperShown || glowOff) return;
        int from = glowColor;
        colorIndex = (colorIndex + 1) % PALETTE.length;
        int to = PALETTE[colorIndex];
        if (aurora != null) aurora.fadeTo(to);
        ArgbEvaluator mix = new ArgbEvaluator();
        for (int i = 1; i <= GLOW_STEPS; i++) {
            float f = (float) i / GLOW_STEPS;
            handler.postDelayed(() -> setGlow((int) mix.evaluate(f, from, to)), i * GLOW_STEP_MS);
        }
    }

    private void setGlow(int color) {
        glowColor = color;
        // night mode: warm red, at a fifth of the strength
        int c = (int) NIGHT_MIX.evaluate(night, color, NIGHT_RED) & 0x00FFFFFF;
        float k = 1f - 0.78f * night;
        glow.setColors(new int[]{Math.round(0x40 * k) << 24 | c, Math.round(0x14 * k) << 24 | c, c});
    }

    /** The sky (AuroraView) could not draw on this TV: back to the plain glow, as without clock_aurora. */
    private void auroraFailed() {
        if (aurora == null) return;
        aurora.stop();
        ((ViewGroup) aurora.getParent()).removeView(aurora);
        aurora = null;
        if (!glowOff && !wallpaperShown) ambient.setBackground(glow);
    }

    /** The weather scene for now: the weather, the time against the sun, and night mode (WeatherScene). */
    private float[] sceneNow() {
        LocalDateTime now = LocalDateTime.now();
        int minute = now.getHour() * 60 + now.getMinute();
        boolean nightNow = nightModeOn && WeatherScene.nightMode(minute);
        if (!weatherScenes) return WeatherScene.plain(nightNow);
        int[] times = WallpaperMood.sun(now.toLocalDate(), sunLatitude, sunLongitude, ZoneId.systemDefault());
        int sun = WeatherScene.SUN_NORMAL;
        if (polar(now.toLocalDate())) {
            // Sun has no rise or set today; Open-Meteo's is_day tells which, or else the season does
            boolean summer = (latitude() >= 0) == (now.getMonthValue() >= 4 && now.getMonthValue() <= 9);
            sun = (Weather.code() >= 0 ? Weather.isDay() : summer) ? WeatherScene.MIDNIGHT_SUN : WeatherScene.POLAR_NIGHT;
        }
        boolean day = Weather.code() >= 0 ? Weather.isDay() : WeatherScene.isDay(minute, times[0], times[1], sun);
        double hour = minute / 60.0 + now.getSecond() / 3600.0;
        return WeatherScene.scene(WeatherScene.pick(Weather.code(), day), hour, times[0] / 60.0, times[1] / 60.0, sun,
                nightNow);
    }

    /** Whether the configured place has no sunrise or sunset today; false when there is no place (WallpaperMood's times). */
    private boolean polar(LocalDate date) {
        try {
            if (sunLatitude.trim().isEmpty() || sunLongitude.trim().isEmpty()) return false;
            return Sun.riseAndSet(date, latitude(), Double.parseDouble(sunLongitude.trim()), ZoneId.systemDefault()) == null;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private double latitude() {
        try {
            return Double.parseDouble(sunLatitude.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * clock_night_mode: the clock, the date and the weather row warm red from 23:00 to 06:00, the glow too, fading over
     * 2 s (at once when the screensaver starts). The sky dims itself from WeatherScene's night number.
     */
    private void updateNight(boolean fade) {
        if (!nightModeOn) return;
        if (texts.isEmpty()) collectTexts(content);
        LocalDateTime now = LocalDateTime.now();
        float target = WeatherScene.nightMode(now.getHour() * 60 + now.getMinute()) ? 1f : 0f;
        if (nightFade != null) nightFade.cancel();
        if (!fade || target == night) {
            applyNight(target);
            return;
        }
        nightFade = ValueAnimator.ofFloat(night, target);
        nightFade.setDuration(WeatherScene.FADE_MS);
        nightFade.addUpdateListener(a -> applyNight((float) a.getAnimatedValue()));
        nightFade.start();
    }

    private void applyNight(float amount) {
        night = amount;
        for (int i = 0; i < texts.size(); i++) {
            int warm = NIGHT_TEXT[Math.min(i, NIGHT_TEXT.length - 1)];
            int color = (int) NIGHT_MIX.evaluate(amount, textColors.get(i), warm);
            TextView v = texts.get(i);
            v.setTextColor(color);
            // the weather row's icon is tinted in the text's colour (Weather.show)
            if (v.getCompoundDrawablesRelative()[0] != null) v.setCompoundDrawableTintList(ColorStateList.valueOf(color));
        }
        if (!wallpaperShown && !glowOff) setGlow(glowColor);
    }

    /** The clock's text views in layout order (time, AM/PM, date, weather) and the colours the layout gave them. */
    private void collectTexts(View v) {
        if (v instanceof TextView) {
            texts.add((TextView) v);
            textColors.add(((TextView) v).getCurrentTextColor());
        } else if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectTexts(g.getChildAt(i));
        }
    }
}
