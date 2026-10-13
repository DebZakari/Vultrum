package dev.vultrum.bench;

import java.io.IOException;
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
 * submits, each section marked dirty and the calls that marked it, each chunk the client loads or drops, each light
 * update it applies, and the benchmark's own phases. Written as timeline.csv next to results.json, with the callers
 * in timeline-callers.txt. The stack walks cost time: a timeline run is not a timing run.
 */
public final class Timeline {
    public static final boolean ON = System.getProperty("vultrum.bench") != null && Boolean.getBoolean("vultrum.bench.timeline");
    private static final long ORIGIN = System.nanoTime();
    private static final StackWalker WALKER = StackWalker.getInstance();
    private static final List<String> events = new ArrayList<>();
    private static final Map<String, Integer> callers = new LinkedHashMap<>();
    // Set while Sodium submits from the walk's deferred list rather than its important sets.
    private static boolean deferred;

    private Timeline() {}

    public static void event(String kind, int x, int y, int z, String detail) {
        if (!ON) return;
        String line = String.format(Locale.ROOT, "%.3f,%s,%s,%d,%d,%d,%s", (System.nanoTime() - ORIGIN) / 1e6, kind,
                Thread.currentThread().getName().replace(',', ' '), x, y, z, detail);
        synchronized (events) { events.add(line); }
    }

    public static void mark(String what) { event("mark", 0, 0, 0, what); }

    public static void deferred(boolean on) { deferred = on; }

    public static void submitted(int x, int y, int z, int type) {
        if (!ON) return;
        event("submit", x, y, z, type + (deferred ? ";deferred" : ";important"));
    }

    /** A section marked dirty, with the ten calls above Sodium's scheduleRebuildForChunk. */
    public static void dirty(int x, int y, int z, boolean important) {
        if (!ON) return;
        String caller = WALKER.walk(frames -> frames.skip(3).limit(10)
                .map(f -> f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1) + "." + f.getMethodName())
                .collect(Collectors.joining(" < ")));
        int id;
        synchronized (callers) { id = callers.computeIfAbsent(caller, key -> callers.size()); }
        event("dirty", x, y, z, (important ? "important" : "normal") + ";c" + id);
    }

    static void write(Path out) throws IOException {
        if (!ON) return;
        List<String> lines = new ArrayList<>();
        lines.add("ms,kind,thread,x,y,z,detail");
        synchronized (events) { lines.addAll(events); }
        Files.write(out.resolve("timeline.csv"), lines);
        List<String> callerLines = new ArrayList<>();
        synchronized (callers) { callers.forEach((caller, id) -> callerLines.add("c" + id + " " + caller)); }
        Files.write(out.resolve("timeline-callers.txt"), callerLines);
    }
}
