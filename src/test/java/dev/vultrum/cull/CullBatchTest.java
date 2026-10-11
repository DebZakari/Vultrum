package dev.vultrum.cull;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CullBatchTest {
    private static final int SECTIONS = CullBatch.SECTIONS_PER_REGION;

    @Test
    void copiesSectionsAtTheirCommand() {
        int[] recorded = {1, 2, 3, 3 * SECTIONS + 7, 4, 5, 6, 3 * SECTIONS + 8};
        int[] to = new int[16];
        CullBatch.copySections(recorded, 2, 3, 3, to, 1);
        assertArrayEquals(new int[] {0, 0, 0, 0, 1, 2, 3, 3 * SECTIONS + 7, 4, 5, 6, 3 * SECTIONS + 8, 0, 0, 0, 0}, to);
    }

    @Test
    void movesKeysMadeBeforeTheRegionHadAnId() {
        // Filled with region id -1: keys are index - 256, which the shader would leave alone.
        int[] recorded = {10, -4, 20, -SECTIONS + 7, 11, -4, 20, -SECTIONS + 255};
        int[] to = new int[8];
        CullBatch.copySections(recorded, 2, -1, 5, to, 0);
        assertArrayEquals(new int[] {10, -4, 20, 5 * SECTIONS + 7, 11, -4, 20, 5 * SECTIONS + 255}, to);
    }

    @Test
    void copiesOnlyTheBatchesCommands() {
        int[] recorded = {1, 1, 1, 9, 2, 2, 2, 10}; // the second command is stale, from a larger fill
        int[] to = new int[8];
        CullBatch.copySections(recorded, 1, -1, 0, to, 0);
        assertArrayEquals(new int[] {1, 1, 1, 9 + SECTIONS, 0, 0, 0, 0}, to);
    }
}
