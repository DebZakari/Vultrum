package dev.vultrum.bench;

import dev.vultrum.bench.FrameTimer.LoopPhase;
import dev.vultrum.bench.FrameTimer.SetupPhase;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;

import java.util.*;
import java.util.function.LongSupplier;
import java.util.stream.IntStream;

/**
 * Render-thread measurement windows. Owns frame alignment, asynchronous attribution and completed snapshots.
 * Hooks supply events; callers never index the render/extract arrays or reuse a mutable recording.
 */
final class Measurements {
    private static final long DRAIN_NANOS = 2_000_000_000L;
    private static final double LONG_SETUP_MS = 5;
    private static final int MAX_LONG_SETUPS = 32;
    private final LongSupplier clock, wallMillis;
    private final DoubleArrayList intervals = new DoubleArrayList(), starts = new DoubleArrayList();
    private final DoubleArrayList render = new DoubleArrayList(), extract = new DoubleArrayList(), setup = new DoubleArrayList();
    private final DoubleArrayList draw = new DoubleArrayList(), lateLists = new DoubleArrayList();
    private final DoubleArrayList gpuFrame = new DoubleArrayList(), gpuTerrain = new DoubleArrayList();
    private final DoubleArrayList added = new DoubleArrayList(), removed = new DoubleArrayList(), pending = new DoubleArrayList(), refs = new DoubleArrayList();
    private final DoubleArrayList[] phases = lists(SetupPhase.values().length), loops = lists(LoopPhase.values().length);
    private final long[] phaseNs = new long[phases.length], loopNs = new long[loops.length];
    private final boolean[] loopTimed = new boolean[loops.length];
    private final BitSet gpuDone = new BitSet(), statsDone = new BitSet();
    private final Map<String, double[]> statistics = new LinkedHashMap<>();
    private final DoubleArrayList loadFrames = new DoubleArrayList(), loadPackets = new DoubleArrayList();
    private final DoubleArrayList loadEvents = new DoubleArrayList(), loadReclaim = new DoubleArrayList();
    private boolean recording, stopped, inSetup, loading, skipLoad, loadFrame, statisticsAvailable;
    private long window, frame, frameWindow, firstFrame, lastStart, renderStart, extractStart;
    private long extractNs, setupNs, drawNs, lateNs, started, ended, dropped, overflow, statsDropped;
    private int columnsAdded, columnsRemoved, columnsPending, reclaimed, loadPendingMax;
    private double epochOffset;
    private Snapshot finished;

    Measurements(LongSupplier clock, LongSupplier wallMillis) {
        this.clock = clock;
        this.wallMillis = wallMillis;
        epochOffset = wallMillis.getAsLong() - clock.getAsLong() / 1e6;
    }

    private static DoubleArrayList[] lists(int n) {
        var result = new DoubleArrayList[n];
        Arrays.setAll(result, i -> new DoubleArrayList());
        return result;
    }

    void statisticsAvailable(boolean available) { statisticsAvailable = available; }

    void startRecording() {
        window++;
        for (var list : new DoubleArrayList[]{intervals, starts, render, extract, setup, draw, lateLists,
                gpuFrame, gpuTerrain, added, removed, pending, refs}) list.clear();
        for (var list : phases) list.clear();
        for (var list : loops) list.clear();
        gpuDone.clear();
        statsDone.clear();
        statistics.clear();
        dropped = overflow = statsDropped = 0;
        columnsAdded = columnsRemoved = reclaimed = 0;
        extractNs = setupNs = drawNs = lateNs = 0;
        Arrays.fill(phaseNs, 0);
        // The tick starting a recording is inside a loop phase; retain that phase's whole duration.
        lastStart = 0;
        firstFrame = frame + 1;
        started = clock.getAsLong();
        epochOffset = wallMillis.getAsLong() - started / 1e6;
        recording = true;
        stopped = false;
        finished = null;
    }

    void stopRecording() {
        if (!recording) return;
        recording = false;
        stopped = true;
        ended = clock.getAsLong();
    }

