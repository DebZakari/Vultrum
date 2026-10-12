package dev.vultrum.upload;

import it.unimi.dsi.fastutil.longs.LongCollection;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * Pending initial columns of one renderer generation, used only on the render thread. A reload's ready-column
 * snapshot is registered over several terrain setups; ordinary streaming additions still run immediately.
 * The queue owns only packed positions, never a level, section manager, mesh or GPU buffer.
 */
public final class RegistrationQueue {
    static final long BUDGET_NANOS = 2_000_000L;
    static final int QUEUE_SHARE = 256;

    private final LongLinkedOpenHashSet pending = new LongLinkedOpenHashSet();
    private final LongSupplier clock;
    private final long budgetNanos;

    public RegistrationQueue() {
        this(BUDGET_NANOS, System::nanoTime);
    }

    RegistrationQueue(long budgetNanos, LongSupplier clock) {
        this.budgetNanos = budgetNanos;
        this.clock = clock;
    }

    /** Drops work before the section manager or its level is replaced. */
    public void reset() {
        pending.clear();
    }

    public int pending() {
        return pending.size();
    }

    /** Transfers the initial snapshot and its duplicate load events to this generation's queue. */
    public void seed(LongCollection ready, LongSet loads) {
        pending.clear();
        pending.addAll(ready);
        for (var it = pending.iterator(); it.hasNext();) loads.remove(it.nextLong());
    }

    /**
     * Cancels removals for columns not yet registered. Fresh additions supersede pending registration and stay in
     * Sodium's load queue, so streaming changes use its ordinary callbacks in the current terrain setup.
     */
    public void events(LongSet loads, LongSet unloads) {
        for (var it = unloads.iterator(); it.hasNext();) {
            if (pending.remove(it.nextLong())) it.remove();
        }
        for (var it = loads.iterator(); it.hasNext();) pending.remove(it.nextLong());
    }

    /**
     * Registers still-ready columns until the time budget expires, with at least one and at least 1/256 of the
     * pending queue progressing per setup. This prevents a slow callback from indefinitely holding a large reload.
     * A column's callback is indivisible and can take the batch beyond the budget.
     */
    public int drain(LongCollection ready, LongConsumer add) {
        long start = clock.getAsLong();
        int count = 0, minimum = Math.max(1, pending.size() / QUEUE_SHARE);
        for (var it = pending.iterator(); it.hasNext() && (count < minimum || clock.getAsLong() - start < budgetNanos);) {
            long pos = it.nextLong();
            it.remove();
            if (ready.contains(pos)) {
                add.accept(pos);
                count++;
            }
        }
        return count;
    }
}
