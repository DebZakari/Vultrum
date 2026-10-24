package dev.vultrum.upload;

import dev.vultrum.VultrumClient;
import dev.vultrum.VultrumConfig;
import dev.vultrum.LaunchHooks;
import net.caffeinemc.mods.sodium.client.util.NativeBuffer;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.LockSupport;

/**
 * Runs Sodium's buffer reclaim on its own thread. Each of Sodium's native buffers registers a phantom reference,
 * which the GC queues once the buffer object is collected, and the first step of every terrain setup polls that
 * queue until it is empty. So the setup after a GC cycle takes every reference the cycle queued: 10–48 thousand,
 * 7–33 ms in one frame, part of it a rehash of Sodium's map that no budget between polls can split.
 *
 * <p>Here a daemon thread calls the same reclaim every {@link #INTERVAL_NANOS}, and the terrain setup skips its call
 * while the thread is alive (SodiumReclaimThreadMixin). Sodium's reclaim runs on any thread: its map is synchronized,
 * its byte count atomic, and build threads already run it after a failed allocation. If a reclaim throws, the thread
 * logs it and ends, and the setup reclaims as it did without it. So does an interrupt. Measured over 12 pairs of runs: no
 * terrain setup of 5 ms or more with the thread, against 13 without, and loads 2.1% longer, within their noise.
 */
public final class ReclaimThread {
    static final long INTERVAL_NANOS = 50_000_000;

    /** A reclaim on the thread that took at least one reference, kept for the benchmark. */
    public record Batch(long startNanos, long nanos, int refs) { }

    private final Runnable reclaim;
    private final long intervalNanos;
    private volatile Thread thread;
    private volatile boolean trackBatches;
    private final ConcurrentLinkedQueue<Batch> batches = new ConcurrentLinkedQueue<>();
    // Only the thread reads and writes this: the references its current reclaim took (the bench's mixin counts them).
    private int refs;

    ReclaimThread(Runnable reclaim, long intervalNanos) {
        this.reclaim = reclaim;
        this.intervalNanos = intervalNanos;
    }

    /** Starts the thread, once. */
    synchronized void begin() {
        if (thread != null) return;
        var started = new Thread(this::run, "Vultrum reclaim");
        started.setDaemon(true);
        // Set, not inherited: the game thread runs at the highest priority once Minecraft.run starts.
        started.setPriority(Thread.NORM_PRIORITY);
        thread = started;
        started.start();
    }

    /** Whether the terrain setup's reclaim, called on this thread, is skipped: the thread is alive and this isn't it. */
    boolean skips() {
        Thread current = thread;
        return current != null && current != Thread.currentThread() && current.isAlive();
    }

    boolean onThread() {
        return Thread.currentThread() == thread;
    }

    /** One reference the thread's current reclaim took. Called on the thread, by the bench's mixin. */
    public void countRef() {
        refs++;
    }

    /** Keeps each reclaim that takes a reference from now on, for the benchmark to drain. */
    public void trackBatches() {
        trackBatches = true;
    }

    public void drainBatches(List<Batch> to) {
        for (Batch batch; (batch = batches.poll()) != null; ) to.add(batch);
    }

    private void run() {
        while (true) {
            LockSupport.parkNanos(intervalNanos);
            // Once interrupted, every park would return at once.
            if (Thread.interrupted()) {
                VultrumClient.LOGGER.warn("Vultrum's reclaim thread was interrupted and stopped. Sodium reclaims in its terrain setup again.");
                return;
            }
            refs = 0;
            long start = System.nanoTime();
            try {
                reclaim.run();
            } catch (Throwable t) {
                VultrumClient.LOGGER.error("Vultrum's reclaim thread stopped: Sodium's reclaim failed. Sodium reclaims in its terrain setup again.", t);
                return;
            }
            if (refs > 0 && trackBatches) batches.add(new Batch(start, System.nanoTime() - start, refs));
        }
    }

    /** The client's setting and thread, loaded at their first use so that the thread above can be tested without a game. */
    private static final class Client {
        static final boolean ON = VultrumConfig.get().reclaimThread() && LaunchHooks.get().sodium();
        static final ReclaimThread THREAD = new ReclaimThread(() -> NativeBuffer.reclaim(false), INTERVAL_NANOS);
    }

    /** Whether the thread runs: the setting is on and Sodium is a version the thread's mixin supports. */
    public static boolean on() {
        return Client.ON;
    }

    /** Starts the thread if it is on. Called once, at client init. */
    public static void start() {
        if (Client.ON) Client.THREAD.begin();
    }

    /** Whether the terrain setup, on this thread, skips its reclaim. Never while the setting is off: the thread isn't started. */
    public static boolean skip() {
        return Client.THREAD.skips();
    }

    /** Whether this is the reclaim thread. */
    public static boolean isCurrent() {
        return Client.THREAD.onThread();
    }

    /** The client's thread, started or not, for the benchmark's batches. */
    public static ReclaimThread client() {
        return Client.THREAD;
    }
}
