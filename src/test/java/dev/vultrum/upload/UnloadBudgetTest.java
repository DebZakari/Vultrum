package dev.vultrum.upload;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import org.junit.jupiter.api.Test;

import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;

class UnloadBudgetTest {
    private static final long US = 1_000L;

    /** A clock that moves on only while a column is removed, by {@link #cost} each. */
    private long now, cost = 40 * US;
    private final LongList removed = new LongArrayList();
    private final UnloadBudget budget = new UnloadBudget(250 * US, () -> now);

    private void remove(long pos) {
        removed.add(pos);
        now += cost;
    }

    private static LongSet columns(long first, int count) {
        return new LongOpenHashSet(LongStream.range(first, first + count).toArray());
    }

    @Test
    void removesWhatFitsInTheBudget() {
        cost = 10 * US;
        LongSet queue = columns(0, 3);
        assertEquals(3, budget.drain(queue, this::remove));
        assertTrue(queue.isEmpty());
        assertEquals(columns(0, 3), new LongOpenHashSet(removed));
    }

    @Test
    void leavesTheRestQueuedOnceTheBudgetIsSpent() {
        LongSet queue = columns(0, 20);
        assertEquals(7, budget.drain(queue, this::remove), "the seventh column starts at 240 µs, under the budget");
        assertEquals(13, queue.size());
        for (long pos : removed) assertFalse(queue.contains(pos), "a removed column leaves the queue");
        assertEquals(7, budget.drain(queue, this::remove), "the next frame goes on");
        assertEquals(6, queue.size());
        assertEquals(14, new LongOpenHashSet(removed).size(), "no column is removed twice");
    }

    @Test
    void removesAColumnEvenWhenItOverruns() {
        cost = 5_000 * US;
        LongSet queue = columns(0, 5);
        assertEquals(1, budget.drain(queue, this::remove));
        assertEquals(4, queue.size());
    }

    @Test
    void anEmptyQueueRemovesNothing() {
        assertEquals(0, budget.drain(columns(0, 0), this::remove));
        assertTrue(removed.isEmpty());
    }

    @Test
    void aLongQueueLosesItsShareWhateverTheBudget() {
        assertEquals(1, UnloadBudget.atLeast(0));
        assertEquals(1, UnloadBudget.atLeast(511));
        assertEquals(2, UnloadBudget.atLeast(512));
        assertEquals(16, UnloadBudget.atLeast(4225), "the view disc at RD 32");
        cost = 5_000 * US;
        LongSet queue = columns(0, 1024);
        assertEquals(4, budget.drain(queue, this::remove));
        assertEquals(1020, queue.size());
    }

    @Test
    void addsEveryColumnAfterTheRemovalsWhateverTheBudget() {
        cost = 5_000 * US;
        LongSet loads = columns(100, 30), unloads = columns(0, 5);
        LongList order = new LongArrayList();
        assertEquals(1, budget.drain(loads, unloads, order::add, pos -> { order.add(pos); remove(pos); }));
        assertEquals(4, unloads.size(), "the other removals wait");
        assertTrue(loads.isEmpty(), "no addition waits");
        assertEquals(removed, order.subList(0, 1), "the removal comes first");
        assertEquals(columns(100, 30), new LongOpenHashSet(order.subList(1, order.size())));
        assertEquals(30, order.size() - 1, "no column is added twice");
    }

    @Test
    void anAdditionNeedsNoRemoval() {
        LongSet loads = columns(100, 3), unloads = columns(0, 0);
        LongList added = new LongArrayList();
        assertEquals(0, budget.drain(loads, unloads, added::add, this::remove));
        assertEquals(columns(100, 3), new LongOpenHashSet(added));
        assertTrue(loads.isEmpty());
    }

    @Test
    void theQueueStaysBoundedWhenFramesAreSlow() {
        cost = 5_000 * US;
        LongSet queue = columns(0, 0);
        int arriving = 10, most = 0;
        for (int frame = 0; frame < 5_000; frame++) {
            queue.addAll(columns(1_000_000L + (long) frame * arriving, arriving));
            budget.drain(queue, this::remove);
            most = Math.max(most, queue.size());
        }
        assertTrue(most > 7 * arriving, "the budget alone would fall behind: " + most);
        assertTrue(most <= (arriving + 1) * UnloadBudget.QUEUE_SHARE, "but the queue's share keeps up: " + most);
    }
}
