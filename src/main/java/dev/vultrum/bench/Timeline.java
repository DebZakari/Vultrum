package dev.vultrum.bench;

import dev.vultrum.LaunchHooks;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Diagnostic event log for a benchmark run started with -Dvultrum.bench.timeline=true: each section build Sodium
 * submits, each section marked dirty and the calls that marked it, each chunk the client loads, drops, adds to or
 * removes from its storage, each light update it applies, and the benchmark's own phases. Streamed to timeline.csv
 * next to results.json, with the callers in timeline-callers.txt. The stack walks cost time: a timeline run is not a
 * timing run. With -Dvultrum.bench.timeline=chunks it logs only the chunks, the builds and the phases, which leaves
 * loads at their speed.
 */
public final class Timeline {
    public static final boolean ON = LaunchHooks.get().timeline() != LaunchHooks.TimelineMode.OFF;
    /** Dirty marks and light too: the timeline's full mode. */
    public static final boolean FULL = LaunchHooks.get().timeline() == LaunchHooks.TimelineMode.FULL;
    private static final long ORIGIN = System.nanoTime();
    private static final StackWalker WALKER = StackWalker.getInstance();
    private static final Map<String, Integer> callers = new LinkedHashMap<>();
    // Written as the events happen, not held: a load at RD 32 makes millions of them. Guarded by Timeline.class.
    private static BufferedWriter csv;
    private static IOException failure;
    // Set while Sodium submits from the walk's deferred list rather than its important sets.
    private static boolean deferred;

    private Timeline() {}

    /** Starts timeline.csv in the run's output directory; a failure is reported by {@link #write}. */
    static void open(Path out) {
        if (!ON) return;
        try {
            Files.createDirectories(out);
            var writer = new BufferedWriter(new OutputStreamWriter(Files.newOutputStream(out.resolve("timeline.csv")), StandardCharsets.UTF_8), 1 << 20);
            writer.write("ms,kind,thread,x,y,z,detail\n");
            synchronized (Timeline.class) { csv = writer; }
        } catch (IOException e) {
            synchronized (Timeline.class) { failure = e; }
        }
    }

    public static void event(String kind, int x, int y, int z, String detail) {
        if (!ON) return;
        String line = String.format(Locale.ROOT, "%.3f,%s,%s,%d,%d,%d,%s\n", (System.nanoTime() - ORIGIN) / 1e6, kind,
                Thread.currentThread().getName().replace(',', ' '), x, y, z, detail);
        synchronized (Timeline.class) {
            if (csv == null) return;
            try {
                csv.write(line);
            } catch (IOException e) {
                failure = e;
                csv = null;
            }
        }
    }

    public static void mark(String what) { event("mark", 0, 0, 0, what); }

    public static void deferred(boolean on) { deferred = on; }

    public static void submitted(int x, int y, int z, int type) {
        if (!ON) return;
        event("submit", x, y, z, type + (deferred ? ";deferred" : ";important"));
    }

    /** A section marked dirty, with the ten calls above Sodium's scheduleRebuildForChunk. */
    public static void dirty(int x, int y, int z, boolean important) {
        if (!FULL) return;
        String caller = WALKER.walk(frames -> frames.skip(3).limit(10)
                .map(f -> f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1) + "." + f.getMethodName())
                .collect(Collectors.joining(" < ")));
        int id;
        synchronized (callers) { id = callers.computeIfAbsent(caller, key -> callers.size()); }
        event("dirty", x, y, z, (important ? "important" : "normal") + ";c" + id);
    }

    /** Flushes timeline.csv and writes timeline-callers.txt, or throws the first write that failed. */
    static void write(Path out) throws IOException {
        if (!ON) return;
        synchronized (Timeline.class) {
            if (failure != null) throw failure;
            if (csv != null) csv.flush();
        }
        List<String> callerLines = new ArrayList<>();
        synchronized (callers) { callers.forEach((caller, id) -> callerLines.add("c" + id + " " + caller)); }
        Files.write(out.resolve("timeline-callers.txt"), callerLines);
    }
}
