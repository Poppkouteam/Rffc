package com.rffc.geodash.game;

import static org.junit.Assert.assertTrue;

import java.util.HashSet;

import org.junit.Test;

/**
 * Searches for an input sequence that finishes each level, changing the input
 * at most every 1/30 s so a solution does not rely on frame-perfect timing.
 */
public class LevelsBeatableTest {
    private static final float DT = 1f / 240f;
    private static final int TICKS_PER_DECISION = 8;

    private final HashSet<Long> seen = new HashSet<>();
    private float furthest;

    @Test
    public void allLevelsAreBeatable() throws Exception {
        for (Level level : Levels.all()) {
            seen.clear();
            furthest = 0;
            boolean[] result = new boolean[1];
            // Deep recursion: run on a thread with a large stack.
            Thread t = new Thread(null, () -> result[0] = search(new World(level), 0, false),
                    "search", 1L << 29);
            t.start();
            t.join();
            assertTrue("Level '" + level.name + "' is not beatable, stuck near x=" + furthest, result[0]);
        }
    }

    private boolean search(World w, int depth, boolean prevHold) {
        if (w.won) return true;
        if (w.dead) return false;
        furthest = Math.max(furthest, w.x);
        long key = ((long) depth << 34) ^ ((long) Math.round(w.y * 40) << 20)
                ^ ((long) Math.round(w.vy * 4) << 4)
                ^ (w.ship ? 1 : 0) ^ (prevHold ? 2 : 0) ^ (w.onGround ? 4 : 0);
        if (!seen.add(key)) return false;
        for (int h = 0; h < 2; h++) {
            boolean hold = h == 1;
            World next = w.copy();
            for (int i = 0; i < TICKS_PER_DECISION && !next.dead && !next.won; i++) {
                next.step(DT, hold, hold && !prevHold && i == 0);
            }
            if (search(next, depth + 1, hold)) return true;
        }
        return false;
    }
}
