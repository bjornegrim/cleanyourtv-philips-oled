package com.gathin.tvhome;

import android.annotation.SuppressLint;
import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.SystemClock;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.opengles.GL10;

/**
 * clock_aurora: northern lights behind the clock screensaver, in the clock glow's colour (Aurora). OpenGL ES 2.0 at
 * 960 x 540, scaled up by the compositor, at 15 fps (FRAME_NS). A SurfaceView, so it sits under the window: the glow and the clock
 * draw over it as before.
 * The TV's GPU (Mali-G51) manages only a few dozen operations per pixel at this size, so the work is split by what it
 * depends on: aurora_columns.glsl works out each curtain per screen column (960 x 3), aurora_sky.glsl draws the curtains
 * and the night sky at a third of the height (960 x 180: they are soft upwards, sharp only sideways), one cheap pass
 * stretches that to 960 x 540 with dither, and the stars are 200 point sprites on top.
 * Renders only between start() and stop(); stop() releases the EGL context, and with it every GL resource, and the GL
 * thread ends when the screensaver's window goes.
 */
@SuppressLint("ViewConstructor")
final class AuroraView extends GLSurfaceView implements GLSurfaceView.Renderer {

    private static final String TAG = "AuroraView";
    private static final int WIDTH = 960, HEIGHT = 540, STARS = 200, DITHER = 64;
    /**
     * 15 fps: nothing here moves more than about 2 screen pixels a frame, on soft shapes, so it looks the same as 30
     * and costs half the CPU (about 10 % of a core at 30 fps on the TV, mostly the GPU driver).
     */
    private static final long FRAME_NS = 66_666_667L;
    private static final String VERTEX = "attribute vec2 a; varying vec2 uv;"
            + " void main() { uv = a * 0.5 + 0.5; gl_Position = vec4(a, 0.0, 1.0); }";
    /** Pass 3: the sky stretched to full height, dithered so its near-black gradients do not band. */
    private static final String STRETCH = """
            precision mediump float;
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            varying highp vec2 uv;
            #else
            varying mediump vec2 uv;
            #endif
            uniform sampler2D sky;
            uniform sampler2D dither;
            void main() {
              vec3 c = texture2D(sky, uv).rgb + (texture2D(dither, gl_FragCoord.xy / 64.0).r - 0.5) / 255.0;
              gl_FragColor = vec4(c, 1.0);
            }
            """;
    /** Pass 4: faint stars that drift slowly sideways, so no pixel stays lit, and twinkle over seconds. */
    private static final String STAR_VERTEX = """
            attribute vec4 star;   // x and y from 0 to 1, twinkle phase, brightness
            uniform vec2 res;
            uniform float t;
            varying float glow;
            void main() {
              float w = res.x + 8.0;
              vec2 q = vec2(mod(star.x * w + t * 1.2, w) - 4.0, star.y * res.y);
              vec2 p = (q - 0.5 * res) / res.y;
              gl_Position = vec4(q / res * 2.0 - 1.0, 0.0, 1.0);
              gl_PointSize = 4.0;
              glow = star.w * (0.5 + 0.5 * sin(t * 0.7 + star.z))
                   * smoothstep(-0.2, 0.4, p.y) * smoothstep(1.35, 0.35, length(p * vec2(0.85, 1.15)));
            }
            """;
    private static final String STAR_FRAGMENT = """
            precision mediump float;
            varying float glow;
            void main() {
              float s = smoothstep(0.5, 0.1, length(gl_PointCoord - 0.5));
              gl_FragColor = vec4(vec3(0.75, 0.8, 0.9) * (0.33 * glow * s), 1.0);
            }
            """;

    private final Aurora colors;
    private final String columnsSource, skySource;
    /** A different sky each time the screensaver starts. */
    private final float timeOffset = 30f + new Random().nextFloat() * 600f;
    private final float[] rgb = new float[6];
    private final FloatBuffer triangle = floats(new float[]{-1f, -1f, 3f, -1f, -1f, 3f});
    private final FloatBuffer stars;
    private final ByteBuffer ditherNoise = ByteBuffer.allocateDirect(DITHER * DITHER);
    private boolean running;
    /** Set on the main thread, read on the GL thread. */
    private volatile long startMs;

    // GL thread only, rebuilt in each new context: programs, the uniforms set every frame, {texture, framebuffer} per target.
    private int columns, sky, stretch, starProgram, width, height;
    private int columnsT, pulseU, baseU, crownU, starT;
    private int[] columnTarget, skyTarget;
    private boolean broken;
    private long nextFrameNs;

