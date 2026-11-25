package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * habit_focus: after a long time away, Home starts on the app it opened most often at this time of day. Each launch
 * from a tile adds one to that app's count in the current block of the day (four blocks of six hours), kept in the
 * launcher's own preferences. Nothing is read from other apps.
 */
final class HabitFocus {
    /** Away at least this long (or a cold start) and focus starts on the habit app; shorter keeps the last app. */
    static final long AWAY_MS = 30 * 60_000L;
    /** Launches an app needs in a block before it can take the focus, so one odd evening steers nothing. */
    static final int MIN_COUNT = 2;
    /** A count past this halves every count in its block, so old habits fade and the numbers stay small. */
    static final int CAP = 50;
    private static final String PREFS = "open_counts";

    private HabitFocus() {
    }

    /** The block of the day an hour (0 to 23) falls in: 0 night, 1 morning, 2 afternoon, 3 evening. */
    static int block(int hourOfDay) {
        return Math.max(0, Math.min(23, hourOfDay)) / 6;
    }

    /**
     * True when Home was hidden long enough for the habit app to take the focus. Both times come from
     * SystemClock.elapsedRealtime, so setting the TV's clock changes nothing; {@code hiddenAt} 0 means Home has not
     * been hidden since the process started (a cold start).
     */
    static boolean longAway(long hiddenAt, long now) {
        // Later than now: saved before the TV restarted, which resets elapsedRealtime. That is a cold start too.
        return hiddenAt <= 0 || hiddenAt > now || now - hiddenAt >= AWAY_MS;
    }

    /** Index of the highest count of at least MIN_COUNT, the earlier tile on a tie; -1 when none has enough. */
    static int pick(int[] counts) {
        int best = -1;
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] >= MIN_COUNT && (best < 0 || counts[i] > counts[best])) best = i;
        }
        return best;
    }

    static String countKey(int block, String tileKey) {
        return block + "|" + tileKey;
    }

    /**
     * One block's counts after one more launch of {@code key}: past CAP every count in the block is halved (rounded
     * down), and a count that reaches 0 is left out.
     */
    static Map<String, Integer> afterLaunch(Map<String, Integer> block, String key) {
        Map<String, Integer> next = new HashMap<>(block);
        int count = next.getOrDefault(key, 0) + 1;
        next.put(key, count);
        if (count <= CAP) return next;
        Map<String, Integer> halved = new HashMap<>();
        for (Map.Entry<String, Integer> e : next.entrySet()) if (e.getValue() / 2 > 0) halved.put(e.getKey(), e.getValue() / 2);
        return halved;
    }

    /** The saved count keys whose tile is not among {@code tileKeys} (an app removed from Home or uninstalled). */
    static Set<String> stale(Set<String> savedKeys, Set<String> tileKeys) {
        Set<String> gone = new HashSet<>();
        for (String key : savedKeys) {
            int bar = key.indexOf('|');
            if (bar < 0 || !tileKeys.contains(key.substring(bar + 1))) gone.add(key);
        }
        return gone;
    }

    /** One more launch of this tile (its component, as AppAdapter.keyAt) in the block of the day it is now. */
    static void record(Context ctx, String tileKey, int hourOfDay) {
        SharedPreferences prefs = prefs(ctx);
        String prefix = block(hourOfDay) + "|";
        Map<String, Integer> block = new HashMap<>();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            if (e.getKey().startsWith(prefix) && e.getValue() instanceof Integer) block.put(e.getKey(), (Integer) e.getValue());
        }
        Map<String, Integer> next = afterLaunch(block, prefix + tileKey);
        SharedPreferences.Editor edit = prefs.edit();
        for (String key : block.keySet()) if (!next.containsKey(key)) edit.remove(key);
        for (Map.Entry<String, Integer> e : next.entrySet()) edit.putInt(e.getKey(), e.getValue());
        edit.apply();
    }

    /** Forgets the counts of tiles that are no longer on Home (MainActivity.reloadTiles). */
    static void prune(Context ctx, Set<String> tileKeys) {
        SharedPreferences prefs = prefs(ctx);
        Set<String> gone = stale(prefs.getAll().keySet(), tileKeys);
        if (gone.isEmpty()) return;
        SharedPreferences.Editor edit = prefs.edit();
        for (String key : gone) edit.remove(key);
        edit.apply();
    }

    /** Each tile's launches in this block, in the order of {@code tileKeys}. */
    static int[] counts(Context ctx, List<String> tileKeys, int hourOfDay) {
        SharedPreferences prefs = prefs(ctx);
        int block = block(hourOfDay);
        int[] counts = new int[tileKeys.size()];
        for (int i = 0; i < counts.length; i++) counts[i] = prefs.getInt(countKey(block, tileKeys.get(i)), 0);
        return counts;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
