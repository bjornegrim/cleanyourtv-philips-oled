package com.gathin.tvhome;

import android.os.Handler;
import android.os.Looper;
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
        if (text != null) show(tv, text);
        long now = SystemClock.elapsedRealtime();
        boolean fresh = text != null && fetchedAt >= 0 && now - fetchedAt < FRESH_MS;
        boolean backingOff = failedAt >= 0 && now - failedAt < RETRY_MS;
        if (fresh || backingOff || inFlight) return;
        inFlight = true;
        IO.execute(() -> {
            String line = fetch();
            MAIN.post(() -> {
                inFlight = false;
                long t = SystemClock.elapsedRealtime();
                if (line == null) {
                    failedAt = t;
                    return;
                }
                text = line;
                fetchedAt = t;
                failedAt = -1;
                for (WeakReference<TextView> ref : targets) {
                    TextView v = ref.get();
                    if (v != null) show(v, line);
                }
            });
        });
    }

    private static void show(TextView tv, String line) {
        tv.setText(line);
        tv.setVisibility(View.VISIBLE);
    }

    private static String fetch() {
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
            return Math.round(temp) + "°C · " + condition(current.getInt("weather_code"))
                    + " · Känns som " + Math.round(apparent) + "°C";
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
