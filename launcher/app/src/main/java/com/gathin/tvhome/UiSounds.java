package com.gathin.tvhome;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Handler;
import android.os.HandlerThread;

/**
 * ui_sounds: a soft low tick when focus moves and a deeper sound on OK (res/raw/ui_tick.wav, ui_ok.wav: short
 * decaying sines, about 1 KB and 7 KB). Played through SoundPool as interface sounds, at a low volume, whatever the
 * TV's own "sound effects" setting says: the switch in Launcher settings decides.
 */
final class UiSounds {
    private static final float TICK_VOLUME = 0.5f, OK_VOLUME = 0.6f;
    private final SoundPool pool;
    private final int tick, ok;
    /** play() makes binder calls and may set up an AudioTrack: measured on the TV, on the UI thread it made about
     *  40 % of D-pad frames miss their deadline. Its own thread keeps the focus animation smooth. */
    private final HandlerThread thread = new HandlerThread("ui-sounds");
    private final Handler handler;

    UiSounds(Context ctx) {
        pool = new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        // Loaded in the background; a press before they are ready simply plays nothing.
        tick = pool.load(ctx, R.raw.ui_tick, 1);
        ok = pool.load(ctx, R.raw.ui_ok, 1);
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    void tick() {
        handler.post(() -> pool.play(tick, TICK_VOLUME, TICK_VOLUME, 1, 0, 1f));
    }

    void ok() {
        handler.post(() -> pool.play(ok, OK_VOLUME, OK_VOLUME, 2, 0, 1f));
    }

    void release() {
        handler.post(pool::release);
        thread.quitSafely();
    }
}
