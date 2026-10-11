package dev.vultrum.bench;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StatsTest {
    @Test
    void nearestRankPercentiles() {
        double[] samples = new double[100];
        for (int i = 0; i < 100; i++) samples[i] = 100 - i; // 1..100, unsorted
        var stats = Stats.of(samples);
        assertEquals(100, stats.count());
        assertEquals(50.5, stats.mean(), 1e-9);
        assertEquals(50, stats.p50());
        assertEquals(99, stats.p99());
        assertEquals(100, stats.p999());
        assertEquals(100, stats.max());
    }

    @Test
    void emptyInputIsNaN() {
        assertSame(Stats.EMPTY, Stats.of(new double[0]));
        assertTrue(Double.isNaN(Stats.averageFps(new double[0])));
        assertTrue(Double.isNaN(Stats.lowFps(new double[0], 0.01)));
    }

    @Test
    void fpsAndLows() {
        double[] frames = new double[1000];
        java.util.Arrays.fill(frames, 2.0);
        frames[0] = 20.0; // one 20 ms hitch among 999 frames of 2 ms
        assertEquals(1000 * 1000.0 / (999 * 2.0 + 20.0), Stats.averageFps(frames), 1e-9);
        // slowest 1% = 10 frames: one 20 ms + nine 2 ms -> mean 3.8 ms
        assertEquals(1000.0 / 3.8, Stats.lowFps(frames, 0.01), 1e-9);
        // slowest 0.1% = 1 frame
        assertEquals(50.0, Stats.lowFps(frames, 0.001), 1e-9);
    }

    @Test
    void hitchesMissA144HzRefreshAndDoubleTheMedian() {
        // Fast scene, median 0.5 ms: 1.5 ms is triple the median but no missed refresh.
        assertEquals(1, Stats.hitches(new double[] {0.5, 0.5, 0.5, 1.5, 7.0, 0.5, 0.5}));
        // Slow scene, median 5 ms: 8 ms misses a refresh but isn't twice the median; exactly 10 ms isn't over it.
        assertEquals(1, Stats.hitches(new double[] {5, 5, 5, 8, 10, 10.1, 5}));
        assertEquals(0, Stats.hitches(new double[0]));
    }

    @Test
    void hitchFramesAndTheirMeans() {
        double[] frames = {0.5, 9.0, 0.5, 0.5, 12.0, 0.5};
        int[] hitches = Stats.hitchFrames(frames);
        assertArrayEquals(new int[] {1, 4}, hitches);
        assertEquals(10.5, Stats.meanAt(frames, hitches, 0), 1e-9);
        // Offset 1: the samples of the frame after each hitch.
        assertEquals(4.0, Stats.meanAt(new double[] {0, 0, 3.0, 0, 0, 5.0}, hitches, 1), 1e-9);
        assertTrue(Double.isNaN(Stats.meanAt(frames, new int[0], 0)));
    }
}
