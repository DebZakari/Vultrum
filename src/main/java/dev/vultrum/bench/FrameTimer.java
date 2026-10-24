package dev.vultrum.bench;

import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import java.util.function.Consumer;

/** Render-thread hook adapter. Dormant unless a benchmark enables measurement. */
public final class FrameTimer {
    public enum SetupPhase {
        CHUNK_EVENTS("chunkEvents"), SORT_TRIGGER("sortTrigger"), CULL_TREES("cullTrees"),
        SCHEDULE("schedule"), UPLOAD("upload"), RENDER_LISTS("renderLists"), RECLAIM("reclaim"), CLEANUP("cleanup");
        final String key;
        SetupPhase(String key) { this.key = key; }
    }
    public enum LoopPhase {
        PACKETS("packets"), TASKS("tasks"), TICK("tick"), ACQUIRE("acquire"), LIGHT_QUEUE("lightQueue"),
        LIGHT_ENGINE("lightEngine"), SUBMIT("submit"), PRESENT("present");
        final String key;
        LoopPhase(String key) { this.key = key; }
    }
    private static Measurements measurements;
    private static GpuTimer gpu;
    private static PipelineStats stats;
    private static boolean statsTried;
    private static Consumer<GpuTimer.Sample> gpuSink;
    private static Consumer<PipelineStats.Sample> pipelineSink;

    private FrameTimer() {}
    static void enable() {
        measurements = new Measurements(System::nanoTime, System::currentTimeMillis);
        gpuSink = measurements::gpu;
        pipelineSink = measurements::pipeline;
    }
    static void startRecording() { measurements.startRecording(); }
    static void stopRecording() { measurements.stopRecording(); }
    static boolean finishRecording() { collect(); return measurements.finishRecording(); }
    static Measurements.Snapshot recording() { return measurements.recording(); }
    static void startLoad() { measurements.startLoad(); }
    static Measurements.LoadMark markLoad() { return measurements.markLoad(); }
    static int framesSince(Measurements.LoadMark mark) { return measurements.framesSince(mark); }
    static java.util.Map<String, Object> finishLoad(Measurements.LoadMark mark) { return measurements.finishLoad(mark); }
    static double epochMs(long nanos) { return measurements.epochMs(nanos); }

    private static void collect() {
        if (gpu != null) gpu.collect(gpuSink);
        if (stats != null) stats.collect(pipelineSink);
    }
    public static void beginFrame() {
        if (measurements == null) return;
        // Preserve render-entry timestamps: query setup/collection belongs inside render CPU time.
        long frame = measurements.beginFrame();
        if (gpu == null) gpu = new GpuTimer();
        if (!statsTried && VultrumFeatures.statsRequested()) { statsTried = true; stats = PipelineStats.create(); }
        measurements.statisticsAvailable(stats != null);
        collect();
        long window = measurements.frameWindow();
        gpu.beginFrame(frame, window, gpuSink);
        if (stats != null) stats.beginFrame(frame, window, pipelineSink);
    }
    public static void endFrame() {
        if (measurements == null || gpu == null) return;
        if (stats != null) stats.endFrame(pipelineSink);
        gpu.endFrame();
        measurements.endFrame();
    }
    public static void beginExtract() { if (measurements != null) measurements.beginExtract(); }
    public static void endExtract() { if (measurements != null) measurements.endExtract(); }
    public static long beginTerrainSetup() { return measurements == null ? 0 : measurements.beginSetup(); }
    public static void endTerrainSetup(long start) { if (measurements != null) measurements.endSetup(start); }
    public static long beginSetupPhase() { return measurements == null ? 0 : measurements.beginPhase(); }
    public static void endSetupPhase(SetupPhase phase, long start) { if (measurements != null) measurements.endPhase(phase, start); }
    public static void reclaimedRef() { if (measurements != null) measurements.reclaimed(); }
    public static void chunkColumns(int added, int removed, int pending) { if (measurements != null) measurements.columns(added, removed, pending); }
    public static long beginLoopPhase() { return measurements == null ? 0 : measurements.beginPhase(); }
    public static void endLoopPhase(LoopPhase phase, long start) { if (measurements != null) measurements.endLoop(phase, start); }
    public static void endRenderLists(long start) { if (measurements != null) measurements.endLists(start); }
    public static long beginTerrainDraw(RenderPass pass, ChunkSectionLayerGroup group) {
        if (measurements == null) return 0;
        if (gpu != null) gpu.beginSpan();
        if (stats != null) stats.begin(pass, group.name());
        return measurements.beginPhase();
    }
    public static void endTerrainDraw(long start) {
        if (measurements == null) return;
        measurements.endDraw(start);
        if (stats != null) stats.end();
        if (gpu != null) gpu.endSpan();
    }
}
