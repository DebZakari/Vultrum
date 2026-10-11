package dev.vultrum.cull;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CullGateTest {
    @Test
    void startsOnAndCullsEveryFrame() {
        var gate = new CullGate();
        for (int i = 0; i < 100; i++) assertTrue(gate.cull());
    }

    @Test
    void goesOffAfterARunOfLowSamples() {
        var gate = new CullGate();
        for (int i = 0; i < CullGate.OFF_AFTER - 1; i++) gate.hidden(CullGate.OFF - 1);
        assertTrue(gate.on());
        gate.hidden(CullGate.OFF - 1);
        assertFalse(gate.on());
    }

    @Test
    void oneHighSampleRestartsTheRun() {
        var gate = new CullGate();
        for (int i = 0; i < CullGate.OFF_AFTER - 1; i++) gate.hidden(0);
        gate.hidden(CullGate.OFF); // between the thresholds still counts as worth culling while on
        for (int i = 0; i < CullGate.OFF_AFTER - 1; i++) gate.hidden(0);
        assertTrue(gate.on());
    }

    @Test
    void offSamplesOneFrameInN() {
        var gate = off();
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < CullGate.SAMPLE_EVERY - 1; i++) assertFalse(gate.cull());
            assertTrue(gate.cull());
        }
    }

    @Test
    void offNeedsTheHigherThresholdToComeBack() {
        var gate = off();
        gate.hidden(CullGate.ON - 1);
        assertFalse(gate.on());
        gate.hidden(CullGate.ON);
        assertTrue(gate.on());
        assertTrue(gate.cull());
    }

    @Test
    void offAgainStartsAFreshSampleCount() {
        var gate = off();
        for (int i = 0; i < 10; i++) gate.cull();
        gate.hidden(CullGate.ON);
        for (int i = 0; i < CullGate.OFF_AFTER; i++) gate.hidden(0);
        assertFalse(gate.on());
        for (int i = 0; i < CullGate.SAMPLE_EVERY - 1; i++) assertFalse(gate.cull());
        assertTrue(gate.cull());
    }

    @Test
    void thresholdsBracketTheMeasuredBreakEven() {
        long breakEven = 258_000; // the measured fit: 0.228 ms saved per million hidden triangles against 0.059 ms
        assertTrue(CullGate.OFF < breakEven && breakEven < CullGate.ON);
    }

    private static CullGate off() {
        var gate = new CullGate();
        for (int i = 0; i < CullGate.OFF_AFTER; i++) gate.hidden(0);
        assertFalse(gate.on());
        return gate;
    }
}
