package dev.vultrum.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PacketBudgetTest {
    private static final long MS = 1_000_000L;

    @Test
    void stopsOnceTheBudgetIsSpent() {
        var budget = new PacketBudget(MS);
        budget.begin(10 * MS);
        assertFalse(budget.stop(10 * MS));
        assertFalse(budget.stop(11 * MS - 1));
        assertTrue(budget.stop(11 * MS));
    }

    @Test
    void theFirstPacketIsHandledEvenWhenTheDrainStartsLate() {
        var budget = new PacketBudget(MS);
        budget.begin(0);
        assertFalse(budget.stop(5 * MS), "a stall before the first packet");
        assertTrue(budget.stop(5 * MS));
    }

    @Test
    void aDrainThatEmptiesTheQueueEndsTheWait() {
        var budget = new PacketBudget(MS);
        budget.begin(0);
        assertFalse(budget.stop(0));
        assertTrue(budget.stop(2 * MS));
        budget.end(2 * MS);
        budget.begin(5 * MS);
        assertFalse(budget.stop(5 * MS));
        budget.end(6 * MS);
        assertEquals(4.0, budget.report(true).get("longestWaitMs"));
        budget.begin(100 * MS);
        assertFalse(budget.stop(100 * MS));
        assertTrue(budget.stop(101 * MS), "a new burst starts a new wait, not a forced drain");
        assertEquals(0, budget.report(true).get("forcedDrains"));
    }

    @Test
    void aBacklogLeftForTheMaximumWaitIsDrainedInFull() {
        var budget = new PacketBudget(MS);
        budget.begin(0);
        assertFalse(budget.stop(0));
        assertTrue(budget.stop(MS));
        budget.end(MS);
        budget.begin(20 * MS);
        assertFalse(budget.stop(20 * MS));
        assertTrue(budget.stop(21 * MS), "still under the maximum wait");
        budget.end(21 * MS);
        budget.begin(MS + PacketBudget.MAX_WAIT_NANOS);
        assertFalse(budget.stop(MS + PacketBudget.MAX_WAIT_NANOS));
        assertFalse(budget.stop(200 * MS), "the forced drain ignores the budget");
        budget.end(200 * MS);
        var report = budget.report(true);
        assertEquals(1, report.get("forcedDrains"));
        assertEquals(199.0, report.get("longestWaitMs"), "from the first drain that left the backlog");
        assertEquals(3, report.get("drains"));
        assertEquals(66.7, report.get("cutDrainPct"));
    }

    @Test
    void resetClearsTheCountsButNotABacklog() {
        var budget = new PacketBudget(MS);
        budget.begin(0);
        assertFalse(budget.stop(0));
        assertTrue(budget.stop(MS));
        budget.end(MS);
        budget.reset();
        assertEquals(0, budget.report(true).get("drains"));
        budget.begin(MS + PacketBudget.MAX_WAIT_NANOS);
        budget.end(MS + PacketBudget.MAX_WAIT_NANOS);
        assertEquals(1, budget.report(true).get("forcedDrains"), "the backlog from before the reset still waited");
    }

    @Test
    void reportsTheBudgetOnlyWhenEnabled() {
        var budget = new PacketBudget(PacketBudget.BUDGET_NANOS);
        assertEquals(1000L, budget.report(true).get("budgetUs"));
        assertEquals(0L, budget.report(false).get("budgetUs"));
        assertEquals(0.0, budget.report(true).get("cutDrainPct"), "no drains yet");
    }
}
