package dev.vultrum.bench;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * A case's long terrain setups, each with its own split. One long setup among thousands is lost in the case's
 * means, and the hitch breakdown averages it with the other hitches.
 */
final class LongSetups {
    /** A setup at least this long is a long one. */
    static final double MIN_MS = 5;
    /** The most setups listed for a case: the longest ones. */
    static final int LIMIT = 32;

    private LongSetups() { }

    /** How many of a case's setups are long, listed or not. */
    static int count(double[] setup) {
        return (int) Arrays.stream(setup).filter(ms -> ms >= MIN_MS).count();
    }

    /**
     * The longest {@link #LIMIT} long setups, in the order they ran. Setup j runs in the extract before render j,
     * at the end of frame interval j - 1, so {@code frames} and {@code starts} are one shorter than {@code setup}.
     * {@code phases} (by key) and {@code refs} hold a sample for each setup, and {@code gpu} one for each render
     * unless a frame's sample was dropped.
     */
    static List<Map<String, Object>> of(double[] frames, double[] starts, double[] setup, Map<String, double[]> phases, double[] refs, double[] gpu) {
        // The sort is stable: of setups equally long, the earlier ones are kept.
        int[] longest = IntStream.range(0, setup.length).filter(j -> setup[j] >= MIN_MS).boxed()
                .sorted(Comparator.comparingDouble((Integer j) -> -setup[j])).limit(LIMIT).mapToInt(Integer::intValue).sorted().toArray();
        var list = new ArrayList<Map<String, Object>>();
        for (int j : longest) {
            var entry = new LinkedHashMap<String, Object>();
            // The first setup's frame interval started before the recording did.
            boolean inFrame = j >= 1 && j - 1 < frames.length;
            entry.put("startEpochMs", inFrame ? Math.round(starts[j - 1]) : null);
            entry.put("frame", inFrame ? Stats.round(frames[j - 1]) : null);
            entry.put("terrainSetup", Stats.round(setup[j]));
            double timed = 0;
            for (var phase : phases.entrySet()) {
                timed += phase.getValue()[j];
                entry.put(phase.getKey(), Stats.round(phase.getValue()[j]));
            }
            entry.put("untimed", Stats.round(setup[j] - timed));
            entry.put("reclaimedRefs", (int) refs[j]);
            // The GPU's time for the two renders before this setup and the one after it: a setup that waited
            // for the GPU would show there. Empty when a sample was dropped, since the renders no longer line up.
            var gpuFrames = new ArrayList<Object>();
            if (gpu.length == setup.length) for (int k = j - 2; k <= j; k++) gpuFrames.add(k >= 0 ? Stats.round(gpu[k]) : null);
            entry.put("gpuFrames", gpuFrames);
            list.add(entry);
        }
        return list;
    }
}
