package dev.vultrum.bench;

import com.mojang.renderpearl.api.commands.RenderPass;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Per-frame CPU and GPU timing, called from the render-thread mixins. Inactive (one boolean check)
 * unless the benchmark enabled it. Samples are kept only while recording.
 *
 * <p>{@code Minecraft.renderFrame} calls {@code GameRenderer.extract} (where Sodium runs
 * {@code setupTerrain}) before {@code GameRenderer.render}, so the extract and setup times that
 * {@link #endFrame} records belong to the extract that preceded that render.
 */
public final class FrameTimer {
    /** Steps of Sodium's {@code setupTerrain}, each timed around the call that runs it. */
    public enum SetupPhase {
        CHUNK_EVENTS("chunkEvents"), SORT_TRIGGER("sortTrigger"), CULL_TREES("cullTrees"),
        SCHEDULE("schedule"), UPLOAD("upload"), RENDER_LISTS("renderLists"), RECLAIM("reclaim"), CLEANUP("cleanup");

        final String key;

        SetupPhase(String key) { this.key = key; }
    }

    /** Steps of Minecraft's frame loop between one render and the next extract, each timed around its call. */
    public enum LoopPhase {
        PACKETS("packets"), TASKS("tasks"), TICK("tick"), ACQUIRE("acquire"), LIGHT_QUEUE("lightQueue"),
        LIGHT_ENGINE("lightEngine"), SUBMIT("submit"), PRESENT("present");

        final String key;

        LoopPhase(String key) { this.key = key; }
    }

    private static boolean enabled;
    private static boolean recording;
    private static GpuTimer gpu;
    private static PipelineStats stats;
    private static boolean statsTried;
    private static long frame, lastStart, renderStart, extractStart, extractNs, setupNs, drawNs, lateListsNs;
    private static boolean inSetup;

    static final DoubleArrayList frameMs = new DoubleArrayList();
    /** Wall-clock start of each frameMs interval, in epoch milliseconds, to line a hitch up with a JFR recording. */
    static final DoubleArrayList frameStartEpochMs = new DoubleArrayList();
    private static double epochOffsetMs;
    static final DoubleArrayList renderCpuMs = new DoubleArrayList();
    static final DoubleArrayList extractCpuMs = new DoubleArrayList();
    static final DoubleArrayList setupCpuMs = new DoubleArrayList();
    static final DoubleArrayList drawCpuMs = new DoubleArrayList();
    static final DoubleArrayList gpuFrameMs = new DoubleArrayList();
    static final DoubleArrayList gpuTerrainMs = new DoubleArrayList();
    /** Sodium's finalizeRenderLists called outside setupTerrain: under Vitrail, its shadow walk. */
    static final DoubleArrayList lateListsCpuMs = new DoubleArrayList();
    private static final long[] phaseNs = new long[SetupPhase.values().length];
    static final DoubleArrayList[] setupPhaseMs = new DoubleArrayList[SetupPhase.values().length];
    static { for (int i = 0; i < setupPhaseMs.length; i++) setupPhaseMs[i] = new DoubleArrayList(); }
    private static final long[] loopNs = new long[LoopPhase.values().length];
    /** Recorded with the render that follows them, like the extract. */
    static final DoubleArrayList[] loopPhaseMs = new DoubleArrayList[LoopPhase.values().length];
    static { for (int i = 0; i < loopPhaseMs.length; i++) loopPhaseMs[i] = new DoubleArrayList(); }
    /**
     * Whether each loop phase's wrap has run at least once. Every phase gets a sample every frame, so an optional
     * wrap that didn't apply (the light phases') would otherwise read as 0 ms.
     */
    private static final boolean[] loopPhaseTimed = new boolean[LoopPhase.values().length];
    /**
     * While a load (a join or a teleport) is timed, apart from recording: frame intervals, the packets phase and
     * the chunk events and reclaim phases, one of each a render. The render under way when the load starts is left
     * out of all.
     */
    private static boolean loading, loadSkip, loadFrame;
    static final DoubleArrayList loadFrameMs = new DoubleArrayList();
    static final DoubleArrayList loadPacketsMs = new DoubleArrayList();
    static final DoubleArrayList loadChunkEventsMs = new DoubleArrayList();
    static final DoubleArrayList loadReclaimMs = new DoubleArrayList();
    /** The most columns a drain of Sodium's chunk events left waiting to be removed during the load. */
    static int loadColumnsPendingMax;
    /** Sodium's chunk events each frame, in columns added and removed, and the columns left waiting to be removed after them. */
    private static int columnsAdded, columnsRemoved, columnsPending;
    static final DoubleArrayList addedColumns = new DoubleArrayList(), removedColumns = new DoubleArrayList(), pendingColumns = new DoubleArrayList();
    /**
     * The buffer references Sodium's NativeBuffer.reclaim polled each frame: one for each NativeBuffer the GC
     * collected, freed or not, so a GC cycle queues thousands at once.
     */
    private static int reclaimed;
    static final DoubleArrayList reclaimedRefs = new DoubleArrayList();

    private FrameTimer() { }

    static void enable() {
        enabled = true;
        epochOffsetMs = System.currentTimeMillis() - System.nanoTime() / 1e6;
    }

    /** A System.nanoTime stamp in epoch milliseconds, on the same clock as frameStartEpochMs. */
    static double epochMs(long nanos) { return epochOffsetMs + nanos / 1e6; }

    static void startRecording() {
        for (var list : new DoubleArrayList[] {frameMs, frameStartEpochMs, renderCpuMs, extractCpuMs, setupCpuMs, drawCpuMs, gpuFrameMs, gpuTerrainMs, lateListsCpuMs,
                addedColumns, removedColumns, pendingColumns, reclaimedRefs}) list.clear();
        columnsAdded = columnsRemoved = 0;
        reclaimed = 0;
        for (var list : setupPhaseMs) list.clear();
        for (var list : loopPhaseMs) list.clear();
        if (gpu != null) { gpu.dropped = 0; gpu.overflowed = 0; }
        if (stats != null) stats.clear();
        lastStart = 0;
        epochOffsetMs = System.currentTimeMillis() - System.nanoTime() / 1e6;
        extractNs = setupNs = drawNs = lateListsNs = 0;
        Arrays.fill(phaseNs, 0);
        // Not loopNs: this runs inside the client tick, a loop phase, and endFrame already cleared what came
        // before. The first render's sample then holds its whole loop: the last submit and present, packets, tasks.
        recording = true;
    }

    static void stopRecording() { recording = false; }

    static void startLoad() {
        loadFrameMs.clear();
        loadPacketsMs.clear();
        loadChunkEventsMs.clear();
        loadReclaimMs.clear();
        loadColumnsPendingMax = 0;
        // The frame under way started before the load did.
        loadSkip = true;
        loading = true;
    }

    static void stopLoad() { loading = false; }

    /** True once every frame that started while recording has delivered its GPU sample. */
    static boolean drained() {
        if (stats != null) stats.collect();
        boolean statsDrained = stats == null || !stats.hasPendingRecorded();
        if (gpu == null) return statsDrained;
        gpu.collect(FrameTimer::accept);
        return !gpu.hasPendingRecorded() && statsDrained;
    }

    /** Per-frame pipeline statistics of each terrain draw call, or null unless the run asked for them. */
    static List<Map<String, Object>> terrainStats() { return stats == null ? null : stats.results(); }

    static long gpuDropped() { return gpu == null ? 0 : gpu.dropped; }
    static long gpuOverflowed() { return gpu == null ? 0 : gpu.overflowed; }

    public static void beginFrame() {
        if (!enabled) return;
        long now = System.nanoTime();
        ReloadProbe.beginFrame(now);
        if (recording && lastStart != 0) {
            frameMs.add((now - lastStart) / 1e6);
            frameStartEpochMs.add(epochOffsetMs + lastStart / 1e6);
        }
        loadFrame = loading && !loadSkip && lastStart != 0;
        if (loadFrame) loadFrameMs.add((now - lastStart) / 1e6);
        loadSkip = false;
        lastStart = now;
        renderStart = now;
        if (gpu == null) gpu = new GpuTimer();
        if (!statsTried && VultrumFeatures.statsRequested()) { statsTried = true; stats = PipelineStats.create(); }
        gpu.collect(FrameTimer::accept);
        gpu.beginFrame(++frame, recording);
        if (stats != null) { stats.collect(); stats.beginFrame(frame, recording); }
    }

    public static void endFrame() {
        if (!enabled || gpu == null) return;
        if (stats != null) stats.endFrame();
        gpu.endFrame();
        if (recording) {
            renderCpuMs.add((System.nanoTime() - renderStart) / 1e6);
            extractCpuMs.add(extractNs / 1e6);
            setupCpuMs.add(setupNs / 1e6);
            drawCpuMs.add(drawNs / 1e6);
            lateListsCpuMs.add(lateListsNs / 1e6);
            for (int i = 0; i < phaseNs.length; i++) setupPhaseMs[i].add(phaseNs[i] / 1e6);
            for (int i = 0; i < loopNs.length; i++) loopPhaseMs[i].add(loopNs[i] / 1e6);
            addedColumns.add(columnsAdded);
            removedColumns.add(columnsRemoved);
            pendingColumns.add(columnsPending);
            reclaimedRefs.add(reclaimed);
        }
        columnsAdded = columnsRemoved = 0;
        reclaimed = 0;
        // The packets drained, the chunk events handled and the buffer references reclaimed before this render, in
        // the same frame interval as beginFrame's sample.
        if (loadFrame) {
            loadPacketsMs.add(loopNs[LoopPhase.PACKETS.ordinal()] / 1e6);
            loadChunkEventsMs.add(phaseNs[SetupPhase.CHUNK_EVENTS.ordinal()] / 1e6);
            loadReclaimMs.add(phaseNs[SetupPhase.RECLAIM.ordinal()] / 1e6);
        }
        extractNs = setupNs = drawNs = lateListsNs = 0;
        Arrays.fill(phaseNs, 0);
        Arrays.fill(loopNs, 0);
    }

    public static void beginExtract() { if (enabled) extractStart = System.nanoTime(); }
    public static void endExtract() { if (enabled) extractNs += System.nanoTime() - extractStart; }

    public static long beginTerrainSetup() {
        if (!enabled) return 0;
        inSetup = true;
        return System.nanoTime();
    }

    public static void endTerrainSetup(long start) {
        if (!enabled) return;
        setupNs += System.nanoTime() - start;
        inSetup = false;
    }

    public static long beginSetupPhase() { return enabled ? System.nanoTime() : 0; }
    public static void endSetupPhase(SetupPhase phase, long start) { if (enabled) phaseNs[phase.ordinal()] += System.nanoTime() - start; }

    /** One reference polled by Sodium's NativeBuffer.reclaim, in the setup recorded with the next render. */
    public static void reclaimedRef() { if (enabled) reclaimed++; }

    /** One drain of Sodium's chunk events, in the extract recorded with the next render. */
    public static void chunkColumns(int added, int removed, int pending) {
        if (!enabled) return;
        columnsAdded += added;
        columnsRemoved += removed;
        columnsPending = pending;
        if (loading) loadColumnsPendingMax = Math.max(loadColumnsPendingMax, pending);
    }

    public static long beginLoopPhase() { return enabled ? System.nanoTime() : 0; }
    public static void endLoopPhase(LoopPhase phase, long start) {
        if (!enabled) return;
        loopNs[phase.ordinal()] += System.nanoTime() - start;
        if (phase == LoopPhase.SUBMIT) ReloadProbe.submit(System.nanoTime() - start);
        loopPhaseTimed[phase.ordinal()] = true;
    }

    /** False while the phase's wrap has never run: it didn't apply, or the call never happened. */
    static boolean timed(LoopPhase phase) { return loopPhaseTimed[phase.ordinal()]; }

    /** Every finalizeRenderLists call: the camera's is a setup phase, any other is timed apart. */
    public static void endRenderLists(long start) {
        if (!enabled) return;
        long ns = System.nanoTime() - start;
        if (inSetup) phaseNs[SetupPhase.RENDER_LISTS.ordinal()] += ns;
        else lateListsNs += ns;
    }

    public static long beginTerrainDraw(RenderPass pass, ChunkSectionLayerGroup group) {
        if (!enabled) return 0;
        if (gpu != null) gpu.beginSpan();
        if (stats != null) stats.begin(pass, group.name());
        return System.nanoTime();
    }

    public static void endTerrainDraw(long start) {
        if (!enabled) return;
        drawNs += System.nanoTime() - start;
        if (stats != null) stats.end();
        if (gpu != null) gpu.endSpan();
    }

    private static void accept(GpuTimer.Sample sample) {
        if (!sample.recorded()) return;
        gpuFrameMs.add(sample.frameMs());
        gpuTerrainMs.add(sample.terrainMs());
    }
}