    /** Poll after collecting query completions; a deadline seals missing results rather than waiting forever. */
    boolean finishRecording() {
        if (finished != null) return true;
        if (!stopped) throw new IllegalStateException("Recording has not stopped");
        int expected = gpuFrame.size();
        boolean complete = gpuDone.cardinality() == expected
                && (!statisticsAvailable || statsDone.cardinality() == expected);
        if (!complete && clock.getAsLong() - ended < DRAIN_NANOS) return false;
        finished = snapshot(!complete);
        return true;
    }

    Snapshot recording() {
        if (finished == null) throw new IllegalStateException("Recording has not finished");
        return finished;
    }

    long beginFrame() {
        long now = clock.getAsLong();
        if (recording && lastStart != 0) {
            intervals.add((now - lastStart) / 1e6);
            starts.add(epochOffset + lastStart / 1e6);
        }
        loadFrame = loading && !skipLoad && lastStart != 0;
        if (loadFrame) loadFrames.add((now - lastStart) / 1e6);
        skipLoad = false;
        lastStart = renderStart = now;
        frame++;
        frameWindow = recording ? window : 0;
        if (recording) {
            gpuFrame.add(Double.NaN);
            gpuTerrain.add(Double.NaN);
        }
        return frame;
    }

    long frameWindow() { return frameWindow; }

    void endFrame() {
        if (frameWindow == window && frameWindow != 0 && finished == null) {
            render.add((clock.getAsLong() - renderStart) / 1e6);
            extract.add(extractNs / 1e6);
            setup.add(setupNs / 1e6);
            draw.add(drawNs / 1e6);
            lateLists.add(lateNs / 1e6);
            for (int i = 0; i < phases.length; i++) phases[i].add(phaseNs[i] / 1e6);
            for (int i = 0; i < loops.length; i++) loops[i].add(loopNs[i] / 1e6);
            added.add(columnsAdded);
            removed.add(columnsRemoved);
            pending.add(columnsPending);
            refs.add(reclaimed);
        }
        columnsAdded = columnsRemoved = reclaimed = 0;
        if (loadFrame) {
            loadPackets.add(loopNs[LoopPhase.PACKETS.ordinal()] / 1e6);
            loadEvents.add(phaseNs[SetupPhase.CHUNK_EVENTS.ordinal()] / 1e6);
            loadReclaim.add(phaseNs[SetupPhase.RECLAIM.ordinal()] / 1e6);
        }
        extractNs = setupNs = drawNs = lateNs = 0;
        Arrays.fill(phaseNs, 0);
        Arrays.fill(loopNs, 0);
        frameWindow = 0;
    }

    void beginExtract() { extractStart = clock.getAsLong(); }
    void endExtract() { extractNs += clock.getAsLong() - extractStart; }
    long beginSetup() { inSetup = true; return clock.getAsLong(); }
    void endSetup(long start) { setupNs += clock.getAsLong() - start; inSetup = false; }
    long beginPhase() { return clock.getAsLong(); }
    void endPhase(SetupPhase phase, long start) { phaseNs[phase.ordinal()] += clock.getAsLong() - start; }
    void endLoop(LoopPhase phase, long start) { loopNs[phase.ordinal()] += clock.getAsLong() - start; loopTimed[phase.ordinal()] = true; }
    void endLists(long start) {
        long ns = clock.getAsLong() - start;
        if (inSetup) phaseNs[SetupPhase.RENDER_LISTS.ordinal()] += ns;
        else lateNs += ns;
    }
    void endDraw(long start) { drawNs += clock.getAsLong() - start; }
    void reclaimed() { reclaimed++; }
    void columns(int add, int remove, int waiting) {
        columnsAdded += add;
        columnsRemoved += remove;
        columnsPending = waiting;
        if (loading) loadPendingMax = Math.max(loadPendingMax, waiting);
    }

    void gpu(GpuTimer.Sample sample) {
        int index = index(sample.frame(), sample.window());
        if (index < 0 || gpuDone.get(index)) return;
        gpuDone.set(index);
        gpuFrame.set(index, sample.frameMs());
        gpuTerrain.set(index, sample.terrainMs());
        dropped += sample.dropped();
        overflow += sample.overflowed();
    }

