package dev.vultrum.cull;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

class CullReadbacksTest {
    @Test void inFlightSlotsCannotBeMappedOrOverwritten() {
        var reads = new CullReadbacks(4);
        var mapped = new ArrayList<Integer>();
        reads.recorded(0, 8); reads.recorded(1, 10);
        reads.collect(7, mapped::add);
        assertTrue(mapped.isEmpty());
        assertFalse(reads.free(0));
        assertThrows(IllegalStateException.class, () -> reads.recorded(0, 12));
        reads.collect(8, mapped::add);
        assertEquals(java.util.List.of(0), mapped);
        assertTrue(reads.free(0)); assertFalse(reads.free(1));
        reads.recorded(0, 12);
        reads.collect(11, mapped::add);
        assertEquals(java.util.List.of(0, 1), mapped);
        reads.collect(12, mapped::add);
        assertEquals(java.util.List.of(0, 1, 0), mapped);
    }

    @Test void failedMappingDoesNotReleaseTheSlot() {
        var reads = new CullReadbacks(1); reads.recorded(0, 3);
        assertThrows(IllegalStateException.class, () -> reads.collect(3, ignored -> { throw new IllegalStateException("map"); }));
        assertFalse(reads.free(0));
        reads.discard(); assertTrue(reads.free(0));
    }
}
