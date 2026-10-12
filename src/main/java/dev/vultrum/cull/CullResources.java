package dev.vultrum.cull;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Owns cull buffers and host staging memory. Buffer close must defer destruction through the GPU encoder. */
final class CullResources {
    interface Allocator {
        GpuBuffer buffer(String name, int usage, long bytes);
        ByteBuffer host(int bytes);
        void free(ByteBuffer buffer);
    }

    private final Allocator allocator;
    GpuBuffer frame, visibility, hiz, phase1, counters;
    final GpuBuffer[] readback = new GpuBuffer[4];
    final CullReadbacks reads = new CullReadbacks(4);
    ByteBuffer upload, flush;
    boolean clearVisibility;

    CullResources(Allocator allocator) { this.allocator = allocator; }

    void ensure(int commands, int maxRegion, long hizTexels) {
        int storage = GpuBuffer.USAGE_INDIRECT_PARAMETERS;
        long frameBytes = CullFrame.BYTES + (long) commands * CullFrame.SECTION_BYTES + 16;
        if (frame == null || frame.size() < frameBytes) {
            long bytes = frame == null ? frameBytes : Math.max(frameBytes, frame.size() * 2);
            ByteBuffer nextUpload = allocator.host(Math.toIntExact(bytes)).order(ByteOrder.LITTLE_ENDIAN);
            GpuBuffer next;
            try { next = allocator.buffer("frame", storage | GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_COPY_SRC, bytes); }
            catch (RuntimeException | Error failure) { allocator.free(nextUpload); throw failure; }
            if (frame != null) frame.close();
            if (upload != null) allocator.free(upload);
            frame = next;
            upload = nextUpload;
        }
        long visibilityBytes = ((long) maxRegion + 1) * CullBatch.SECTIONS_PER_REGION * Integer.BYTES;
        if (visibility == null || visibility.size() < visibilityBytes) {
            visibility = grow(visibility, "visibility", storage | GpuBuffer.USAGE_COPY_DST, visibilityBytes);
            clearVisibility = true;
        }
        hiz = grow(hiz, "Hi-Z", storage, hizTexels * Float.BYTES);
        phase1 = grow(phase1, "phase 1 draws", storage, (long) commands * Integer.BYTES);
        if (counters == null) counters = allocator.buffer("counters", storage | GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_COPY_SRC, CullCounters.BYTES);
        // Each successful allocation is owned immediately, so later failures can close the partial set.
        for (int i = 0; i < readback.length; i++)
            if (readback[i] == null) readback[i] = allocator.buffer("counter readback", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, CullCounters.BYTES);
        if (flush == null) {
            flush = allocator.host(16);
            for (int i = 0; i < 16; i++) flush.put(i, (byte) 0);
        }
    }

    private GpuBuffer grow(GpuBuffer old, String name, int usage, long bytes) {
        if (old != null && old.size() >= bytes) return old;
        GpuBuffer next = allocator.buffer(name, usage, old == null ? bytes : Math.max(bytes, old.size() * 2));
        if (old != null) old.close();
        return next;
    }

    void close() {
        for (GpuBuffer buffer : new GpuBuffer[]{frame, visibility, hiz, phase1, counters})
            if (buffer != null) buffer.close();
        for (int i = 0; i < readback.length; i++) {
            if (readback[i] != null) readback[i].close();
            readback[i] = null;
        }
        if (upload != null) allocator.free(upload);
        if (flush != null) allocator.free(flush);
        frame = visibility = hiz = phase1 = counters = null;
        upload = flush = null;
        reads.discard();
    }
}
