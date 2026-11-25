package com.gathin.tvhome;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.SystemClock;
import android.util.Log;
import android.view.TextureView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * clock_aurora and clock_weather_scenes: the sky behind the clock screensaver (aurora, snow, rain, fog, the time of
 * day and night mode, see WeatherScene), in the clock glow's colour (Aurora). OpenGL ES 2.0 at 960 x 540, 30 fps.
 * <p>
 * A TextureView with its own EGL thread, not a GLSurfaceView: a SurfaceView is a layer of its own in SurfaceFlinger,
 * and Philips firmware (org.droidtv.tvsystemui) took that layer for video and put its own Bowers & Wilkins logo
 * screensaver over ours after a while. A TextureView is drawn into the window like any view, so there is no layer to
 * mistake. The 960 x 540 picture is stretched to the view by the window's own drawing.
 * <p>
 * The TV's GPU (Mali-G51) manages only a few dozen operations per pixel at 1080p, so the work is split by what it
 * depends on, each pass skipped when it has nothing to draw:
 * 1 aurora_columns   960 x 3     each aurora curtain per screen column
 * 2 weather_sky      960 x 180   the sky for the time of day, and the curtains (soft upwards, so a third of the height)
 * 3 weather_fog      240 x 135   fog density, soft every way
 * 4 weather_compose  960 x 540   sky stretched, fog over it, night mode, the clock's glow, dither
 * 5 stars, 6 snow, 7 rain        point sprites and thin quads added on top
 * <p>
 * Draws only between start() and stop(), and only while the view's texture exists; stop() ends the GL thread, which
 * releases its EGL context and with it every GL resource. Any EGL or GL failure ends the thread too: the view then
 * stays black and ClockScene's fallback (the plain glow) takes over. Nothing here throws on another thread.
 */
@SuppressLint("ViewConstructor")
final class AuroraView extends TextureView implements TextureView.SurfaceTextureListener {

    private static final String TAG = "AuroraView";
    static final int WIDTH = 960, HEIGHT = 540;
    private static final int STARS = 200, FLAKES = 720, DROPS = 350, DITHER = 64;
    /** 30 fps. */
    private static final long FRAME_NS = 33_333_333L;
    private static final String QUAD = "attribute vec2 a; varying vec2 uv;"
            + " void main() { uv = a * 0.5 + 0.5; gl_Position = vec4(a, 0.0, 1.0); }";

    private final Aurora colors;
    private final WeatherScene scene;
    private final Runnable onFailure;
    /** Shader sources by raw resource id (names may be shortened in a release build). */
    private final Map<Integer, String> sources = new HashMap<>();
    private final float[] starData, flakeData, dropData;
    private final byte[] ditherNoise = new byte[DITHER * DITHER];
    /** A different sky each time the screensaver starts. */
    private final float timeOffset = 30f + new Random().nextFloat() * 600f;
    /** The clock's centre in p units (x from -aspect/2, y from -0.5 at the bottom); main thread writes, GL thread reads. */
    private volatile float clockX, clockY;

    private boolean running;   // main thread
    private SurfaceTexture texture;   // main thread
    private Renderer renderer;   // main thread
    /** The thread stop() last ended, until it has: main thread. */
    private Renderer last;

