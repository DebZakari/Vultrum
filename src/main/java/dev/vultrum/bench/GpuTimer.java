package dev.vultrum.bench;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import java.util.OptionalLong;
import java.util.function.Consumer;

/** Timestamp transport. Pending slots belong to their original frame until query results are available. */
final class GpuTimer {
    record Sample(long frame, long window, double frameMs, double terrainMs, long dropped, long overflowed) {}
    interface Queries {
        void write(int query);
        OptionalLong[] read(int first, int count);
    }
    private static final int RING = 8, QUERIES = 32, MAX_SPANS = (QUERIES - 2) / 2;
    private final Queries queries;
    private final double msPerTick;
    private final boolean[] pending = new boolean[RING];
    private final long[] frames = new long[RING], windows = new long[RING], overflow = new long[RING];
    private final int[] spans = new int[RING];
    private int slot = -1;
    private boolean spanOpen;
    GpuTimer() { this(new DeviceQueries(), RenderSystem.getDevice().getDeviceInfo().timestampPeriod() / 1_000_000.0); }
    GpuTimer(Queries queries, double msPerTick) { this.queries = queries; this.msPerTick = msPerTick; }
    void beginFrame(long frame, long window, Consumer<Sample> sink) {
        int next = (int) (frame % RING);
        slot = -1;
        spanOpen = false;
        if (pending[next]) {
            sink.accept(new Sample(frame, window, Double.NaN, Double.NaN, 1, 0));
            return;
        }
        slot = next;
        frames[slot] = frame; windows[slot] = window;
        spans[slot] = 0; overflow[slot] = 0;
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
        if (spans[slot] == MAX_SPANS) { overflow[slot]++; return; }
        write(2 + 2 * spans[slot]);
        spanOpen = true;
    }
    void endSpan() {
        if (slot < 0 || !spanOpen) return;
        write(3 + 2 * spans[slot]++);
        spanOpen = false;
    }
    void collect(Consumer<Sample> sink) {
        for (int s = 0; s < RING; s++) {
            if (!pending[s] || s == slot) continue;
            OptionalLong[] values = queries.read(s * QUERIES, 2 + 2 * spans[s]);
            boolean ready = true;
            for (OptionalLong value : values) ready &= value.isPresent();
            if (!ready) continue;
            pending[s] = false;
            long frameTicks = values[1].getAsLong() - values[0].getAsLong(), terrainTicks = 0;
            for (int i = 0; i < spans[s]; i++) terrainTicks += values[3 + 2 * i].getAsLong() - values[2 + 2 * i].getAsLong();
            boolean valid = frameTicks >= 0 && terrainTicks >= 0;
            sink.accept(new Sample(frames[s], windows[s], valid ? frameTicks * msPerTick : Double.NaN,
                    valid ? terrainTicks * msPerTick : Double.NaN, valid ? 0 : 1, overflow[s]));
        }
    }
    private void write(int query) { queries.write(slot * QUERIES + query); }
    private static final class DeviceQueries implements Queries {
        private final GpuQueryPool pool = RenderSystem.getDevice().createTimestampQueryPool(RING * QUERIES);
        public void write(int query) { RenderSystem.getDevice().createCommandEncoder().writeTimestamp(pool, query); }
        public OptionalLong[] read(int first, int count) { return pool.getValues(first, count); }
    }
}
