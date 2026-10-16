package dev.vultrum.upload;

import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;

class ColumnLifecycleTest {
    private static LongSet set(long... positions) {
        return new LongLinkedOpenHashSet(positions);
    }

    private static LongSet range(long first, int count) {
        return set(LongStream.range(first, first + count).toArray());
    }

    /** Current tracker queues and manager callbacks, including Sodium's original unbudgeted dispatch. */
    private static final class World {
        final LongSet ready = set(), loads = set(), unloads = set(), active = set();
        final List<String> calls = new ArrayList<>();
        final ColumnLifecycle lifecycle;
        long now, cost = 6;
        int initialCalls, eventCalls, added, removed, pending;

        World(boolean registration, boolean unloadBudget) {
            this(registration, unloadBudget, 10, 5);
        }

        World(boolean registration, boolean unloadBudget, long registrationNanos, long unloadNanos) {
            lifecycle = new ColumnLifecycle(registration, unloadBudget, registrationNanos, unloadNanos, () -> now,
                    (a, r, p) -> { added = a; removed = r; pending = p; });
        }

        void add(long pos) {
            calls.add("+" + pos);
            active.add(pos);
            now += cost;
        }

        void remove(long pos) {
            calls.add("-" + pos);
            active.remove(pos);
            now += cost;
        }

        void initialize() {
            lifecycle.initialize(ready, loads, unloads, () -> {
                initialCalls++;
                ready.forEach((long pos) -> add(pos));
            });
        }

        void process() {
            lifecycle.process(ready, loads, unloads, this::add, this::remove, () -> {
                eventCalls++;
                unloads.forEach((long pos) -> remove(pos));
                unloads.clear();
                loads.forEach((long pos) -> add(pos));
                loads.clear();
            });
        }

