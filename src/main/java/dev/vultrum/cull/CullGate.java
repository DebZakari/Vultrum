package dev.vultrum.cull;

/**
 * Turns the occlusion cull off in light scenes, where its fixed GPU cost (Hi-Z build, two dispatches and an
 * extra Vitrail pass, about 0.06 ms) is more than the triangles it leaves out save (about 0.23 ms per million).
 * They break even at about 0.26 M hidden triangles, which the two thresholds bracket. That was measured by
 * running the cull in alternate 32-frame blocks within each scene and comparing the two halves' GPU frame
 * (tag {@code probe/gate-alternate}, scenario {@code gate.json}), on an RTX 3090 with Complementary. Render
 * CPU isn't counted: a culled frame costs about 0.05 ms more, which shader-pack frames, GPU-bound, absorb.
 * Fed the hidden-triangle count of each culled frame, as the GPU reports it a few frames later.
 *
 * <p>On, it culls every frame and goes off after {@value #OFF_AFTER} samples in a row below {@value #OFF}
 * hidden triangles. Off, it culls one frame in {@value #SAMPLE_EVERY} and goes on at a sample of
 * {@value #ON} or more.
 */
final class CullGate {
    static final long ON = 300_000, OFF = 250_000;
    static final int OFF_AFTER = 32, SAMPLE_EVERY = 32;

    private boolean on = true;
    private int low, sinceSample;

    /** Called once per frame: whether this frame culls. */
    boolean cull() {
        if (on) return true;
        if (++sinceSample < SAMPLE_EVERY) return false;
        sinceSample = 0;
        return true;
    }

    /** A culled frame's readback arrived: triangles listed but drawn in neither phase. */
    void hidden(long triangles) {
        if (on) {
            low = triangles < OFF ? low + 1 : 0;
            if (low >= OFF_AFTER) {
                on = false;
                low = 0;
                sinceSample = 0;
            }
        } else if (triangles >= ON) {
            on = true;
        }
    }

    boolean on() {
        return on;
    }
}