    AuroraView(Context context, int color, float[] firstScene, Runnable onFailure) {
        super(context);
        colors = new Aurora(color);
        scene = new WeatherScene(firstScene);
        this.onFailure = onFailure;
        for (int id : new int[]{R.raw.aurora_columns, R.raw.weather_sky, R.raw.weather_fog, R.raw.weather_compose,
                R.raw.weather_stars_vertex, R.raw.weather_stars_fragment, R.raw.weather_snow_vertex,
                R.raw.weather_snow_fragment, R.raw.weather_rain_vertex, R.raw.weather_rain_fragment}) {
            sources.put(id, read(context, id));
        }
        Random random = new Random();
        starData = new float[STARS * 4];
        for (int i = 0; i < starData.length; i += 4) {
            starData[i] = random.nextFloat();
            starData[i + 1] = random.nextFloat();
            starData[i + 2] = random.nextFloat() * 6.2832f;
            starData[i + 3] = 0.6f + 0.4f * random.nextFloat();
        }
        // three depths: 440 far, 210 middle, 70 near
        flakeData = new float[FLAKES * 4];
        for (int i = 0; i < FLAKES; i++) {
            int layer = i < 440 ? 0 : i < 650 ? 1 : 2;
            flakeData[i * 4] = random.nextFloat();
            flakeData[i * 4 + 1] = random.nextFloat();
            flakeData[i * 4 + 2] = (layer + random.nextFloat()) / 3f;
            flakeData[i * 4 + 3] = random.nextFloat() * 6.2832f;
        }
        // each drop a quad of two triangles, its corners numbered along + 2 * side
        dropData = new float[DROPS * 6 * 4];
        int[] corners = {0, 1, 2, 1, 3, 2};
        for (int i = 0, k = 0; i < DROPS; i++) {
            float x = random.nextFloat(), y = random.nextFloat();
            float z = i < 260 ? random.nextFloat() * 0.5f : 0.5f + random.nextFloat() * 0.5f;
            for (int corner : corners) {
                dropData[k++] = x;
                dropData[k++] = y;
                dropData[k++] = z;
                dropData[k++] = corner;
            }
        }
        random.nextBytes(ditherNoise);
        setOpaque(true);
        setSurfaceTextureListener(this);
    }

    void start() {
        if (running) return;
        running = true;
        startRenderer();
    }

    /** Safe to call twice (ClockDream stops in onDreamingStopped and again on detach). */
    void stop() {
        running = false;
        stopRenderer();
    }

    /** Fade the palette colour to the glow's next one over the glow's 8 s. Main thread. */
    void fadeTo(int color) {
        colors.fadeTo(color, SystemClock.uptimeMillis());
    }

    /** Fade to these WeatherScene numbers over 2 s; the same scene again changes nothing. Main thread. */
    void scene(float[] numbers) {
        scene.fadeTo(numbers, SystemClock.uptimeMillis());
    }

    /** Where the clock's centre is, as fractions of the screen from its top left. Main thread. */
    void clock(float fx, float fy) {
        clockX = (fx - 0.5f) * WIDTH / HEIGHT;
        clockY = 0.5f - fy;
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        texture = surface;
        surface.setDefaultBufferSize(WIDTH, HEIGHT);
        startRenderer();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        // TextureView sizes its buffers to the view on a resize: back to 960 x 540, which the window stretches
        surface.setDefaultBufferSize(WIDTH, HEIGHT);
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        stopRenderer();
        texture = null;
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    }

    private void startRenderer() {
        if (!running || texture == null || renderer != null) return;
        // the last thread still finishing (stop() waits at most 1 s): no second context on the same texture
        if (last != null && last.isAlive()) return;
        renderer = new Renderer(texture);
        renderer.start();
    }

