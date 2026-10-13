package dev.vultrum.bench;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TimelineTest {
    @Test
    void readsItsModeInAnyCase() {
        assertEquals("true", Timeline.mode("default.json", "TRUE"));
        assertEquals("chunks", Timeline.mode("default.json", "Chunks"));
        assertTrue(Timeline.on("true"));
        assertTrue(Timeline.full("true"));
        assertTrue(Timeline.on("chunks"));
        assertFalse(Timeline.full("chunks"));
    }

    @Test
    void isOffWhenNotAskedForOrOutsideABenchmark() {
        assertEquals("", Timeline.mode("default.json", null));
        assertEquals("", Timeline.mode(null, "true"));
        for (var mode : new String[] {"", "false", "yes"}) {
            assertFalse(Timeline.on(mode), mode);
            assertFalse(Timeline.full(mode), mode);
        }
    }
}