    void pipeline(PipelineStats.Sample sample) {
        int index = index(sample.frame(), sample.window());
        if (index < 0 || statsDone.get(index)) return;
        statsDone.set(index);
        statsDropped += sample.dropped();
        for (var call : sample.calls()) {
            var sum = statistics.computeIfAbsent(call.ordinal() + " " + call.group(), k -> new double[PipelineStats.NAMES.length + 1]);
            for (int i = 0; i < PipelineStats.NAMES.length; i++) sum[i] += call.values()[i];
            sum[PipelineStats.NAMES.length]++;
        }
    }

    private int index(long sampleFrame, long sampleWindow) {
        long index = sampleFrame - firstFrame;
        return sampleWindow == window && sampleWindow != 0 && finished == null && index >= 0 && index < gpuFrame.size()
                ? (int) index : -1;
    }

    void startLoad() {
        loadGeneration++;
        loadFrames.clear(); loadPackets.clear(); loadEvents.clear(); loadReclaim.clear();
        loadPendingMax = 0;
        skipLoad = true;
        loading = true;
    }

    /** A complete-terrain streak starts here; subsequent loads cannot reuse this mark. */
    record LoadMark(int frames, int pending, long generation) {}
    private long loadGeneration;
    LoadMark markLoad() { return new LoadMark(loadFrames.size(), loadPendingMax, loadGeneration); }
    int framesSince(LoadMark mark) { check(mark); return loadFrames.size() - mark.frames(); }
    private void check(LoadMark mark) {
        if (mark.generation() != loadGeneration) throw new IllegalArgumentException("Stale load mark");
    }

    Map<String, Object> finishLoad(LoadMark complete) {
        if (complete != null) check(complete);
        loading = false;
        int n = complete == null ? loadFrames.size() : complete.frames();
        double[] frames = Arrays.copyOf(loadFrames.toDoubleArray(), n);
        var result = new LinkedHashMap<String, Object>();
        var stats = Stats.of(frames);
        result.put("frameMs", stats.toMap());
        result.put("fps1Low", Stats.round(Stats.lowFps(frames, 0.01)));
        result.put("fps01Low", Stats.round(Stats.lowFps(frames, 0.001)));
        result.put("hitches", Stats.hitchFrames(frames, stats.p50()).length);
        result.put("packetsMs", Stats.of(Arrays.copyOf(loadPackets.toDoubleArray(), n)).toMap());
        result.put("chunkEventsMs", Stats.of(Arrays.copyOf(loadEvents.toDoubleArray(), n)).toMap());
        result.put("reclaimMs", Stats.of(Arrays.copyOf(loadReclaim.toDoubleArray(), n)).toMap());
        result.put("columnsPendingMax", complete == null ? loadPendingMax : complete.pending());
        loadGeneration++;
        return freeze(result);
    }

    double epochMs(long nanos) { return epochOffset + nanos / 1e6; }

