package dev.vultrum.cull;

import java.util.Arrays;

/** A prepared range is published only when Sodium's entire preparation returns successfully. */
final class CullPreparation {
    Object context;
    int first, count, maxRegion;
    int[] sections = new int[4096 * 4];
    private boolean collecting, valid, ready;

    void run(boolean enabled, Runnable prepare) {
        context = null;
        first = -1;
        count = 0;
        maxRegion = -1;
        ready = false;
        collecting = valid = enabled;
        boolean success = false;
        try { prepare.run(); success = true; }
        finally {
            collecting = false;
            ready = success && valid && count > 0 && maxRegion >= 0;
            if (!ready) context = null;
        }
    }

    void append(Object drawContext, int command, int size, int region, boolean translucent, int recordedRegion, int[] recorded) {
        if (!collecting || !valid) return;
        // Opaque commands without a region key cannot be distinguished from excluded translucent commands.
        // Keep the entire frame on the original path instead of risking a duplicate or missing recovery draw.
        if (!translucent && region < 0) { valid = false; return; }
        if (command < 0 || size < 0 || (context != null && context != drawContext)) { valid = false; return; }
        context = drawContext;
        if (first < 0) first = command;
        int at = command - first;
        if (at < count || (!translucent && region >= 0 && recorded.length < (long) size * 4)) { valid = false; return; }
        int end = Math.addExact(at, size), words = Math.multiplyExact(end, 4);
        if (words > sections.length) sections = Arrays.copyOf(sections, Math.max(words, sections.length * 2));
        Arrays.fill(sections, count * 4, at * 4, -1);
        if (translucent) Arrays.fill(sections, at * 4, words, -1);
        else {
            CullBatch.copySections(recorded, size, recordedRegion, region, sections, at);
            maxRegion = Math.max(maxRegion, region);
        }
        count = end;
    }

    boolean take() {
        boolean result = ready;
        ready = false;
        return result;
    }

    void discard() { ready = collecting = valid = false; context = null; }
}
