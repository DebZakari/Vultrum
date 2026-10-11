package dev.vultrum.bench;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LongSetupsTest {
    private static Map<String, double[]> phases(double[] reclaim, double[] upload) {
        var phases = new LinkedHashMap<String, double[]>();
        phases.put("reclaim", reclaim);
        phases.put("upload", upload);
        return phases;
    }

    @Test
    void aLongSetupIsListedWithTheFrameItEndsAndItsOwnSplit() {
        // Four renders, three frame intervals. Setup 2 runs at the end of interval 1, before render 2.
        double[] frames = {1, 20, 1}, starts = {1000, 1001, 1021};
        double[] setup = {0.2, 0.3, 16, 4.99};
        var phases = phases(new double[] {0, 0, 15.5, 4}, new double[] {0.1, 0.1, 0.3, 0.1});
        var list = LongSetups.of(frames, starts, setup, phases, new double[] {0, 0, 35_000, 9}, new double[] {3, 3.1, 3.2, 3.3});
        assertEquals(1, list.size());
        var entry = list.get(0);
        assertEquals(1001L, entry.get("startEpochMs"));
        assertEquals(20.0, entry.get("frame"));
        assertEquals(16.0, entry.get("terrainSetup"));
        assertEquals(15.5, entry.get("reclaim"));
        assertEquals(0.3, entry.get("upload"));
        assertEquals(0.2, entry.get("untimed"));
        assertEquals(35_000, entry.get("reclaimedRefs"));
        assertEquals(List.of(3.0, 3.1, 3.2), entry.get("gpuFrames"));
    }

    @Test
    void theFirstSetupHasNoFrameAndNoRendersBeforeIt() {
        double[] setup = {5, 0};
        var list = LongSetups.of(new double[] {1}, new double[] {1000}, setup, phases(new double[] {5, 0}, new double[] {0, 0}), new double[] {7, 0}, new double[] {3, 3});
        assertEquals(1, list.size());
        var entry = list.get(0);
        assertNull(entry.get("startEpochMs"));
        assertNull(entry.get("frame"));
        assertEquals(0.0, entry.get("untimed"));
        assertEquals(Arrays.asList(null, null, 3.0), entry.get("gpuFrames"));
    }

    @Test
    void aDroppedGpuSampleLeavesTheGpuFramesOut() {
        double[] setup = {0, 6};
        var list = LongSetups.of(new double[] {7}, new double[] {1000}, setup, phases(new double[] {0, 6}, new double[] {0, 0}), new double[] {0, 1}, new double[] {3});
        assertEquals(List.of(), list.get(0).get("gpuFrames"));
        assertEquals(7.0, list.get(0).get("frame"));
    }

    @Test
    void onlyTheLongestAreListedInTheOrderTheyRan() {
        // Eight setups of 6 ms, then more of 9 ms than the list holds, then one of 20 ms.
        int n = 8 + LongSetups.LIMIT + 2;
        double[] setup = new double[n], zeros = new double[n], frames = new double[n - 1];
        Arrays.fill(setup, 0, 8, 6);
        Arrays.fill(setup, 8, n - 1, 9);
        setup[n - 1] = 20;
        for (int i = 0; i < frames.length; i++) frames[i] = i;
        var list = LongSetups.of(frames, new double[n - 1], setup, phases(zeros, zeros), zeros, zeros);
        assertEquals(n, LongSetups.count(setup));
        assertEquals(LongSetups.LIMIT, list.size());
        // The 6 ms setups are left out, and of the equal 9 ms setups the last two: the first one listed is setup 8.
        assertEquals(7.0, list.get(0).get("frame"));
        assertEquals(9.0, list.get(0).get("untimed"));
        var last = list.get(LongSetups.LIMIT - 1);
        assertEquals(20.0, last.get("terrainSetup"));
        assertEquals((double) (n - 2), last.get("frame"));
    }

    @Test
    void countsEverySetupAtOrOverTheLine() {
        assertEquals(2, LongSetups.count(new double[] {4.99, 5, 0, 17}));
        assertEquals(0, LongSetups.count(new double[0]));
    }
}
