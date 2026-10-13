package dev.vultrum.bench;

/**
 * The section build and sort jobs Sodium has handed to its build threads in a benchmark run, from its important sets
 * and from its walk's deferred list, which {@code isTerrainRenderComplete} doesn't see. Render thread only.
 */
public final class Builds {
    private static long submitted;
    private static long lastNanos = System.nanoTime();

    private Builds() {}

    public static void submitted() {
        submitted++;
        lastNanos = System.nanoTime();
    }

    static long count() { return submitted; }

    /** Seconds since the last submission. */
    static double quietSeconds() { return (System.nanoTime() - lastNanos) / 1e9; }
}
