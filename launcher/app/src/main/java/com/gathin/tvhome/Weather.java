package com.gathin.tvhome;

import android.os.Handler;
import android.os.Looper;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Weather at config.xml's latitude and longitude from Open-Meteo, shared by Home and the clock screensaver.
 *
 * One cached line for the whole process: a view gets the last reading at once (no network wait when Home is
 * rebuilt or the screensaver opens), and at most one request is in flight. A good reading is kept 20 minutes;
 * after a failure (TV offline at boot) the next try waits 2 minutes instead of firing on every minute tick.
 */
final class Weather {
    private static String urlString;             // from config.xml's latitude and longitude, null when unusable
    private static final long FRESH_MS = 20 * 60_000L;
    private static final long RETRY_MS = 2 * 60_000L;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "weather");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static String text;              // last good line, main thread only
    private static String spoken;            // the same with the condition in words, for TalkBack
    private static int code = -1;            // WMO weather code of that line, -1 before the first reading
    private static boolean day = true;       // Open-Meteo's is_day for that line
    private static long fetchedAt = -1;      // elapsedRealtime of the last good line
    private static long failedAt = -1;       // elapsedRealtime of the last failure
    private static boolean inFlight;
    /** Every view that asked since the last answer (Home and the screensaver can both wait on one request). */
    private static final java.util.List<WeakReference<TextView>> targets = new java.util.ArrayList<>(2);

    private Weather() {}

    /** Shows the cached line in {@code tv} now, and fetches a new one if it is stale. Main thread. */
    static void bind(TextView tv) {
        if (tv == null) return;
        urlString = WeatherUrl.of(tv.getContext().getString(R.string.latitude), tv.getContext().getString(R.string.longitude));
        if (urlString == null) return;   // no place set: no request, the row stays hidden
        boolean known = false;
        for (java.util.Iterator<WeakReference<TextView>> it = targets.iterator(); it.hasNext(); ) {
            TextView v = it.next().get();
            if (v == null) it.remove();
            else if (v == tv) known = true;
        }
        if (!known) targets.add(new WeakReference<>(tv));
        if (text != null) show(tv);
        long now = SystemClock.elapsedRealtime();
        boolean fresh = text != null && fetchedAt >= 0 && now - fetchedAt < FRESH_MS;
        boolean backingOff = failedAt >= 0 && now - failedAt < RETRY_MS;
        if (fresh || backingOff || inFlight) return;
        inFlight = true;
        IO.execute(() -> {
            Reading reading = fetch();
            MAIN.post(() -> {
                inFlight = false;
                long t = SystemClock.elapsedRealtime();
                if (reading == null) {
                    failedAt = t;
                    return;
                }
                text = reading.line;
                spoken = reading.spoken;
                code = reading.code;
                day = reading.day;
                fetchedAt = t;
                failedAt = -1;
                for (WeakReference<TextView> ref : targets) {
                    TextView v = ref.get();
                    if (v != null) show(v);
                }
            });
        });
    }

    /** The WMO weather code of the last reading, or -1 before the first one. Main thread. */
    static int code() {
        return code;
    }

    /** Open-Meteo's is_day for the last reading: false between sunset and sunrise; true before the first one. */
    static boolean isDay() {
        return day;
    }

    /**
     * The line, after a line icon for the weather (WeatherIcon) in the text's own colour and opacity, about 1.3 times
     * the text's height: the icon carries the condition, the words stay for TalkBack.
     */
    private static void show(TextView tv) {
        tv.setText(text);
        tv.setContentDescription(spoken);
        int icon = WeatherIcon.forCode(code, day);
        Drawable d = icon == 0 ? null : tv.getContext().getDrawable(icon);
        if (d != null) {
            int size = Math.round(tv.getTextSize() * 1.3f);
            d.setBounds(0, 0, size, size);
            tv.setCompoundDrawablePadding(Math.round(tv.getTextSize() * 0.4f));
            tv.setCompoundDrawableTintList(tv.getTextColors());
        }
        tv.setCompoundDrawablesRelative(d, null, null, null);
        tv.setVisibility(View.VISIBLE);
    }

    private static final class Reading {
        final String line, spoken;
        final int code;
        final boolean day;

        Reading(String line, String spoken, int code, boolean day) {
            this.line = line;
            this.spoken = spoken;
            this.code = code;
            this.day = day;
        }
    }

    private static Reading fetch() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlString).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            if (conn.getResponseCode() != 200) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream(512);
            try (InputStream in = conn.getInputStream()) {
                byte[] buf = new byte[1024];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            org.json.JSONObject current = new org.json.JSONObject(out.toString("UTF-8")).getJSONObject("current");
            double temp = current.getDouble("temperature_2m");
            double apparent = current.optDouble("apparent_temperature", temp);
            int wmo = current.getInt("weather_code");
            String feels = " · Känns som " + Math.round(apparent) + "°C";
            return new Reading(Math.round(temp) + "°C" + feels,
                    Math.round(temp) + "°C · " + condition(wmo) + feels, wmo, current.optInt("is_day", 1) != 0);
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String condition(int code) {
        switch (code) {
            case 0: return "Klart";
            case 1: return "Mestadels klart";
            case 2: return "Halvklart";
            case 3: return "Molnigt";
            case 45: case 48: return "Dimmigt";
            case 51: case 53: case 55: return "Duggregn";
            case 56: case 57: case 66: case 67: return "Underkylt regn";
            case 61: case 63: case 65: return "Regn";
            case 71: case 73: case 75: case 77: return "Snöfall";
            case 80: case 81: case 82: return "Regnskurar";
            case 85: case 86: return "Snöbyar";
            case 95: case 96: case 99: return "Åska";
            default: return "Molnigt";
        }
    }
}
