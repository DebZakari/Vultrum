package dev.vultrum.upload;

import dev.vultrum.VultrumConfig;
import dev.vultrum.LaunchHooks;
import dev.vultrum.bench.FrameTimer;
import it.unimi.dsi.fastutil.longs.LongCollection;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * Render-thread column events for one renderer generation. Owns reload registration, event reconciliation and
 * completion, retaining only packed positions. Queues and callbacks are borrowed for each call, never retained.
 */
public final class ColumnLifecycle {
    private final boolean registerReloads, budgetUnloads;
    private final RegistrationQueue registration;
    private final UnloadBudget unloadBudget;
    private final Observer observer;

    @FunctionalInterface
    interface Observer {
        void columns(int added, int removed, int pending);
    }

    public ColumnLifecycle() {
        this(VultrumConfig.get().reloadRegistration(), unloadBudgetOn(), RegistrationQueue.BUDGET_NANOS,
                UnloadBudget.BUDGET_NANOS, System::nanoTime, Client::columns);
    }

    ColumnLifecycle(boolean registerReloads, boolean budgetUnloads, long registrationNanos, long unloadNanos,
                    LongSupplier clock, Observer observer) {
        this.registerReloads = registerReloads;
        this.budgetUnloads = budgetUnloads;
        this.registration = new RegistrationQueue(registrationNanos, clock);
        this.unloadBudget = new UnloadBudget(unloadNanos, clock);
        this.observer = observer;
    }

    /** Before destroying or replacing the manager, invalidate all pending work and the backlog diagnostic. */
    public void reset() {
        registration.reset();
        observer.columns(0, 0, 0);
    }

    /**
     * Sodium has created the new manager. Transfer its ready snapshot or invoke its original initial callbacks.
     * Old removals cannot apply to the new manager; clear them only when unload budgeting can have delayed them.
     */
    public void initialize(LongCollection ready, LongSet loads, LongSet unloads, Runnable sodium) {
        reset();
        if (registerReloads) registration.seed(ready, loads);
        else sodium.run();
        if (budgetUnloads) unloads.clear();
    }

    /**
     * After Sodium's beforeSectionUpdates: reconcile pending registration, remove columns, add every streaming
     * column, then register a reload batch using current readiness. Disabled unload budgeting invokes Sodium's
     * original event dispatcher; neither setting suppresses the other's work.
     */
    public void process(LongCollection ready, LongSet loads, LongSet unloads,
                        LongConsumer load, LongConsumer unload, Runnable sodium) {
        if (registration.pending() != 0) registration.events(loads, unloads);
        int added = loads.size(), removed = unloads.size();
        if (budgetUnloads) removed = unloadBudget.drain(loads, unloads, load, unload);
        else sodium.run();
        observer.columns(added, removed, budgetUnloads ? unloads.size() : 0);
        if (registration.pending() != 0) registration.drain(ready, load);
    }

    /** An empty Sodium build queue is insufficient while initial columns still await registration. */
    public boolean complete(boolean sodium) {
        return sodium && registration.pending() == 0;
    }

    /** Launch configuration and numeric diagnostics only; no renderer, level or callback is held globally. */
    private static final class Client {
        static final boolean UNLOADS = VultrumConfig.get().unloadBudget()
                && LaunchHooks.get().sodium();
        static int pending;

        static void columns(int added, int removed, int waiting) {
            pending = waiting;
            FrameTimer.chunkColumns(added, removed, waiting);
        }
    }

    public static boolean unloadBudgetOn() {
        return Client.UNLOADS;
    }

    /** Latest client backlog, cleared immediately when its renderer generation is discarded. */
    public static int pendingClientUnloads() {
        return Client.pending;
    }
}