    /** Ends the GL thread and waits for it (a frame takes under 40 ms); main thread. */
    private void stopRenderer() {
        Renderer r = renderer;
        renderer = null;
        if (r == null) return;
        last = r;
        r.quit = true;
        r.interrupt();
        try {
            r.join(1_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** One GL program and its uniforms, looked up once. */
    private static final class Program {
        final int id;
        private final Map<String, Integer> uniforms = new HashMap<>();

        Program(int id) {
            this.id = id;
        }

        int u(String name) {
            Integer at = uniforms.get(name);
            if (at == null) {
                at = GLES20.glGetUniformLocation(id, name);
                uniforms.put(name, at);
            }
            return at;
        }
    }

    /** The GL thread: its own EGL context on the view's texture, 30 frames a second until quit. */
    private final class Renderer extends Thread {
        private final SurfaceTexture surface;
        volatile boolean quit;
        private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
        private EGLContext context = EGL14.EGL_NO_CONTEXT;
        private EGLSurface window = EGL14.EGL_NO_SURFACE;
        private Program columns, sky, fog, compose, stars, snow, rain;
        private int[] columnTarget, skyTarget, fogTarget;
        private FloatBuffer triangle, starBuffer, flakeBuffer, dropBuffer;
        private final float[] rgb = new float[6], now = new float[WeatherScene.SIZE];
        private long nextFrameNs;
        private float drift, lastT;
        private boolean fogUsed;

        Renderer(SurfaceTexture surface) {
            super("AuroraGL");
            this.surface = surface;
        }

        @Override
        public void run() {
            boolean failed = true;
            try {
                if (setUp()) {
                    long startMs = SystemClock.uptimeMillis();
                    lastT = timeOffset;
                    failed = false;
                    while (!quit) {
                        pace();
                        if (quit) break;
                        draw(timeOffset + (SystemClock.uptimeMillis() - startMs) / 1000f);
                        if (!EGL14.eglSwapBuffers(display, window)) {
                            // while stopping the texture may go first; otherwise the plain glow takes over
                            Log.w(TAG, "eglSwapBuffers: " + EGL14.eglGetError());
                            failed = !quit;
                            break;
                        }
                    }
                }
            } catch (Throwable e) {
                // anything, an Error from the driver too: never take the launcher's process down from here
                Log.w(TAG, "GL thread failed: aurora off", e);
                failed = true;
            } finally {
                tearDown();
            }
            if (failed && !quit) post(onFailure);
        }

        /** EGL on the texture, then the programs, targets and buffers. False (logged) where this TV cannot. */
        private boolean setUp() {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] version = new int[2];
            if (display == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(display, version, 0, version, 1)) {
                Log.w(TAG, "No EGL display: aurora off");
                display = EGL14.EGL_NO_DISPLAY;
                return false;
            }
            EGLConfig config = choose(display);
            if (config == null) {
                Log.w(TAG, "No EGL config for OpenGL ES 2.0: aurora off");
                return false;
            }
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            if (context == null || context == EGL14.EGL_NO_CONTEXT) {
                Log.w(TAG, "No OpenGL ES 2.0 context: aurora off");
                context = EGL14.EGL_NO_CONTEXT;
                return false;
            }
            window = EGL14.eglCreateWindowSurface(display, config, surface, new int[]{EGL14.EGL_NONE}, 0);
            if (window == null || window == EGL14.EGL_NO_SURFACE
                    || !EGL14.eglMakeCurrent(display, window, window, context)) {
                Log.w(TAG, "No EGL window surface: aurora off");
                if (window == null) window = EGL14.EGL_NO_SURFACE;
                return false;
            }

            GLES20.glDisable(GLES20.GL_DITHER);   // pass 1 stores numbers, not colours
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE);
            columns = program(0, R.raw.aurora_columns);
            sky = program(0, R.raw.weather_sky);
            fog = program(0, R.raw.weather_fog);
            compose = program(0, R.raw.weather_compose);
            stars = program(R.raw.weather_stars_vertex, R.raw.weather_stars_fragment);
            snow = program(R.raw.weather_snow_vertex, R.raw.weather_snow_fragment);
            rain = program(R.raw.weather_rain_vertex, R.raw.weather_rain_fragment);
            if (columns == null || sky == null || fog == null || compose == null || stars == null || snow == null
                    || rain == null) {
                return false;
            }
            // NEAREST: the curtain edge's two 8-bit halves must not be blended between columns
            columnTarget = target(WIDTH, 3, GLES20.GL_NEAREST);
            skyTarget = target(WIDTH, HEIGHT / 3, GLES20.GL_LINEAR);
            fogTarget = target(WIDTH / 4, HEIGHT / 4, GLES20.GL_LINEAR);
            if (columnTarget == null || skyTarget == null || fogTarget == null) {
                Log.w(TAG, "No render-to-texture: aurora off");
                return false;
            }
            clear(fogTarget);
            // Texture unit 1 holds the dither noise for good, unit 2 the fog; unit 0 takes the pass before.
            int[] id = new int[1];
            GLES20.glGenTextures(1, id, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id[0]);
            parameters(GLES20.GL_NEAREST, GLES20.GL_REPEAT);
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, DITHER, DITHER, 0,
                    GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.wrap(ditherNoise));
            GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fogTarget[0]);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glEnableVertexAttribArray(0);

            triangle = floats(new float[]{-1f, -1f, 3f, -1f, -1f, 3f});
            starBuffer = floats(starData);
            flakeBuffer = floats(flakeData);
            dropBuffer = floats(dropData);

            // What holds for good: each program keeps its own uniforms.
            float aspect = WIDTH / (float) HEIGHT;
            Object[][] sizes = {{columns, WIDTH, HEIGHT}, {sky, WIDTH, HEIGHT / 3}, {fog, WIDTH / 4, HEIGHT / 4},
                    {compose, WIDTH, HEIGHT}, {stars, WIDTH, HEIGHT}, {snow, WIDTH, HEIGHT}, {rain, WIDTH, HEIGHT}};
            for (Object[] s : sizes) {
                Program p = (Program) s[0];
                GLES20.glUseProgram(p.id);
                GLES20.glUniform2f(p.u("res"), (Integer) s[1], (Integer) s[2]);
                GLES20.glUniform1f(p.u("aspect"), aspect);
            }
            GLES20.glUseProgram(sky.id);
            GLES20.glUniform1i(sky.u("cols"), 0);
            GLES20.glUniform1i(sky.u("dither"), 1);
            GLES20.glUseProgram(compose.id);
            GLES20.glUniform1i(compose.u("sky"), 0);
            GLES20.glUniform1i(compose.u("dither"), 1);
            GLES20.glUniform1i(compose.u("fog"), 2);
            GLES20.glUniform1f(compose.u("glowR"), 0.55f * aspect);
            return GLES20.glGetError() == GLES20.GL_NO_ERROR;
        }