    AuroraView(Context context, int color) {
        super(context);
        colors = new Aurora(color);
        columnsSource = read(context, R.raw.aurora_columns);
        skySource = read(context, R.raw.aurora_sky);
        Random random = new Random();
        float[] s = new float[STARS * 4];
        for (int i = 0; i < s.length; i += 4) {
            s[i] = random.nextFloat();
            s[i + 1] = random.nextFloat();
            s[i + 2] = random.nextFloat() * 6.2832f;
            s[i + 3] = 0.6f + 0.4f * random.nextFloat();
        }
        stars = floats(s);
        byte[] noise = new byte[DITHER * DITHER];
        random.nextBytes(noise);
        ditherNoise.put(noise).position(0);
        setEGLContextClientVersion(2);
        setEGLConfigChooser((egl, display) -> {
            EGLConfig config = choose(egl, display);
            // supported() found one before this view was made; only a driver that changed its mind gets here
            if (config == null) throw new IllegalStateException("No EGL config for OpenGL ES 2.0");
            return config;
        });
        getHolder().setFixedSize(WIDTH, HEIGHT);
        setRenderer(this);
        // Continuous, paced on the GL thread itself (pace()), so the main thread is never woken for it.
        // Paused until start(): the screensaver's own lifecycle decides when it draws.
        onPause();
    }

    /**
     * Whether this TV's EGL gives an OpenGL ES 2.0 context to draw in. GLSurfaceView throws on its own thread when it
     * cannot, which would take the whole launcher (Home) down, so ClockScene asks this first and leaves the aurora
     * out on a TV that says no. Asked once per process; main thread.
     */
    static boolean supported() {
        if (supported == null) supported = probe();
        return supported;
    }

    private static Boolean supported;

