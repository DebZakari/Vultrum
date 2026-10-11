package dev.vultrum.cull;

/**
 * Per-command section of a Sodium draw batch, added to {@code MultiDrawBatch} by a mixin. Batches are
 * filled once and reused across frames, so the mapping lives with the batch: four ints per command,
 * section x, y, z and the section's visibility key, {@code region * 256 + index in region}, for the region
 * id the batch was filled with.
 */
public interface CullBatch {
    int SECTIONS_PER_REGION = 256;

    /**
     * Copies {@code count} commands' sections, recorded with region id {@code recordedRegion}, into {@code to} at
     * command {@code at}. A batch filled before its region had an id holds keys for region -1, so keys move
     * to region id {@code region}.
     */
    static void copySections(int[] recorded, int count, int recordedRegion, int region, int[] to, int at) {
        System.arraycopy(recorded, 0, to, at * 4, count * 4);
        int shift = (region - recordedRegion) * SECTIONS_PER_REGION;
        if (shift != 0) for (int i = at * 4 + 3; i < (at + count) * 4; i += 4) to[i] += shift;
    }

    /** Commands {@code [from, to)} belong to one section. */
    void vultrum$section(int from, int to, int x, int y, int z, int region, int index);

    /** Four ints per command, valid for the batch's current size. */
    int[] vultrum$sections();

    /** The region id the keys were made with; -1 before the region had one. */
    int vultrum$region();
}
