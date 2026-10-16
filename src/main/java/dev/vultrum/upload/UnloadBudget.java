package dev.vultrum.upload;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * Spreads Sodium's chunk removals over several frames. Sodium queues a column for its render graph once the column
 * and its 8 neighbours have block and light data, queues it for removal once one of them loses its data, and
 * drains both queues in full at the start of the next terrain setup. Removals come in bursts: a row of the view
 * disc at once in a flight (25–65 columns in a hitch frame at RD 32, at 35–40 µs each), and the
 * whole old disc after a long teleport (37–114 ms in one frame at RD 32).
 *
 * <p>Here a frame removes columns until {@link #BUDGET_NANOS} have passed and leaves the rest queued. It removes
 * at least one, and at least 1/{@link #QUEUE_SHARE} of the queue, so the queue stays bounded however slow the
 * frames are. Sodium's tracker drops a queued removal when its column becomes ready again, so a late removal stays
 * correct. A column waiting for removal keeps its meshes, and their video memory, until it goes. A reload drops
 * the removals still queued through {@link ColumnLifecycle}: Sodium's new section manager holds none of their columns.
 *
 * <p>Additions aren't budgeted. They come a few a frame, and terrain the player is waiting for shouldn't queue:
 * with additions under the same budget, joins at RD 16 took 0.3–0.7 s longer.
 */
final class UnloadBudget {
    static final long BUDGET_NANOS = 250_000L;
    static final int QUEUE_SHARE = 256;

    private final long budgetNanos;
    private final LongSupplier clock;

    UnloadBudget(long budgetNanos, LongSupplier clock) {
        this.budgetNanos = budgetNanos;
        this.clock = clock;
    }

    /** Removes queued columns until the budget has passed, and at least {@link #atLeast} of them. Returns how many. */
    private int drain(LongSet unloads, LongConsumer unload) {
        int atLeast = atLeast(unloads.size()), count = 0;
        long deadline = clock.getAsLong() + budgetNanos;
        for (LongIterator it = unloads.iterator(); it.hasNext() && (count < atLeast || clock.getAsLong() < deadline); count++) {
            long pos = it.nextLong();
            it.remove();
            unload.accept(pos);
        }
        return count;
    }

    /** One frame's chunk events: removals within the budget, then every addition. Returns how many columns it removed. */
    int drain(LongSet loads, LongSet unloads, LongConsumer load, LongConsumer unload) {
        int removed = drain(unloads, unload);
        for (LongIterator it = loads.iterator(); it.hasNext();) load.accept(it.nextLong());
        loads.clear();
        return removed;
    }

    /** The fewest columns a frame removes from a queue of {@code queued}: one, or the queue's share when that's more. */
    private static int atLeast(int queued) {
        return Math.max(1, queued / QUEUE_SHARE);
    }

}
