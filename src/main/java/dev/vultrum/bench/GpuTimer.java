package dev.vultrum.bench;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.GpuQueryPool;

import java.util.OptionalLong;

/**
 * GPU timestamps through renderpearl's public query API. Each in-flight frame owns a block of queries:
 * [0] frame start, [1] frame end, then begin/end pairs around terrain draws. A block is reused
 * {@link #RING} frames later. By then its submit has completed, so the host-side reset that
 * {@code writeTimestamp} performs is safe.
 */
final class GpuTimer {
    record Sample(double frameMs, double terrainMs, boolean recorded) { }

    private static final int RING = 8;
    private static final int QUERIES = 32;
    private static final int MAX_SPANS = (QUERIES - 2) / 2;

    private final GpuQueryPool pool;
    private final double msPerTick;
    private final boolean[] pending = new boolean[RING];
    private final boolean[] recorded = new boolean[RING];
    private final int[] spans = new int[RING];
    private int slot = -1;
    private boolean spanOpen;
    long dropped, overflowed;

    GpuTimer() {
        var device = RenderSystem.getDevice();
        pool = device.createTimestampQueryPool(RING * QUERIES);
        msPerTick = device.getDeviceInfo().timestampPeriod() / 1_000_000.0;
    }

    void beginFrame(long frame, boolean record) {
        slot = (int) (frame % RING);
        if (pending[slot]) ++dropped;
        pending[slot] = false;
        recorded[slot] = record;
        spans[slot] = 0;
        spanOpen = false;
        write(0);
    }

    void endFrame() {
        if (slot < 0) return;
        if (spanOpen) endSpan();
        write(1);
        pending[slot] = true;
        slot = -1;
    }

    void beginSpan() {
        if (slot < 0 || spanOpen) return;
        if (spans[slot] == MAX_SPANS) { ++overflowed; return; }
        write(2 + 2 * spans[slot]);
        spanOpen = true;
    }

    void endSpan() {
        if (slot < 0 || !spanOpen) return;
        write(3 + 2 * spans[slot]++);
        spanOpen = false;
    }

    /** Reads every finished frame without waiting. */
    void collect(java.util.function.Consumer<Sample> sink) {
        for (int s = 0; s < RING; s++) {
            if (!pending[s] || s == slot) continue;
            OptionalLong[] values = pool.getValues(s * QUERIES, 2 + 2 * spans[s]);
            boolean ready = true;
            for (OptionalLong value : values) ready &= value.isPresent();
            if (!ready) continue;
            pending[s] = false;
            long frameTicks = values[1].getAsLong() - values[0].getAsLong();
            long terrainTicks = 0;
            for (int i = 0; i < spans[s]; i++) terrainTicks += values[3 + 2 * i].getAsLong() - values[2 + 2 * i].getAsLong();
            if (frameTicks < 0 || terrainTicks < 0) { ++dropped; continue; }
            sink.accept(new Sample(frameTicks * msPerTick, terrainTicks * msPerTick, recorded[s]));
        }
    }

    boolean hasPendingRecorded() {
        for (int s = 0; s < RING; s++) if (pending[s] && recorded[s]) return true;
        return false;
    }

    private void write(int query) {
        RenderSystem.getDevice().createCommandEncoder().writeTimestamp(pool, slot * QUERIES + query);
    }
}
