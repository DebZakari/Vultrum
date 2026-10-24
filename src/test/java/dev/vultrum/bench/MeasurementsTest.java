package dev.vultrum.bench;

import dev.vultrum.bench.FrameTimer.LoopPhase;
import dev.vultrum.bench.FrameTimer.SetupPhase;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Sequences cross the same event/recording interface as the render hooks. */
class MeasurementsTest {
    private final long origin = 1_000_000_000L;
    private long now = origin;
    private final Measurements m = new Measurements(() -> now, () -> 10_000L);
    private record Frame(long id, long window) {}

    private Frame frame(double startMs, double setupMs, double reclaimMs, int refs, double packetMs, double renderMs) {
        now = origin + Math.round((startMs - setupMs - packetMs) * 1e6);
        long packet = m.beginPhase(); now += Math.round(packetMs * 1e6); m.endLoop(LoopPhase.PACKETS, packet);
        m.beginExtract();
        long setup = m.beginSetup(), reclaim = m.beginPhase();
        now += Math.round(reclaimMs * 1e6); m.endPhase(SetupPhase.RECLAIM, reclaim);
        for (int i = 0; i < refs; i++) m.reclaimed();
        now += Math.round((setupMs - reclaimMs) * 1e6);
        m.endSetup(setup); m.endExtract();
        long id = m.beginFrame(), window = m.frameWindow();
        now += Math.round(renderMs * 1e6); m.endFrame();
        return new Frame(id, window);
    }
    private Frame frame(double start, double setup) { return frame(start, setup, 0, 0, 0, .1); }
    private void gpu(Frame f, double ms) { m.gpu(new GpuTimer.Sample(f.id(), f.window(), ms, ms / 2, 0, 0)); }
    private Measurements.Snapshot finish() { m.stopRecording(); assertTrue(m.finishRecording()); return m.recording(); }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Object value) { return (List<Map<String, Object>>) value; }

    @Test
    void completeRecordingPreservesMetricsAndAssociatesOutOfOrderGpuResults() {
        m.startRecording();
        var a = frame(10, .2, 0, 0, .05, .1);
        var b = frame(12, .3, 0, 0, .1, .2);
        var c = frame(32, 16, 15.5, 35000, .15, .3);
        var d = frame(38, 4.99, 4, 9, .2, .4);
        gpu(d, 3.3); gpu(b, 3.1); gpu(c, 3.2); gpu(a, 3);
        var snapshot = finish();
        var result = snapshot.metrics();
        assertEquals(Map.of("count", 3, "mean", 9.3333, "p50", 6.0, "p99", 20.0, "p999", 20.0, "max", 20.0), result.get("frameMs"));
        assertEquals(107.1429, result.get("fpsAvg")); assertEquals(50.0, result.get("fps1Low"));
        assertEquals(.25, snapshot.mean("renderCpuMs"));
        assertEquals(5.3725, snapshot.mean("extractCpuMs"));
        assertEquals(3.15, snapshot.mean("gpuFrameMs"));
        var hitch = map(result.get("hitchBreakdownMs"));
        assertEquals(1, result.get("frameHitches"));
        assertEquals(List.of(10012L), hitch.get("startsEpochMs"));
        assertEquals(.2, hitch.get("render")); assertEquals(16.0, hitch.get("extract"));
        assertEquals(15.5, hitch.get("reclaim")); assertEquals(.15, hitch.get("packets"));
        assertNull(hitch.get("lightQueue"));
        var longSetup = rows(result.get("longSetups")).getFirst();
        assertEquals(1L, result.get("longSetupCount")); assertEquals(10012L, longSetup.get("startEpochMs"));
        assertEquals(20.0, longSetup.get("frame")); assertEquals(35000, longSetup.get("reclaimedRefs"));
        assertEquals(.5, longSetup.get("untimed"));
        assertEquals(List.of(3.0, 3.1, 3.2), longSetup.get("gpuFrames"));
        assertEquals(20.0, snapshot.worstFrame(10012, 10013)); assertNull(snapshot.worstFrame(9990, 9999));
        assertEquals(true, map(result.get("measurement")).get("complete"));
    }

    @Test
    void firstAndLastRendersHaveNoInventedFrameIntervals() {
        m.startRecording();
        var a = frame(10, 5); gpu(a, 3);
        var b = frame(12, 0); gpu(b, 4);
        var result = finish().metrics();
        assertEquals(1, map(result.get("frameMs")).get("count"));
        assertEquals(2, map(result.get("renderCpuMs")).get("count"));
        var first = rows(result.get("longSetups")).getFirst();
        assertNull(first.get("startEpochMs")); assertNull(first.get("frame"));
        assertEquals(Arrays.asList(null, null, 3.0), first.get("gpuFrames"));
    }

    @Test
    void waitsForQueriesThenSealsTimeoutAndKeepsCorrectMissingNeighbors() {
        m.startRecording();
        var a = frame(10, 5);
        var b = frame(20, 6); gpu(b, 4);
        m.stopRecording();
        assertFalse(m.finishRecording()); now += 1_999_999_999L; assertFalse(m.finishRecording());
        now++; assertTrue(m.finishRecording());
        var saved = m.recording();
        var state = map(saved.metrics().get("measurement"));
        assertEquals(false, state.get("complete")); assertEquals(true, state.get("drainTimedOut"));
        assertEquals(1, state.get("gpuMissing"));
        assertEquals(1, map(saved.metrics().get("gpuFrameMs")).get("count"));
        assertEquals(Arrays.asList(null, null, 4.0), rows(saved.metrics().get("longSetups")).getLast().get("gpuFrames"));
        gpu(a, 99);
        assertSame(saved, m.recording());
        assertEquals(4.0, saved.mean("gpuFrameMs"));
    }

    @Test
    void resultArrivingDuringDrainCompletesWithoutTimeout() {
        m.startRecording(); var f = frame(10, 0); m.stopRecording();
        assertFalse(m.finishRecording()); now += 1_000_000_000L;
        gpu(f, 4); assertTrue(m.finishRecording());
        var result = m.recording().metrics();
        assertEquals(true, map(result.get("measurement")).get("complete"));
        assertEquals(false, map(result.get("measurement")).get("drainTimedOut"));
        assertEquals(4.0, m.recording().mean("gpuFrameMs"));
    }

    @Test
    void retryIgnoresOldAndDuplicateCompletionsAndDetachesSnapshots() {
        m.startRecording();
        var old = frame(10, 5);
        m.stopRecording(); now += 2_000_000_000L; assertTrue(m.finishRecording());
        var previous = m.recording();
        m.startRecording();
        var current = frame(3010, 6);
        gpu(old, 90); gpu(current, 7); gpu(current, 90);
        var saved = finish();
        assertEquals(7.0, saved.mean("gpuFrameMs"));
        assertEquals(0, map(saved.metrics().get("measurement")).get("gpuMissing"));
        assertEquals(0, map(previous.metrics().get("gpuFrameMs")).get("count"));
        assertThrows(UnsupportedOperationException.class, () -> saved.metrics().put("new", true));
        assertThrows(UnsupportedOperationException.class, () -> map(saved.metrics().get("gpuFrameMs")).put("mean", 1));
        assertThrows(UnsupportedOperationException.class, () -> rows(saved.metrics().get("longSetups")).clear());
        assertThrows(UnsupportedOperationException.class, () -> rows(saved.metrics().get("longSetups")).getFirst().put("frame", 1));
    }

    @Test
    void invalidOrOverflowedGpuSamplesRemainIneligibleWithoutInventingZeros() {
        for (boolean invalid : new boolean[]{true, false}) {
            m.startRecording(); var a = frame(10, 0);
            m.gpu(new GpuTimer.Sample(a.id(), a.window(), invalid ? Double.NaN : 2, invalid ? Double.NaN : 1, invalid ? 1 : 0, invalid ? 0 : 1));
            var result = finish().metrics();
            assertEquals(false, map(result.get("measurement")).get("complete"));
            assertEquals(false, map(result.get("measurement")).get("drainTimedOut"));
            assertEquals(invalid ? 0 : 1, map(result.get("gpuFrameMs")).get("count"));
        }
    }

    @Test
    void stopBeforeFirstRenderCannotBeAcceptedAsComplete() {
        m.startRecording();
        var result = finish().metrics();
        assertEquals(false, map(result.get("measurement")).get("complete"));
        assertEquals(0, map(result.get("frameMs")).get("count"));
        assertNull(result.get("fpsAvg"));
    }

    @Test
    void pendingPipelineResultsArePartOfCompletionAndRetainWindowIdentity() {
        m.statisticsAvailable(true); m.startRecording();
        var old = frame(10, 0); gpu(old, 2);
        m.stopRecording(); assertFalse(m.finishRecording());
        now += 2_000_000_000L; assertTrue(m.finishRecording());
        assertEquals(1, map(m.recording().metrics().get("measurement")).get("pipelineMissing"));
        m.startRecording(); var current = frame(3010, 0); gpu(current, 3);
        m.pipeline(new PipelineStats.Sample(old.id(), old.window(), List.of(new PipelineStats.Call(0, "SOLID", new long[]{99,99,99,99,99})), 0));
        m.pipeline(new PipelineStats.Sample(current.id(), current.window(), List.of(new PipelineStats.Call(0, "SOLID", new long[]{1,2,3,4,5})), 0));
        var result = finish().metrics();
        var row = rows(result.get("terrainStats")).getFirst();
        assertEquals(1L, row.get("iaPrimitives")); assertEquals(5L, row.get("fsInvocations"));
        assertEquals(true, map(result.get("measurement")).get("complete"));
    }

    @Test
    void loadMarkCutsSamplesAndPendingCountAtTheStartOfTheCompleteStreak() {
        frame(10, 0); m.startLoad();
        frame(20, 0); // interval already under way when loading began is skipped
        m.columns(0, 0, 5); frame(30, 1, .5, 0, .3, .1);
        var mark = m.markLoad();
        m.columns(0, 0, 99); frame(40, 2, 1, 0, .9, .1);
        assertEquals(1, m.framesSince(mark));
        var load = m.finishLoad(mark);
        assertEquals(1, map(load.get("frameMs")).get("count"));
        assertEquals(10.0, map(load.get("frameMs")).get("mean"));
        assertEquals(.3, map(load.get("packetsMs")).get("mean"));
        assertEquals(.5, map(load.get("reclaimMs")).get("mean"));
        assertEquals(5, load.get("columnsPendingMax"));
        m.startLoad();
        assertThrows(IllegalArgumentException.class, () -> m.framesSince(mark));
        frame(50, 0); frame(60, 0);
        var timeout = m.finishLoad(null);
        assertEquals(1, map(timeout.get("frameMs")).get("count"));
        assertEquals(1, map(load.get("frameMs")).get("count"));
    }

    @Test
    void longSetupLimitKeepsLongestThenChronologicalWithStableTies() {
        m.startRecording();
        for (int i = 0; i < 44; i++) {
            double setup = i < 8 ? 6 : i == 43 ? 20 : 9;
            var f = frame(30 + i * 30, setup); gpu(f, 2);
        }
        var result = finish().metrics();
        assertEquals(44L, result.get("longSetupCount"));
        var rows = rows(result.get("longSetups"));
        assertEquals(32, rows.size());
        assertEquals(9.0, rows.getFirst().get("terrainSetup"));
        assertEquals(10240L, rows.getFirst().get("startEpochMs")); // setup 8 ends interval starting at render 7
        assertEquals(20.0, rows.getLast().get("terrainSetup"));
    }
}
