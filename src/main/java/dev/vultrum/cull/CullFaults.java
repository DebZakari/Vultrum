package dev.vultrum.cull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Benchmark-only, one-shot failure injection. A point may be delayed with "point:occurrence". */
final class CullFaults {
    private final String point;
    private int remaining;

    private CullFaults(String point, int remaining) { this.point = point; this.remaining = remaining; }

    static CullFaults read(Path file, boolean benchmark) {
        if (!benchmark || !Files.isRegularFile(file)) return new CullFaults("", 0);
        try {
            String[] parts = Files.readString(file).trim().split(":", 2);
            return new CullFaults(parts[0], parts.length == 1 ? 1 : Math.max(1, Integer.parseInt(parts[1])));
        } catch (IOException | NumberFormatException failure) { throw new IllegalStateException("invalid cull failure fixture", failure); }
    }

    void hit(String at) {
        if (at.equals(point) && remaining > 0 && --remaining == 0)
            throw new IllegalStateException("injected cull failure at " + at);
    }
}
