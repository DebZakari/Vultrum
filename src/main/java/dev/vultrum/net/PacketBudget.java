package dev.vultrum.net;

import dev.vultrum.VultrumConfig;
import net.minecraft.client.Minecraft;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Spreads a burst of packets over several frames: the client stops handling its queued packets once
 * {@link #BUDGET_NANOS} of the frame's packet work has passed, and goes on at the next frame. A backlog that has
 * waited {@link #MAX_WAIT_NANOS} is drained in full, so the budget never handles fewer packets than vanilla
 * over that span. Measured on flights at RD 32: 37–84% fewer hitches, and on a warm GPU the cap
 * forced at most one drain a case.
 *
 * <p>Packets keep their order, so the client behaves as it would on a slower frame, which vanilla already
 * copes with. Its handlers schedule no main-thread work outside this queue that a held packet could fall
 * behind. Vanilla times chunk batches on the network thread ({@code handleChunkBatchStart}), so holding packets
 * back doesn't slow the server's sending. Only the client's queue is budgeted: the integrated server drains
 * its own on its own thread.
 */
public final class PacketBudget {
    static final long BUDGET_NANOS = 1_000_000L, MAX_WAIT_NANOS = 50_000_000L;
    private static final long NONE = Long.MIN_VALUE;

    private final long budgetNanos;
    private long deadline, waitingSince = NONE;
    private boolean forced, stopped, handling;
    private int drains, cut, forcedDrains;
    private long longestWait;

    PacketBudget(long budgetNanos) {
        this.budgetNanos = budgetNanos;
    }

    /** A drain starts. A backlog that has waited too long is drained in full. */
    void begin(long now) {
        deadline = now + budgetNanos;
        stopped = handling = false;
        forced = waitingSince != NONE && now - waitingSince >= MAX_WAIT_NANOS;
        if (forced) forcedDrains++;
    }

    /**
     * Asked before each packet: whether to leave it and the rest for the next frame. The first is always handled,
     * so a drain that starts late still makes progress.
     */
    boolean stop(long now) {
        stopped = handling && !forced && now >= deadline;
        handling = true;
        return stopped;
    }

    /** A drain ended, early or with the queue empty. */
    void end(long now) {
        drains++;
        if (stopped) {
            cut++;
            if (waitingSince == NONE) waitingSince = now;
        } else {
            if (waitingSince != NONE) longestWait = Math.max(longestWait, now - waitingSince);
            waitingSince = NONE;
        }
    }

    void reset() {
        drains = cut = forcedDrains = 0;
        longestWait = 0;
    }

    Map<String, Object> report(boolean enabled) {
        var stats = new LinkedHashMap<String, Object>();
        stats.put("budgetUs", enabled ? budgetNanos / 1000 : 0);
        stats.put("drains", drains);
        stats.put("cutDrainPct", drains == 0 ? 0 : Math.round(1000.0 * cut / drains) / 10.0);
        stats.put("forcedDrains", forcedDrains);
        stats.put("longestWaitMs", Math.round(longestWait / 1e4) / 100.0);
        return stats;
    }

    /** The client's budget, loaded at its first use so that the policy above can be tested without a game. */
    private static final class Client {
        static final boolean ENABLED = VultrumConfig.get().packetBudget();
        static final PacketBudget BUDGET = new PacketBudget(BUDGET_NANOS);
    }

    /** Whether this drain is the client's and budgeted: it runs on the client thread. Called at its start. */
    public static boolean beginClient() {
        if (!Client.ENABLED || !Minecraft.getInstance().isSameThread()) return false;
        Client.BUDGET.begin(System.nanoTime());
        return true;
    }

    public static boolean stopClient() {
        return Client.BUDGET.stop(System.nanoTime());
    }

    public static void endClient() {
        Client.BUDGET.end(System.nanoTime());
    }

    public static void resetStats() {
        Client.BUDGET.reset();
    }

    /**
     * The client's counts since the last reset: drains (frames), the share cut short, forced drains, and the
     * longest a backlog waited, from the first drain that left it to the one that emptied the queue.
     */
    public static Map<String, Object> stats() {
        return Client.BUDGET.report(Client.ENABLED);
    }
}
