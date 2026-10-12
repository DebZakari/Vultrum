package dev.vultrum.upload;

import it.unimi.dsi.fastutil.longs.LongCollection;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/** Pending initial columns of one renderer generation; streaming events take precedence. */
public final class RegistrationQueue {
    private final LongLinkedOpenHashSet pending = new LongLinkedOpenHashSet();
    private final LongSupplier clock;
    private final long budget;

    public RegistrationQueue() { this(2_000_000L, System::nanoTime); }
    RegistrationQueue(long budget, LongSupplier clock) { this.budget = budget; this.clock = clock; }

    public void reset() { pending.clear(); }
    public int pending() { return pending.size(); }
    public void seed(LongCollection ready, LongSet loads) {
        pending.clear(); pending.addAll(ready);
        // These events describe the same initial snapshot, which this queue now owns.
        for (var it = pending.iterator(); it.hasNext();) loads.remove(it.nextLong());
    }
    public void events(LongSet loads, LongSet unloads) {
        for (var it = unloads.iterator(); it.hasNext();) {
            if (pending.remove(it.nextLong())) it.remove();
        }
        for (var it = loads.iterator(); it.hasNext();) pending.remove(it.nextLong());
    }
    public int drain(LongCollection ready, LongConsumer add) {
        long start = clock.getAsLong();
        int count = 0, minimum = Math.max(1, pending.size() / 256);
        for (var it = pending.iterator(); it.hasNext() && (count < minimum || clock.getAsLong() - start < budget);) {
            long pos = it.nextLong(); it.remove();
            if (ready.contains(pos)) { add.accept(pos); count++; }
        }
        return count;
    }
}
