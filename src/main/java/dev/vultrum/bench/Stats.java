package dev.vultrum.bench;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.IntStream;

/** Summary statistics over per-frame millisecond samples. Percentiles use nearest rank. */
public record Stats(int count, double mean, double p50, double p99, double p999, double max) {
    public static final Stats EMPTY = new Stats(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);

    public static Stats of(double[] samples) {
        if (samples.length == 0) return EMPTY;
        double[] sorted = samples.clone();
        Arrays.sort(sorted);
        return new Stats(sorted.length, Arrays.stream(sorted).sum() / sorted.length,
                percentile(sorted, 50), percentile(sorted, 99), percentile(sorted, 99.9), sorted[sorted.length - 1]);
    }

    static double percentile(double[] sorted, double percent) {
        int rank = (int) Math.ceil(percent / 100.0 * sorted.length);
        return sorted[Math.clamp(rank - 1, 0, sorted.length - 1)];
    }

    /** Frames divided by total frame time. */
    public static double averageFps(double[] frameMs) {
        double total = Arrays.stream(frameMs).sum();
        return total > 0 ? frameMs.length * 1000.0 / total : Double.NaN;
    }

    /** 1000 / mean of the slowest {@code fraction} of frame times (0.01 gives the "1% low"). */
    public static double lowFps(double[] frameMs, double fraction) {
        if (frameMs.length == 0) return Double.NaN;
        double[] sorted = frameMs.clone();
        Arrays.sort(sorted);
        int count = Math.max(1, (int) Math.ceil(sorted.length * fraction));
        double sum = 0;
        for (int i = sorted.length - count; i < sorted.length; i++) sum += sorted[i];
        return 1000.0 / (sum / count);
    }

    /** A frame that misses a refresh at 144 Hz. */
    static final double HITCH_MS = 1000.0 / 144;

    /**
     * Frames that miss a 144 Hz refresh and take more than twice the median frame. The first rule
     * ignores scheduling noise at thousands of frames a second, the second the steady frame time
     * of a slow scene.
     */
    public static int hitches(double[] frameMs) { return hitchFrames(frameMs).length; }

    /** Indices of the {@link #hitches} frames, in order. */
    public static int[] hitchFrames(double[] frameMs) {
        if (frameMs.length == 0) return new int[0];
        double[] sorted = frameMs.clone();
        Arrays.sort(sorted);
        return hitchFrames(frameMs, percentile(sorted, 50));
    }

    /** {@link #hitchFrames(double[])} with the median frame already known, so the frames aren't sorted again. */
    public static int[] hitchFrames(double[] frameMs, double medianMs) {
        double limit = Math.max(HITCH_MS, 2 * medianMs);
        return IntStream.range(0, frameMs.length).filter(i -> frameMs[i] > limit).toArray();
    }

    /** Mean of {@code samples[i + offset]} over the given indices; NaN for none. */
    public static double meanAt(double[] samples, int[] indices, int offset) {
        double sum = 0;
        for (int i : indices) sum += samples[i + offset];
        return indices.length > 0 ? sum / indices.length : Double.NaN;
    }

    public Map<String, Object> toMap() {
        var map = new LinkedHashMap<String, Object>();
        map.put("count", count);
        map.put("mean", round(mean));
        map.put("p50", round(p50));
        map.put("p99", round(p99));
        map.put("p999", round(p999));
        map.put("max", round(max));
        return map;
    }

    static Object round(double value) {
        return Double.isFinite(value) ? Math.round(value * 10_000) / 10_000.0 : null;
    }
}
