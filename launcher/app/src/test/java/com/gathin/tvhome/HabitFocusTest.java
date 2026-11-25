package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

public class HabitFocusTest {
    @Test public void theDayHasFourSixHourBlocks() {
        assertEquals(0, HabitFocus.block(0));
        assertEquals(0, HabitFocus.block(5));
        assertEquals(1, HabitFocus.block(6));
        assertEquals(1, HabitFocus.block(11));
        assertEquals(2, HabitFocus.block(12));
        assertEquals(2, HabitFocus.block(17));
        assertEquals(3, HabitFocus.block(18));
        assertEquals(3, HabitFocus.block(23));
    }

    @Test public void thirtyMinutesAwayOrAColdStartCounts() {
        long minute = 60_000L;
        assertTrue("cold start: Home never hidden", HabitFocus.longAway(0, 5 * minute));
        assertFalse("a quick trip keeps the last app", HabitFocus.longAway(100 * minute, 129 * minute));
        assertTrue(HabitFocus.longAway(100 * minute, 130 * minute));
        assertTrue(HabitFocus.longAway(100 * minute, 400 * minute));
        assertTrue("saved before a restart reset the clock", HabitFocus.longAway(400 * minute, 2 * minute));
    }

    @Test public void aLaunchAddsOne() {
        Map<String, Integer> block = new HashMap<>();
        block.put("3|a/.A", 4);
        Map<String, Integer> next = HabitFocus.afterLaunch(block, "3|a/.A");
        assertEquals(Integer.valueOf(5), next.get("3|a/.A"));
        assertEquals(Integer.valueOf(1), HabitFocus.afterLaunch(next, "3|b/.B").get("3|b/.B"));
    }

    @Test public void passingTheCapHalvesTheWholeBlock() {
        Map<String, Integer> block = new HashMap<>();
        block.put("3|a/.A", HabitFocus.CAP);   // 50: this launch makes 51
        block.put("3|b/.B", 9);
        block.put("3|c/.C", 1);
        Map<String, Integer> next = HabitFocus.afterLaunch(block, "3|a/.A");
        assertEquals(Integer.valueOf(25), next.get("3|a/.A"));   // 51 / 2
        assertEquals(Integer.valueOf(4), next.get("3|b/.B"));    // 9 / 2
        assertFalse("1 / 2 is 0: forgotten", next.containsKey("3|c/.C"));
        assertEquals(2, next.size());
    }

    @Test public void atTheCapNothingHalves() {
        Map<String, Integer> block = new HashMap<>();
        block.put("3|a/.A", HabitFocus.CAP - 1);
        block.put("3|b/.B", 9);
        Map<String, Integer> next = HabitFocus.afterLaunch(block, "3|a/.A");
        assertEquals(Integer.valueOf(HabitFocus.CAP), next.get("3|a/.A"));
        assertEquals(Integer.valueOf(9), next.get("3|b/.B"));
    }

    @Test public void countsOfAppsGoneFromHomeAreStale() {
        assertEquals(new HashSet<>(Arrays.asList("0|gone/.G", "2|gone/.G", "junk")),
                HabitFocus.stale(new HashSet<>(Arrays.asList("0|a/.A", "0|gone/.G", "2|gone/.G", "3|a/.A", "junk")),
                        new HashSet<>(Arrays.asList("a/.A", "b/.B"))));
    }

    @Test public void theMostOpenedTileWins() {
        assertEquals(2, HabitFocus.pick(new int[]{3, 0, 7, 5}));
    }

    @Test public void aTieGoesToTheEarlierTile() {
        assertEquals(1, HabitFocus.pick(new int[]{0, 4, 4}));
    }

    @Test public void oneLaunchIsNoHabit() {
        assertEquals(-1, HabitFocus.pick(new int[]{1, 0, 1}));
        assertEquals(-1, HabitFocus.pick(new int[]{}));
        assertEquals(0, HabitFocus.pick(new int[]{2, 1}));
    }

    @Test public void countsAreKeptPerBlockAndTile() {
        assertEquals("3|org.example/.Main", HabitFocus.countKey(3, "org.example/.Main"));
    }
}
