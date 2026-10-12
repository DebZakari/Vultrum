package dev.vultrum.cull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class CullExecutionTest {
    static class Recording implements CullExecution.Commands {
        final int[] instances = {1, 1, 1, 1};
        final int[] history = {1, 0, 1, 0};
        final List<Integer> drawn = new ArrayList<>();
        final String fault;
        int calls, failures, recoveries;
        boolean recoveredAfterPhase1;
        Recording(String fault) { this.fault = fault; }
        void hit(String point) { if (fault.equals(point)) throw new IllegalStateException(point); }
        public void prepare() { hit("unsupported-device"); }
        public void phase1() {
            System.arraycopy(history, 0, instances, 0, 4);
            hit("phase1-commands");
        }
        public void phase2() {
            hit("after-phase1");
            // Simulate even a partially rewritten command range; recovery uses the separate history.
            instances[0] = 0; instances[1] = 1;
            hit("partial-phase2");
            instances[2] = 0; instances[3] = 0;
        }
        public void readback() { hit("readback"); }
        public void recover(boolean phase1Drawn) {
            recoveries++;
            recoveredAfterPhase1 = phase1Drawn;
            hit("recovery");
            for (int i = 0; i < 4; i++) instances[i] = phase1Drawn ? 1 - history[i] : 1;
        }
        public void failed(RuntimeException failure) { failures++; }
        void draw() {
            calls++;
            for (int i = 0; i < 4; i++) if (instances[i] != 0) {
                drawn.add(i);
                hit("draw" + calls);
            }
        }
    }

    @Test void successfulFrameDrawsOnlyVisibleCommandsOnce() {
        var commands = new Recording("");
        var execution = new CullExecution();
        execution.run(commands, commands::draw);
        assertEquals(List.of(0, 2, 1), commands.drawn);
        assertEquals(0, commands.failures);
        assertEquals(CullExecution.Progress.COMPLETE, execution.progress());
    }

    @TestFactory Stream<DynamicTest> cullFailuresDrawEveryCommandExactlyOnce() {
        return Stream.of("unsupported-device", "phase1-commands", "after-phase1", "partial-phase2", "readback")
                .map(fault -> DynamicTest.dynamicTest(fault, () -> {
                    var commands = new Recording(fault);
                    new CullExecution().run(commands, commands::draw);
                    assertEquals(List.of(0, 1, 2, 3), commands.drawn.stream().sorted().toList());
                    assertEquals(1, commands.failures);
                    assertEquals(fault.equals("unsupported-device") ? 0 : 1, commands.recoveries);
                    assertEquals(!fault.equals("unsupported-device") && !fault.equals("phase1-commands"), commands.recoveredAfterPhase1);
                }));
    }

    @TestFactory Stream<DynamicTest> partialOriginalDrawIsNeverRetried() {
        return Stream.of("draw1", "draw2").map(fault -> DynamicTest.dynamicTest(fault, () -> {
            var commands = new Recording(fault);
            var execution = new CullExecution();
            assertThrows(IllegalStateException.class, () -> execution.run(commands, commands::draw));
            assertEquals(fault.equals("draw1") ? 1 : 2, commands.calls);
            assertEquals(0, commands.recoveries);
            assertEquals(1, commands.failures);
            assertEquals(fault.equals("draw1") ? CullExecution.Progress.DRAWING_PHASE1 : CullExecution.Progress.DRAWING_PHASE2, execution.progress());
        }));
    }

    @Test void failedRecoveryDoesNotDrawMutatedCommands() {
        var commands = new Recording("recovery") {
            public void phase2() { throw new IllegalStateException("phase2"); }
        };
        var failure = assertThrows(IllegalStateException.class, () -> new CullExecution().run(commands, commands::draw));
        assertEquals("phase2", failure.getMessage());
        assertEquals("recovery", failure.getSuppressed()[0].getMessage());
        assertEquals(List.of(0, 2), commands.drawn);
        assertEquals(1, commands.calls);
    }

    @Test void fallbackDrawFailurePropagatesWithoutAnotherRecovery() {
        var commands = new Recording("unsupported-device") {
            void draw() { calls++; throw new IllegalStateException("original"); }
        };
        assertEquals("original", assertThrows(IllegalStateException.class,
                () -> new CullExecution().run(commands, commands::draw)).getMessage());
        assertEquals(1, commands.calls);
        assertEquals(1, commands.failures);
    }
}