        private void draw(float t) {
            long ms = SystemClock.uptimeMillis();
            colors.colors(ms, rgb);
            scene.current(ms, now);
            float dt = Math.min(0.2f, t - lastT);
            lastT = t;
            // wind: a slow breeze that rises and falls over minutes, summed so the flakes never jump
            drift += dt * (0.006f + 0.004f * (float) Math.sin(t * 0.05) + 0.002f * (float) Math.sin(t * 0.13 + 1));
            float slant = 0.2f + 0.04f * (float) Math.sin(t * 0.06);
            float cx = clockX, cy = clockY, night = now[WeatherScene.NIGHT];
            float aurora = now[WeatherScene.AURORA_AMOUNT];

            // 1: each curtain's edge, rays and lean per column, one row each; only while the aurora shows
            if (aurora > 0.004f) {
                pass(columnTarget, WIDTH, 3, columns);
                GLES20.glUniform1f(columns.u("t"), t);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3);
            }

            // 2: the sky for the time of day, and the curtains
            pass(skyTarget, WIDTH, HEIGHT / 3, sky);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, columnTarget[0]);
            GLES20.glUniform1f(sky.u("pulse"), (float) ((t * 0.3) % (2 * Math.PI)));
            GLES20.glUniform3fv(sky.u("base"), 1, rgb, 0);
            GLES20.glUniform3fv(sky.u("crown"), 1, rgb, 3);
            GLES20.glUniform3fv(sky.u("top"), 1, now, WeatherScene.TOP);
            GLES20.glUniform3fv(sky.u("hor"), 1, now, WeatherScene.HOR);
            GLES20.glUniform3fv(sky.u("sunLo"), 1, now, WeatherScene.SUN_LO);
            GLES20.glUniform3fv(sky.u("sunHi"), 1, now, WeatherScene.SUN_HI);
            GLES20.glUniform1f(sky.u("sunX"), now[WeatherScene.SUN_X]);
            GLES20.glUniform1f(sky.u("breath"), now[WeatherScene.BREATH]);
            GLES20.glUniform1f(sky.u("aurora"), aurora > 0.004f ? aurora : 0f);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3);

            // 3: fog, only where there is some; cleared once when it goes, so pass 4 reads nothing
            float low = now[WeatherScene.FOG_LOW], deck = now[WeatherScene.FOG_DECK];
            boolean fogShows = low + deck > 0.004f;
            if (fogShows) {
                pass(fogTarget, WIDTH / 4, HEIGHT / 4, fog);
                GLES20.glUniform1f(fog.u("t"), t);
                GLES20.glUniform1f(fog.u("low"), low);
                GLES20.glUniform1f(fog.u("deck"), deck);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3);
            } else if (fogUsed) {
                clear(fogTarget);
            }
            fogUsed = fogShows;

            // 4: on the screen: sky, fog, night mode, the clock's glow
            pass(null, WIDTH, HEIGHT, compose);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, skyTarget[0]);
            GLES20.glUniform3f(compose.u("fogCol"), mixed(now[WeatherScene.FOG_COL], rgb[0]),
                    mixed(now[WeatherScene.FOG_COL + 1], rgb[1]), mixed(now[WeatherScene.FOG_COL + 2], rgb[2]));
            GLES20.glUniform3f(compose.u("fogLit"), rgb[0] * 0.22f, rgb[1] * 0.22f, rgb[2] * 0.22f);
            GLES20.glUniform2f(compose.u("clock"), cx, cy);
            GLES20.glUniform1f(compose.u("night"), night);
            GLES20.glUniform3fv(compose.u("glow"), 1, rgb, 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3);

            // 5 to 7: added on top
            GLES20.glEnable(GLES20.GL_BLEND);
            float tr = 0.78f + (rgb[0] - 0.78f) * 0.12f, tg = 0.84f + (rgb[1] - 0.84f) * 0.12f,
                    tb = 0.95f + (rgb[2] - 0.95f) * 0.12f;
            float starAmount = now[WeatherScene.STARS];
            if (starAmount > 0.004f) {
                GLES20.glUseProgram(stars.id);
                GLES20.glVertexAttribPointer(0, 4, GLES20.GL_FLOAT, false, 0, starBuffer);
                GLES20.glUniform1f(stars.u("t"), t);
                GLES20.glUniform1f(stars.u("amount"), starAmount);
                GLES20.glUniform1f(stars.u("night"), night);
                GLES20.glDrawArrays(GLES20.GL_POINTS, 0, STARS);
            }
            float snowAmount = now[WeatherScene.SNOW_AMOUNT];
            if (snowAmount > 0.004f) {
                GLES20.glUseProgram(snow.id);
                GLES20.glVertexAttribPointer(0, 4, GLES20.GL_FLOAT, false, 0, flakeBuffer);
                GLES20.glUniform1f(snow.u("t"), t);
                GLES20.glUniform1f(snow.u("drift"), drift);
                GLES20.glUniform1f(snow.u("amount"), snowAmount);
                GLES20.glUniform2f(snow.u("clock"), cx, cy);
                GLES20.glUniform3f(snow.u("tint"), tr, tg, tb);
                GLES20.glUniform3fv(snow.u("lit"), 1, rgb, 0);
                GLES20.glUniform1f(snow.u("night"), night);
                GLES20.glDrawArrays(GLES20.GL_POINTS, 0, FLAKES);
            }
            float rainAmount = now[WeatherScene.RAIN_AMOUNT];
            if (rainAmount > 0.004f) {
                GLES20.glUseProgram(rain.id);
                GLES20.glVertexAttribPointer(0, 4, GLES20.GL_FLOAT, false, 0, dropBuffer);
                GLES20.glUniform1f(rain.u("t"), t);
                GLES20.glUniform1f(rain.u("slant"), slant);
                GLES20.glUniform1f(rain.u("amount"), rainAmount);
                GLES20.glUniform2f(rain.u("clock"), cx, cy);
                GLES20.glUniform3f(rain.u("tint"), tr, tg, tb);
                GLES20.glUniform3fv(rain.u("lit"), 1, rgb, 0);
                GLES20.glUniform1f(rain.u("night"), night);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, DROPS * 6);
            }
            GLES20.glDisable(GLES20.GL_BLEND);
        }

        /** The fog's colour with a quarter of the palette's, faintly. */
        private float mixed(float fogChannel, float palette) {
            return fogChannel + (palette * 0.05f - fogChannel) * 0.25f;
        }

        /** Bind a target (null: the screen), clear it so a tiler loads nothing, and set up a full-screen pass. */
        private void pass(int[] target, int w, int h, Program p) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target == null ? 0 : target[1]);
            GLES20.glViewport(0, 0, w, h);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(p.id);
            GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, triangle);
        }

        private void clear(int[] target) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target[1]);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        }

        /** Hold to one frame per FRAME_NS; after a late frame the count starts again rather than rushing. */
        private void pace() {
            long t = System.nanoTime();
            if (nextFrameNs - t > 0) {
                try {
                    Thread.sleep((nextFrameNs - t) / 1_000_000L);
                } catch (InterruptedException e) {
                    return;   // stop() wakes us; the loop sees quit
                }
                t = nextFrameNs;
            }
            nextFrameNs = (t - nextFrameNs > FRAME_NS ? t : nextFrameNs) + FRAME_NS;
        }

        /** Everything EGL made, in any state setUp() left it. */
        private void tearDown() {
            try {
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                    if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window);
                    // the context takes its programs, textures and framebuffers with it
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
                    // the display is not terminated: the window's own drawing (HWUI) shares it
                }
                EGL14.eglReleaseThread();
            } catch (RuntimeException e) {
                Log.w(TAG, "EGL tear-down", e);
            }
        }

        /** A linked program from two raw shaders (vertex 0: the full-screen quad), or null (logged). */
        private Program program(int vertex, int fragment) {
            int vs = shader(GLES20.GL_VERTEX_SHADER, vertex == 0 ? QUAD : sources.get(vertex));
            int fs = shader(GLES20.GL_FRAGMENT_SHADER, sources.get(fragment));
            if (vs == 0 || fs == 0) return null;
            int p = GLES20.glCreateProgram();
            GLES20.glAttachShader(p, vs);
            GLES20.glAttachShader(p, fs);
            for (String attribute : new String[]{"a", "star", "flake", "drop"}) {
                GLES20.glBindAttribLocation(p, 0, attribute);   // each program has one of them
            }
            GLES20.glLinkProgram(p);
            GLES20.glDeleteShader(vs);
            GLES20.glDeleteShader(fs);
            int[] ok = new int[1];
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
            if (ok[0] != 0) return new Program(p);
            Log.w(TAG, "Link failed: " + GLES20.glGetProgramInfoLog(p));
            GLES20.glDeleteProgram(p);
            return null;
        }
    }

    /** An ES 2.0 window config: RGB888 without alpha or depth if there is one, else any 8-bit RGB, else the first (565). */
    private static EGLConfig choose(EGLDisplay display) {
        int[] spec = {EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE};
        int[] count = new int[1];
        EGLConfig[] configs = new EGLConfig[32];
        if (!EGL14.eglChooseConfig(display, spec, 0, configs, 0, configs.length, count, 0) || count[0] <= 0) return null;
        EGLConfig rgb = null;
        for (int i = 0; i < Math.min(count[0], configs.length); i++) {
            EGLConfig c = configs[i];
            if (c == null) continue;
            boolean rgb888 = value(display, c, EGL14.EGL_RED_SIZE) == 8 && value(display, c, EGL14.EGL_GREEN_SIZE) == 8
                    && value(display, c, EGL14.EGL_BLUE_SIZE) == 8;
            if (!rgb888) continue;
            if (value(display, c, EGL14.EGL_ALPHA_SIZE) == 0 && value(display, c, EGL14.EGL_DEPTH_SIZE) == 0) return c;
            if (rgb == null) rgb = c;
        }
        return rgb != null ? rgb : configs[0];
    }

    private static int value(EGLDisplay display, EGLConfig config, int attribute) {
        int[] v = new int[1];
        return EGL14.eglGetConfigAttrib(display, config, attribute, v, 0) ? v[0] : -1;
    }

    /** {texture, framebuffer} of w x h RGBA, or null if this GPU cannot render to it. */
    private static int[] target(int w, int h, int filter) {
        int[] id = new int[2];
        GLES20.glGenTextures(1, id, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id[0]);
        parameters(filter, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glGenFramebuffers(1, id, 1);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, id[1]);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, id[0], 0);
        boolean ok = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE;
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        return ok ? id : null;   // a half-made target goes with the context
    }

    private static void parameters(int filter, int wrap) {
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, wrap);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, wrap);
    }

    private static int shader(int type, String source) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, source == null ? "" : source);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] != 0) return s;
        Log.w(TAG, "Compile failed: " + GLES20.glGetShaderInfoLog(s));
        GLES20.glDeleteShader(s);
        return 0;
    }

    private static FloatBuffer floats(float[] values) {
        FloatBuffer buffer = ByteBuffer.allocateDirect(values.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        buffer.put(values).position(0);
        return buffer;
    }

    private static String read(Context context, int id) {
        try (InputStream in = context.getResources().openRawResource(id)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";   // fails to compile, so the aurora stays off
        }
    }
}
