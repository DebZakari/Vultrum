package dev.vultrum.upload;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;

class ReclaimThreadTest {
    private static final long INTERVAL = 1_000_000;
    /** Never counted down: a reclaim that waits on it holds its thread until the test ends, which interrupts it. */
    private static final CountDownLatch NEVER = new CountDownLatch(1);

    private ReclaimThread thread;
    private final AtomicInteger calls = new AtomicInteger();
    private final Set<Thread> threads = ConcurrentHashMap.newKeySet();

    /** A reclaim that notes the thread it runs on, to end it after the test. */
    private void create(Runnable reclaim) {
        thread = new ReclaimThread(() -> {
            threads.add(Thread.currentThread());
            reclaim.run();
        }, INTERVAL);
    }

    @AfterEach
    void anInterruptEndsTheThread() throws InterruptedException {
        for (Thread started : threads) {
            started.interrupt();
            started.join(5_000);
            assertFalse(started.isAlive());
        }
        assertFalse(thread.skips(), "the setup reclaims again");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void nothingIsSkippedBeforeTheThreadStarts() {
        thread = new ReclaimThread(() -> { }, INTERVAL);
        assertFalse(thread.skips());
        assertFalse(thread.onThread());
    }

    @Test
    void otherThreadsSkipWhileTheThreadIsAlive() throws InterruptedException {
        var entered = new CountDownLatch(1);
        var fromTheThread = new ArrayList<Boolean>();
        create(() -> {
            fromTheThread.add(thread.skips());
            fromTheThread.add(thread.onThread());
            entered.countDown();
            await(NEVER);
        });
        thread.begin();
        assertTrue(entered.await(5, SECONDS));
        assertTrue(thread.skips());
        assertFalse(thread.onThread());
        assertEquals(List.of(false, true), fromTheThread, "the thread's own reclaim runs");
    }

    @Test
    void beginStartsOneThread() throws InterruptedException {
        var entered = new CountDownLatch(1);
        create(() -> {
            if (calls.incrementAndGet() == 8) {
                entered.countDown();
                await(NEVER);
            }
        });
        thread.begin();
        thread.begin();
        assertTrue(entered.await(5, SECONDS));
        assertEquals(1, threads.size());
        assertTrue(threads.iterator().next().isDaemon(), "the thread doesn't keep the game open");
    }

    @Test
    void aFailedReclaimEndsTheThread() throws InterruptedException {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        create(() -> {
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                await(release);
            } else {
                throw new IllegalStateException("a failed reclaim, for the test");
            }
        });
        thread.begin();
        assertTrue(entered.await(5, SECONDS));
        assertTrue(thread.skips());
        release.countDown();
        long deadline = System.nanoTime() + SECONDS.toNanos(5);
        while (thread.skips() && System.nanoTime() < deadline) Thread.onSpinWait();
        assertFalse(thread.skips(), "the setup reclaims again");
        assertEquals(2, calls.get(), "no reclaim after the failure");
    }

    @Test
    void untrackedReclaimsKeepNoBatches() throws InterruptedException {
        var entered = new CountDownLatch(1);
        create(() -> {
            for (int i = 0; i < 3; i++) thread.countRef();
            if (calls.incrementAndGet() == 3) {
                entered.countDown();
                await(NEVER);
            }
        });
        thread.begin();
        assertTrue(entered.await(5, SECONDS));
        var batches = new ArrayList<ReclaimThread.Batch>();
        thread.drainBatches(batches);
        assertEquals(List.of(), batches);
    }

    @Test
    void trackedReclaimsKeepABatchWhenTheyTookReferences() throws InterruptedException {
        var entered = new CountDownLatch(1);
        create(() -> {
            int call = calls.incrementAndGet();
            if (call == 5) {
                entered.countDown();
                await(NEVER);
            }
            if (call % 2 == 1) for (int i = 0; i < 3; i++) thread.countRef();
        });
        thread.trackBatches();
        thread.begin();
        assertTrue(entered.await(5, SECONDS));
        var batches = new ArrayList<ReclaimThread.Batch>();
        thread.drainBatches(batches);
        assertEquals(2, batches.size(), "calls 1 and 3 took references, 2 and 4 none, and 5 hasn't ended");
        for (var batch : batches) {
            assertEquals(3, batch.refs(), "each reclaim counts its own references");
            assertTrue(batch.nanos() >= 0);
        }
        assertTrue(batches.get(0).startNanos() < batches.get(1).startNanos());
        thread.drainBatches(batches);
        assertEquals(2, batches.size(), "a drain takes the batches out");
    }
}