        void settle() {
            for (int i = 0; i < 100 && (!lifecycle.complete(true) || !unloads.isEmpty() || !loads.isEmpty()); i++) process();
            assertTrue(lifecycle.complete(true));
            assertTrue(unloads.isEmpty());
            assertTrue(loads.isEmpty());
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void reloadTransfersDuplicatesAndKeepsSettingsIndependent(boolean registration, boolean unloadBudget) {
        var w = new World(registration, unloadBudget);
        w.ready.addAll(set(1, 2, 3, 4));
        w.loads.addAll(set(1, 2, 8));
        w.initialize();
        assertEquals(registration ? 0 : 1, w.initialCalls);
        assertEquals(!registration, w.lifecycle.complete(true));
        assertFalse(w.lifecycle.complete(false), "pending Sodium builds always prevent completion");
        assertEquals(registration ? set(8) : set(1, 2, 8), w.loads);
        w.process();
        assertEquals(unloadBudget ? 0 : 1, w.eventCalls);
        assertTrue(w.loads.isEmpty(), "all streaming additions run immediately");
        assertEquals(!registration, w.lifecycle.complete(true), "budgeted reload still has two columns left");
        w.settle();
        assertEquals(set(1, 2, 3, 4, 8), w.active);
        if (registration) assertEquals(5, w.calls.size(), "transferred load events never register twice");
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void removesThenStreamsThenRegistersReloadAndReportsOnlyEvents(boolean registration, boolean unloadBudget) {
        var w = new World(registration, unloadBudget);
        w.ready.addAll(set(1, 2, 3, 4));
        w.initialize();
        w.calls.clear();
        w.ready.add(8);
        w.loads.add(8);
        w.unloads.addAll(set(90, 91));
        w.process();
        var expected = new ArrayList<>(List.of("-90"));
        if (!unloadBudget) expected.add("-91");
        expected.add("+8");
        if (registration) expected.addAll(List.of("+1", "+2"));
        assertEquals(expected, w.calls);
        assertEquals(1, w.added, "reload registrations are not streaming events");
        assertEquals(unloadBudget ? 1 : 2, w.removed);
        assertEquals(unloadBudget ? 1 : 0, w.pending);
        w.settle();
        assertEquals(set(1, 2, 3, 4, 8), w.active);
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void disappearanceBeforeRegistrationAndReturnUseOriginalCallbacksOnce(boolean registration, boolean unloadBudget) {
        var w = new World(registration, unloadBudget);
        w.ready.addAll(set(1, 2, 3, 4));
        w.initialize();
        w.calls.clear();
        w.ready.remove(1);
        w.unloads.add(1);
        w.process();
        assertEquals(!registration, w.calls.contains("-1"), "never unload a column not yet registered");
        assertFalse(w.active.contains(1));
        w.ready.add(1);
        w.loads.add(1);
        w.process();
        w.settle();
        assertEquals(set(1, 2, 3, 4), w.active);
        assertEquals(1, w.calls.stream().filter("+1"::equals).count());
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void streamingAdditionSupersedesPendingSnapshot(boolean registration, boolean unloadBudget) {
        var w = new World(registration, unloadBudget);
        w.ready.addAll(set(1, 2, 3, 4));
        w.initialize();
        w.calls.clear();
        w.loads.add(3);
        w.process();
        assertEquals("+3", w.calls.getFirst(), "streaming additions precede the reload batch");
        w.settle();
        assertEquals(1, w.calls.stream().filter("+3"::equals).count());
        assertEquals(set(1, 2, 3, 4), w.active);
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void delayedUnloadCancelledByTrackerDoesNotRemoveReturningColumn(boolean registration, boolean unloadBudget) {
        var w = new World(registration, unloadBudget);
        w.ready.addAll(set(1, 2));
        w.initialize();
        w.settle();
        w.calls.clear();
        w.ready.remove(1);
        w.ready.remove(2);
        w.unloads.addAll(set(1, 2));
        w.process();
        w.ready.add(2);
        // Sodium cancels an outstanding removal instead of queueing a redundant addition.
        if (!w.unloads.remove(2)) w.loads.add(2);
        w.process();
        assertEquals(set(2), w.active);
        assertEquals(unloadBudget ? List.of("-1") : List.of("-1", "-2", "+2"), w.calls);
        assertEquals(0, w.pending);
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void staleUnloadsAreClearedOnlyForBudgetedReloads(boolean registration, boolean unloadBudget) {
        var w = new World(registration, unloadBudget);
        w.ready.add(1);
        w.unloads.addAll(set(7, 8));
        w.lifecycle.initialize(w.ready, w.loads, w.unloads, () -> {
            assertEquals(set(7, 8), w.unloads, "cleanup follows original initial callbacks");
            w.add(1);
        });
        assertEquals(unloadBudget ? set() : set(7, 8), w.unloads);
        // A stale removal must not cancel this column's next load in the tracker.
        if (unloadBudget) {
            assertFalse(w.unloads.remove(7));
            w.loads.add(7);
            w.ready.add(7);
        }
        w.settle();
        assertEquals(unloadBudget ? set(1, 7) : set(1), w.active);
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void replacementImmediatelyInvalidatesOldRegistrationAndDiagnostics(boolean registration, boolean unloadBudget) {
        var w = new World(registration, unloadBudget);
        w.ready.addAll(set(1, 2, 3, 4));
        w.initialize();
        w.unloads.addAll(set(90, 91));
        w.process();
        assertEquals(unloadBudget ? 1 : 0, w.pending);
        w.lifecycle.reset();
        assertTrue(w.lifecycle.complete(true));
        assertEquals(0, w.pending, "teardown must not leave the last frame's backlog visible");
        // New tracker queues and a new manager contain none of the old generation's state.
        w.ready.clear(); w.loads.clear(); w.unloads.clear(); w.active.clear(); w.calls.clear();
        w.ready.addAll(set(20, 21, 22));
        w.initialize();
        w.settle();
        assertEquals(set(20, 21, 22), w.active);
        assertEquals(List.of("+20", "+21", "+22"), w.calls);
        w.lifecycle.reset();
        w.process();
        assertEquals(3, w.calls.size(), "discarded generation cannot resume on a later setup");
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void latestReadinessIsCheckedAndReseedingReplacesPendingWork(boolean registration, boolean unloadBudget) {
        var w = new World(registration, unloadBudget);
        w.ready.addAll(set(1, 2, 3, 4));
        w.initialize();
        w.ready.clear();
        w.process();
        assertTrue(w.lifecycle.complete(true));
        if (registration) assertTrue(w.calls.isEmpty(), "no callback for a snapshot column no longer ready");
        w.calls.clear(); w.active.clear();
        w.ready.addAll(set(10, 11, 12, 13));
        w.initialize();
        w.ready.clear(); w.ready.add(40); w.active.clear(); w.calls.clear();
        w.initialize();
        w.settle();
        assertEquals(List.of("+40"), w.calls);
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void dispatchFailurePropagatesWithoutFallbackOrLaterRegistration(boolean registration, boolean unloadBudget) {
        var w = new World(registration, unloadBudget);
        w.ready.addAll(set(1, 2));
        w.initialize(); w.calls.clear();
        w.loads.add(8);
        var failure = new IllegalStateException("callback failed");
        assertSame(failure, assertThrows(IllegalStateException.class, () ->
                w.lifecycle.process(w.ready, w.loads, w.unloads,
                        pos -> { throw failure; }, w::remove, () -> { throw failure; })));
        assertTrue(w.calls.isEmpty());
        assertEquals(!registration, w.lifecycle.complete(true));
    }

    @Test
    void productionUnloadBudgetStopsAtBoundaryAndResumesWithoutDuplicateCallbacks() {
        var w = new World(false, true, RegistrationQueue.BUDGET_NANOS, UnloadBudget.BUDGET_NANOS);
        w.cost = 40_000;
        w.initialize();
        w.unloads.addAll(range(0, 20));
        w.process();
        assertEquals(7, w.removed, "seventh column starts at 240us, below the 250us budget");
        assertEquals(13, w.pending);
        w.process();
        assertEquals(7, w.removed);
        assertEquals(6, w.pending);
        w.process();
        assertEquals(6, w.removed);
        assertEquals(0, w.pending);
        assertEquals(20, w.calls.stream().distinct().count());
        assertEquals(20, w.calls.size());
    }

    @Test
    void expensiveColumnsStillMakeMinimumProgressInBothQueues() {
        var w = new World(true, true);
        w.ready.addAll(range(0, 512));
        w.initialize();
        w.unloads.addAll(range(1000, 1024));
        w.process();
        assertEquals(4, w.removed, "at least 1/256 of queued removals");
        assertEquals(1020, w.pending);
        assertEquals(set(0, 1), w.active, "at least 1/256 of initial registration");
        assertFalse(w.lifecycle.complete(true));
    }

    @Test
    void smallQueuesFinishWithinBudgetAndEmptyQueuesDoNothing() {
        var w = new World(true, true);
        w.cost = 1;
        w.ready.addAll(set(1, 2, 3));
        w.initialize();
        w.unloads.addAll(set(90, 91, 92));
        w.process();
        assertEquals(List.of("-90", "-91", "-92", "+1", "+2", "+3"), w.calls);
        assertEquals(3, w.removed);
        assertTrue(w.lifecycle.complete(true));
        w.process();
        assertEquals(0, w.added); assertEquals(0, w.removed); assertEquals(0, w.pending);
        assertEquals(6, w.calls.size());
    }

    @Test
    void unloadBacklogStaysBoundedAndEveryStreamingAdditionRunsEvenWhenSlow() {
        var w = new World(false, true);
        w.initialize();
        int arriving = 10, most = 0;
        for (int frame = 0; frame < 5000; frame++) {
            w.unloads.addAll(range((long) frame * arriving, arriving));
            w.loads.addAll(set(100000));
            w.process();
            assertTrue(w.loads.isEmpty());
            assertEquals("+100000", w.calls.getLast());
            w.calls.clear();
            most = Math.max(most, w.pending);
        }
        assertTrue(most > arriving * 7, "the time budget alone would fall behind");
        assertTrue(most <= (arriving + 1) * 256, "minimum progress bounds the backlog");
    }
}