    private Snapshot snapshot(boolean timeout) {
        double[] frames = intervals.toDoubleArray(), start = starts.toDoubleArray(), setups = setup.toDoubleArray();
        double[] gpu = gpuFrame.toDoubleArray(), ref = refs.toDoubleArray();
        var result = new LinkedHashMap<String, Object>();
        var frameStats = Stats.of(frames);
        result.put("fpsAvg", Stats.round(Stats.averageFps(frames)));
        result.put("fps1Low", Stats.round(Stats.lowFps(frames, 0.01)));
        result.put("fps01Low", Stats.round(Stats.lowFps(frames, 0.001)));
        result.put("frameMs", frameStats.toMap());
        result.put("renderCpuMs", Stats.of(render.toDoubleArray()).toMap());
        result.put("extractCpuMs", Stats.of(extract.toDoubleArray()).toMap());
        result.put("terrainSetupCpuMs", Stats.of(setups).toMap());
        var phaseStats = new LinkedHashMap<String, Object>();
        var phaseValues = new LinkedHashMap<String, double[]>();
        for (var phase : SetupPhase.values()) {
            var values = phases[phase.ordinal()].toDoubleArray();
            phaseValues.put(phase.key, values);
            phaseStats.put(phase.key, Stats.of(values).toMap());
        }
        result.put("terrainSetupPhasesMs", phaseStats);
        result.put("reclaimedRefs", Map.of("total", (long) Arrays.stream(ref).sum(), "max", (long) Arrays.stream(ref).max().orElse(0),
                "frames", Arrays.stream(ref).filter(count -> count > 0).count()));
        result.put("longSetupCount", Arrays.stream(setups).filter(ms -> ms >= LONG_SETUP_MS).count());
        result.put("longSetups", longSetups(frames, start, setups, phaseValues, ref, gpu));
        var loopStats = new LinkedHashMap<String, Object>();
        for (var phase : LoopPhase.values())
            loopStats.put(phase.key, loopTimed[phase.ordinal()] ? Stats.of(loops[phase.ordinal()].toDoubleArray()).toMap() : null);
        result.put("frameLoopPhasesMs", loopStats);
        var hitch = hitchBreakdown(frames, start, frameStats.p50());
        result.put("frameHitches", hitch.get("count"));
        result.put("hitchBreakdownMs", hitch);
        result.put("chunkColumns", Map.of("added", Stats.of(added.toDoubleArray()).toMap(),
                "removed", Stats.of(removed.toDoubleArray()).toMap(), "pending", Stats.of(pending.toDoubleArray()).toMap()));
        result.put("terrainDrawCpuMs", Stats.of(draw.toDoubleArray()).toMap());
        result.put("lateRenderListsCpuMs", Stats.of(lateLists.toDoubleArray()).toMap());
        result.put("gpuFrameMs", Stats.of(finite(gpu)).toMap());
        result.put("gpuTerrainMs", Stats.of(finite(gpuTerrain.toDoubleArray())).toMap());
        result.put("gpuDropped", dropped);
        result.put("gpuSpanOverflow", overflow);
        if (statisticsAvailable) result.put("terrainStats", terrainStats());
        int missing = gpu.length - gpuDone.cardinality(), statsMissing = statisticsAvailable ? gpu.length - statsDone.cardinality() : 0;
        boolean complete = !timeout && missing == 0 && statsMissing == 0 && dropped == 0 && overflow == 0 && statsDropped == 0
                && render.size() == gpu.length && frames.length == Math.max(0, render.size() - 1) && !render.isEmpty();
        result.put("measurement", Map.of("complete", complete, "drainTimedOut", timeout, "gpuExpected", gpu.length,
                "gpuReceived", gpuDone.cardinality(), "gpuMissing", missing, "pipelineMissing", statsMissing, "pipelineDropped", statsDropped,
                "pipelineExpected", statisticsAvailable ? gpu.length : 0, "pipelineReceived", statsDone.cardinality()));
        return new Snapshot(freeze(result), frames, start, started, ended);
    }

    private static double[] finite(double[] values) { return Arrays.stream(values).filter(Double::isFinite).toArray(); }

    private List<Map<String, Object>> terrainStats() {
        var result = new ArrayList<Map<String, Object>>();
        for (var entry : statistics.entrySet()) {
            var row = new LinkedHashMap<String, Object>();
            var key = entry.getKey().split(" ", 2);
            row.put("call", Integer.parseInt(key[0])); row.put("group", key[1]);
            for (int i = 0; i < PipelineStats.NAMES.length; i++)
                row.put(PipelineStats.NAMES[i], Math.round(entry.getValue()[i] / entry.getValue()[PipelineStats.NAMES.length]));
            row.put("frames", (long) entry.getValue()[PipelineStats.NAMES.length]);
            result.add(row);
        }
        return result;
    }