    private static boolean probe() {
        try {
            EGL10 egl = (EGL10) EGLContext.getEGL();
            EGLDisplay display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
            if (display == EGL10.EGL_NO_DISPLAY || !egl.eglInitialize(display, new int[2])) return false;
            EGLConfig config = choose(egl, display);
            if (config == null) return false;
            EGLContext context = egl.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT,
                    new int[]{EGL_CONTEXT_CLIENT_VERSION, 2, EGL10.EGL_NONE});
            if (context == null || context == EGL10.EGL_NO_CONTEXT) return false;
            egl.eglDestroyContext(display, context);
            return true;   // the display stays initialised: HWUI shares it, so it is not terminated here
        } catch (RuntimeException e) {
            Log.w(TAG, "EGL probe failed: aurora off", e);
            return false;
        }
    }

    private static final int EGL_CONTEXT_CLIENT_VERSION = 0x3098, EGL_OPENGL_ES2_BIT = 4;

    /** An ES 2.0 window config: RGB888 without alpha or depth if there is one, else any 8-bit RGB, else the first (565). */
    private static EGLConfig choose(EGL10 egl, EGLDisplay display) {
        int[] spec = {EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL10.EGL_SURFACE_TYPE, EGL10.EGL_WINDOW_BIT,
                EGL10.EGL_NONE};
        int[] count = new int[1];
        if (!egl.eglChooseConfig(display, spec, null, 0, count) || count[0] <= 0) return null;
        EGLConfig[] configs = new EGLConfig[count[0]];
        if (!egl.eglChooseConfig(display, spec, configs, configs.length, count) || count[0] <= 0) return null;
        EGLConfig rgb = null;
        for (int i = 0; i < count[0]; i++) {
            EGLConfig c = configs[i];
            if (c == null) continue;
            boolean rgb888 = value(egl, display, c, EGL10.EGL_RED_SIZE) == 8
                    && value(egl, display, c, EGL10.EGL_GREEN_SIZE) == 8 && value(egl, display, c, EGL10.EGL_BLUE_SIZE) == 8;
            if (!rgb888) continue;
            if (value(egl, display, c, EGL10.EGL_ALPHA_SIZE) == 0 && value(egl, display, c, EGL10.EGL_DEPTH_SIZE) == 0) return c;
            if (rgb == null) rgb = c;
        }
        return rgb != null ? rgb : configs[0];
    }

    private static int value(EGL10 egl, EGLDisplay display, EGLConfig config, int attribute) {
        int[] v = new int[1];
        return egl.eglGetConfigAttrib(display, config, attribute, v) ? v[0] : -1;
    }

    void start() {
        if (running) return;
        running = true;
        setRenderMode(RENDERMODE_CONTINUOUSLY);   // a context that failed last time may work this time
        startMs = SystemClock.uptimeMillis();
        onResume();
    }

    /** Safe to call twice (ClockDream stops in onDreamingStopped and again on detach). */
    void stop() {
        if (!running) return;
        running = false;
        onPause();   // blocks until the GL thread has released its surface and context
    }

    /** Fade to the glow's next colour over the glow's 8 s. Main thread. */
    void fadeTo(int color) {
        colors.fadeTo(color, SystemClock.uptimeMillis());
    }

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        GLES20.glDisable(GLES20.GL_DITHER);   // pass 1 stores numbers, not colours
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE);
        columns = program(VERTEX, columnsSource);
        sky = program(VERTEX, skySource);
        stretch = program(VERTEX, STRETCH);
        starProgram = program(STAR_VERTEX, STAR_FRAGMENT);
        broken = columns == 0 || sky == 0 || stretch == 0 || starProgram == 0;
        columnTarget = skyTarget = null;
        if (broken) return;
        columnsT = GLES20.glGetUniformLocation(columns, "t");
        pulseU = GLES20.glGetUniformLocation(sky, "pulse");
        baseU = GLES20.glGetUniformLocation(sky, "base");
        crownU = GLES20.glGetUniformLocation(sky, "crown");
        starT = GLES20.glGetUniformLocation(starProgram, "t");
        // Texture unit 1 holds the dither noise for good; unit 0 takes pass 1's or pass 2's result as needed.
        int[] id = new int[1];
        GLES20.glGenTextures(1, id, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id[0]);
        parameters(GLES20.GL_NEAREST, GLES20.GL_REPEAT);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, DITHER, DITHER, 0,
                GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, ditherNoise);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glEnableVertexAttribArray(0);
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int w, int h) {
        width = w;
        height = h;
        if (broken) return;
        release(columnTarget);
        release(skyTarget);
        // NEAREST: the edge's two 8-bit halves must not be blended between columns
        columnTarget = target(w, 3, GLES20.GL_NEAREST);
        skyTarget = target(w, skyHeight(), GLES20.GL_LINEAR);
        if (columnTarget == null || skyTarget == null) {
            Log.w(TAG, "No render-to-texture: aurora off");
            broken = true;
            return;
        }
        // Uniforms that hold for this size; each program keeps its own from now on.
        size(columns, w, h);
        size(sky, w, skyHeight());
        GLES20.glUniform1f(GLES20.glGetUniformLocation(sky, "aspect"), w / (float) h);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(sky, "cols"), 0);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(sky, "dither"), 1);
        size(stretch, w, h);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(stretch, "sky"), 0);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(stretch, "dither"), 1);
        size(starProgram, w, h);
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        pace();
        if (broken) {
            // One black frame, then no more until start() again: not a clear and swap 15 times a second for hours.
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            post(() -> setRenderMode(RENDERMODE_WHEN_DIRTY));
            return;
        }
        long now = SystemClock.uptimeMillis();
        float t = timeOffset + (now - startMs) / 1000f;
        colors.colors(now, rgb);

        // 1: each curtain's edge, rays and lean per column, one row each. Cleared first, so a tiler loads nothing.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, columnTarget[1]);
        GLES20.glViewport(0, 0, width, 3);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(columns);
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, triangle);
        GLES20.glUniform1f(columnsT, t);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3);

        // 2: the curtains and the night sky at a third of the height
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, skyTarget[1]);
        GLES20.glViewport(0, 0, width, skyHeight());
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(sky);
        GLES20.glUniform1f(pulseU, (float) ((t * 0.3) % (2 * Math.PI)));
        GLES20.glUniform3fv(baseU, 1, rgb, 0);
        GLES20.glUniform3fv(crownU, 1, rgb, 3);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, columnTarget[0]);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3);

        // 3: stretched to the screen, dithered
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, width, height);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(stretch);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, skyTarget[0]);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3);

        // 4: the stars, added on top
        GLES20.glUseProgram(starProgram);
        GLES20.glVertexAttribPointer(0, 4, GLES20.GL_FLOAT, false, 0, stars);
        GLES20.glUniform1f(starT, t);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, STARS);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    /** Hold the GL thread to one frame per FRAME_NS; after a late frame the count starts again rather than rushing. */
    private void pace() {
        long now = System.nanoTime();
        if (nextFrameNs - now > 0) {
            SystemClock.sleep((nextFrameNs - now) / 1_000_000L);
            now = nextFrameNs;
        }
        nextFrameNs = (now - nextFrameNs > FRAME_NS ? now : nextFrameNs) + FRAME_NS;
    }

    private int skyHeight() {
        return Math.max(1, height / 3);
    }

    /** The target size, set once per program. */
    private static void size(int program, int w, int h) {
        GLES20.glUseProgram(program);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "res"), w, h);
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
        if (ok) return id;
        release(id);
        return null;
    }

    private static void release(int[] target) {
        if (target == null) return;
        GLES20.glDeleteTextures(1, target, 0);
        GLES20.glDeleteFramebuffers(1, target, 1);
    }

    private static void parameters(int filter, int wrap) {
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, wrap);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, wrap);
    }

    /** A linked program, or 0 (logged) if the driver refuses a shader: the aurora then stays black. */
    private static int program(String vertex, String fragment) {
        int vs = shader(GLES20.GL_VERTEX_SHADER, vertex), fs = shader(GLES20.GL_FRAGMENT_SHADER, fragment);
        if (vs == 0 || fs == 0) return 0;
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, vs);
        GLES20.glAttachShader(p, fs);
        GLES20.glBindAttribLocation(p, 0, "a");
        GLES20.glBindAttribLocation(p, 0, "star");   // each program has one of the two
        GLES20.glLinkProgram(p);
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] != 0) return p;
        Log.w(TAG, "Link failed: " + GLES20.glGetProgramInfoLog(p));
        GLES20.glDeleteProgram(p);
        return 0;
    }

    private static int shader(int type, String source) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, source);
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
            return "";   // fails to compile, so the aurora stays black
        }
    }
}
