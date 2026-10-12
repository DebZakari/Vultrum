package dev.vultrum.cull;

import java.util.function.IntConsumer;

/** A readback slot belongs to its recorded submit until completion, even after the cull is disabled. */
final class CullReadbacks {
    private final long[] submits;

    CullReadbacks(int slots) { submits = new long[slots]; }

    boolean free(int slot) { return submits[slot] == 0; }

    void recorded(int slot, long submit) {
        if (!free(slot) || submit <= 0) throw new IllegalStateException("readback slot is not free or submit is invalid");
        submits[slot] = submit;
    }

    void collect(long completed, IntConsumer read) {
        for (int slot = 0; slot < submits.length; slot++) {
            if (submits[slot] == 0 || submits[slot] > completed) continue;
            read.accept(slot);
            submits[slot] = 0;
        }
    }

    void discard() { java.util.Arrays.fill(submits, 0); }
}
