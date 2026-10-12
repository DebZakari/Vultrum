package dev.vultrum.bench;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.Map;

/** Full-run frame and arena destruction timing around renderer reload actions. */
public final class ReloadProbe {
    public static final boolean ENABLED = Boolean.getBoolean("vultrum.reloadProbe");
    private static final Map<GpuBuffer, Long> arenas = new IdentityHashMap<>();
    private static final StringBuilder frames = new StringBuilder("frame,startEpochMs,frameNs,tag,submitNs,rotateNs,bigN,bigBytes,arenaN,arenaBytes,arenaNs,createdN,closedN\n");
    private static final StringBuilder events = new StringBuilder("action,epochMs,tag\n");
    private static final StringBuilder stages = new StringBuilder("stage,startEpochMs,nanos,tag\n");
    public static void stage(String name, long start) { if (ENABLED) stages.append(name).append(',').append(FrameTimer.epochMs(start)).append(',').append(System.nanoTime() - start).append(',').append(tag).append('\n'); }
    private static long frame, lastStart, submitNs, rotateNs, bigN, bigBytes, arenaN, arenaBytes, arenaNs, createdN, closedN;
    private static String tag = "startup", lastTag;

    public static void tag(String value) { if (ENABLED) tag = value; }
    public static void event(String action) {
        if (ENABLED) events.append(action).append(',').append(System.currentTimeMillis()).append(',').append(tag).append('\n');
    }
    public static void beginFrame(long now) {
        if (!ENABLED) return;
        if (lastStart != 0) frames.append(frame).append(',').append(FrameTimer.epochMs(lastStart)).append(',').append(now-lastStart)
                .append(',').append(lastTag).append(',').append(submitNs).append(',').append(rotateNs).append(',').append(bigN)
                .append(',').append(bigBytes).append(',').append(arenaN).append(',').append(arenaBytes).append(',').append(arenaNs)
                .append(',').append(createdN).append(',').append(closedN).append('\n');
        frame++; lastStart = now; lastTag = tag;
        submitNs = rotateNs = bigN = bigBytes = arenaN = arenaBytes = arenaNs = createdN = closedN = 0;
    }
    public static void submit(long nanos) { if (ENABLED) submitNs += nanos; }
    public static void rotate(long nanos) { if (ENABLED) rotateNs += nanos; }
    public static void created(GpuBuffer buffer) {
        if (ENABLED) { arenas.put(buffer, buffer.size()); createdN++; }
    }
    public static void closed(GpuBuffer buffer) { if (ENABLED && arenas.containsKey(buffer)) closedN++; }
    public static void destroyed(GpuBuffer buffer, long nanos) {
        if (!ENABLED) return;
        if (buffer.size() >= (16L << 20)) { bigN++; bigBytes += buffer.size(); }
        Long bytes = arenas.remove(buffer);
        if (bytes != null) { arenaN++; arenaBytes += bytes; arenaNs += nanos; }
    }
    public static void write(Path out) {
        if (!ENABLED) return;
        try {
            Files.writeString(out.resolve("reload-frames.csv"), frames);
            Files.writeString(out.resolve("reload-events.csv"), events);
            Files.writeString(out.resolve("reload-stages.csv"), stages);
        } catch (Exception e) { throw new IllegalStateException("Cannot write reload timing", e); }
    }
}
