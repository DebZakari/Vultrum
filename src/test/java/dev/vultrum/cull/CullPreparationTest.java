package dev.vultrum.cull;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CullPreparationTest {
    final Object context = new Object();
    final int[] section = {2, 3, 4, 7};
    void batch(CullPreparation prep, int at) { prep.append(context, at, 1, 0, false, 0, section); }

    @Test void interruptedPreparationCannotPublishOrContaminateNextFrame() {
        var prep = new CullPreparation();
        assertThrows(IllegalStateException.class, () -> prep.run(true, () -> {
            batch(prep, 40); throw new IllegalStateException("prepare interrupted");
        }));
        assertFalse(prep.take());
        assertNull(prep.context);
        prep.run(true, () -> batch(prep, 80));
        assertTrue(prep.take());
        assertFalse(prep.take());
        assertEquals(80, prep.first);
        assertEquals(1, prep.count);
    }

    @Test void gateSkippedFrameDiscardsOldCommandsAndStillRunsOriginal() {
        var prep = new CullPreparation();
        prep.run(true, () -> batch(prep, 20));
        int[] calls = {0};
        prep.run(false, () -> { calls[0]++; batch(prep, 40); });
        assertEquals(1, calls[0]);
        assertFalse(prep.take());
        assertNull(prep.context);
    }

    @Test void overlapContextChangeAndIncompleteMetadataInvalidateTheWholeRange() {
        for (int fault = 0; fault < 3; fault++) {
            int at = fault;
            var prep = new CullPreparation();
            prep.run(true, () -> {
                batch(prep, 20);
                prep.append(at == 1 ? new Object() : context, at == 0 ? 20 : 21, 1, 0, false, 0,
                        at == 2 ? new int[0] : section);
            });
            assertFalse(prep.take());
        }
    }

    @Test void gapsAndTranslucencyKeepTheirCommandsOutsideTheCull() {
        var prep = new CullPreparation();
        prep.run(true, () -> {
            batch(prep, 20);
            prep.append(context, 23, 1, 0, true, 0, section);
            prep.append(context, 24, 1, 2, false, 0, section);
        });
        assertTrue(prep.take());
        assertEquals(5, prep.count);
        assertEquals(-1, prep.sections[7]);
        assertEquals(-1, prep.sections[11]);
        assertEquals(-1, prep.sections[15]);
        assertEquals(519, prep.sections[19]);
    }
}