    /** Interval i contains render i, then the loop and extract/setup preceding render i+1. */
    private Map<String, Object> hitchBreakdown(double[] frames, double[] start, double median) {
        int[] hitches = Arrays.stream(Stats.hitchFrames(frames, median)).filter(i -> i + 1 < extract.size()).toArray();
        var result = new LinkedHashMap<String, Object>();
        result.put("count", hitches.length);
        result.put("startsEpochMs", Arrays.stream(hitches).mapToObj(i -> Math.round(start[i])).toList());
        result.put("framesMs", Arrays.stream(hitches).mapToObj(i -> Stats.round(frames[i])).toList());
        result.put("frame", Stats.round(Stats.meanAt(frames, hitches, 0)));
        result.put("render", mean(render, hitches, 0)); result.put("terrainDraw", mean(draw, hitches, 0));
        result.put("lateRenderLists", mean(lateLists, hitches, 0)); result.put("extract", mean(extract, hitches, 1));
        result.put("terrainSetup", mean(setup, hitches, 1));
        for (var phase : SetupPhase.values()) result.put(phase.key, mean(phases[phase.ordinal()], hitches, 1));
        for (var phase : LoopPhase.values())
            result.put(phase.key, loopTimed[phase.ordinal()] ? mean(loops[phase.ordinal()], hitches, 1) : null);
        result.put("columnsAdded", mean(added, hitches, 1)); result.put("columnsRemoved", mean(removed, hitches, 1));
        result.put("columnsPending", mean(pending, hitches, 1));
        return result;
    }

    private static Object mean(DoubleArrayList values, int[] indices, int offset) {
        return Stats.round(Stats.meanAt(values.toDoubleArray(), indices, offset));
    }

    private static List<Map<String, Object>> longSetups(double[] frames, double[] starts, double[] setup,
            Map<String, double[]> phases, double[] refs, double[] gpu) {
        int[] longest = IntStream.range(0, setup.length).filter(j -> setup[j] >= LONG_SETUP_MS).boxed()
                .sorted(Comparator.comparingDouble((Integer j) -> -setup[j])).limit(MAX_LONG_SETUPS).mapToInt(Integer::intValue).sorted().toArray();
        var list = new ArrayList<Map<String, Object>>();
        for (int j : longest) {
            var row = new LinkedHashMap<String, Object>();
            boolean inFrame = j >= 1 && j - 1 < frames.length;
            row.put("startEpochMs", inFrame ? Math.round(starts[j - 1]) : null);
            row.put("frame", inFrame ? Stats.round(frames[j - 1]) : null);
            row.put("terrainSetup", Stats.round(setup[j]));
            double timed = 0;
            for (var phase : phases.entrySet()) { timed += phase.getValue()[j]; row.put(phase.getKey(), Stats.round(phase.getValue()[j])); }
            row.put("untimed", Stats.round(setup[j] - timed)); row.put("reclaimedRefs", (int) refs[j]);
            var neighbors = new ArrayList<Object>();
            for (int k = j - 2; k <= j; k++) neighbors.add(k >= 0 && k < gpu.length ? Stats.round(gpu[k]) : null);
            row.put("gpuFrames", neighbors);
            list.add(row);
        }
        return list;
    }

    /** Immutable, detached result; no mutable sample arrays cross the module's interface. */
    static final class Snapshot {
        private final Map<String, Object> metrics;
        private final double[] frames, starts;
        private final long startNanos, endNanos;
        private Snapshot(Map<String, Object> metrics, double[] frames, double[] starts, long startNanos, long endNanos) {
            this.metrics = metrics; this.frames = frames; this.starts = starts;
            this.startNanos = startNanos; this.endNanos = endNanos;
        }
        Map<String, Object> metrics() { return metrics; }
        Object mean(String metric) { return ((Map<?, ?>) metrics.get(metric)).get("mean"); }
        long startNanos() { return startNanos; }
        long endNanos() { return endNanos; }
        Double worstFrame(double fromEpochMs, double toEpochMs) {
            double worst = -1;
            for (int i = 0; i < frames.length; i++)
                if (starts[i] < toEpochMs && starts[i] + frames[i] > fromEpochMs) worst = Math.max(worst, frames[i]);
            return worst < 0 ? null : Math.round(worst * 100) / 100.0;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T freeze(T value) {
        if (value instanceof Map<?, ?> map) {
            var copy = new LinkedHashMap<Object, Object>();
            map.forEach((k, v) -> copy.put(k, freeze(v)));
            return (T) Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            var copy = new ArrayList<Object>();
            list.forEach(item -> copy.add(freeze(item)));
            return (T) Collections.unmodifiableList(copy);
        }
        return value;
    }
}
