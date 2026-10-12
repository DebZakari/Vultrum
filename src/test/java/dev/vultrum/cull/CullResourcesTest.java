package dev.vultrum.cull;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class CullResourcesTest {
    static class Buffer implements GpuBuffer {
        final long bytes;
        boolean closed, destroyed;
        Buffer(long bytes) { this.bytes = bytes; }
        public long size() { return bytes; }
        public int usage() { return 0; }
        public boolean isClosed() { return closed; }
        public GpuBufferSlice.MappedView map(long at, long count, boolean read, boolean write) { throw new UnsupportedOperationException(); }
        public void close() { assertFalse(closed); closed = true; } // fake completion queue owns actual destruction
    }
    static class Allocator implements CullResources.Allocator {
        final ArrayList<Buffer> buffers = new ArrayList<>();
        final Set<ByteBuffer> hosts = Collections.newSetFromMap(new IdentityHashMap<>());
        int allocations, fail;
        void allocation() { if (++allocations == fail) throw new IllegalStateException("allocation " + fail); }
        public GpuBuffer buffer(String name, int usage, long bytes) {
            allocation(); var buffer = new Buffer(bytes); buffers.add(buffer); return buffer;
        }
        public ByteBuffer host(int bytes) { allocation(); var host = ByteBuffer.allocate(bytes); hosts.add(host); return host; }
        public void free(ByteBuffer host) { assertTrue(hosts.remove(host)); }
        void completeSubmit() { buffers.stream().filter(buffer -> buffer.closed).forEach(buffer -> buffer.destroyed = true); }
    }

    @TestFactory Stream<DynamicTest> allPartialAllocationsRemainOwnedAndCanBeClosed() {
        // Host upload, five storage buffers, four readbacks, host flush.
        return IntStream.rangeClosed(1, 11).mapToObj(fail -> DynamicTest.dynamicTest("allocation " + fail, () -> {
            var allocator = new Allocator(); allocator.fail = fail;
            var resources = new CullResources(allocator);
            assertThrows(IllegalStateException.class, () -> resources.ensure(12, 1, 40));
            resources.close(); resources.close();
            assertTrue(allocator.hosts.isEmpty());
            assertTrue(allocator.buffers.stream().allMatch(buffer -> buffer.closed));
            assertTrue(allocator.buffers.stream().noneMatch(buffer -> buffer.destroyed));
            allocator.completeSubmit();
            assertTrue(allocator.buffers.stream().allMatch(buffer -> buffer.destroyed));
        }));
    }

    @Test void failedGrowthKeepsThePreviousBufferAndHostAllocation() {
        var allocator = new Allocator();
        var resources = new CullResources(allocator);
        resources.ensure(12, 1, 40);
        var oldFrame = resources.frame;
        var oldUpload = resources.upload;
        allocator.fail = allocator.allocations + 2; // replacement host succeeds, replacement GPU buffer fails
        assertThrows(IllegalStateException.class, () -> resources.ensure(100, 1, 40));
        assertSame(oldFrame, resources.frame); assertFalse(oldFrame.isClosed());
        assertSame(oldUpload, resources.upload);
        assertEquals(2, allocator.hosts.size());
        resources.close(); assertTrue(allocator.hosts.isEmpty());
    }

    @Test void growthAndStopRetireBuffersWithoutReusingPendingReadbacks() {
        var allocator = new Allocator(); var resources = new CullResources(allocator);
        resources.ensure(12, 1, 40);
        var oldFrame = (Buffer) resources.frame;
        resources.reads.recorded(0, 8);
        var pendingRead = resources.readback[0];
        resources.ensure(100, 5, 400);
        assertTrue(oldFrame.closed); assertFalse(oldFrame.destroyed);
        assertSame(pendingRead, resources.readback[0]);
        assertFalse(resources.reads.free(0));
        resources.close();
        assertTrue(pendingRead.isClosed());
        assertFalse(((Buffer) pendingRead).destroyed);
        allocator.completeSubmit();
        assertTrue(((Buffer) pendingRead).destroyed);
    }
}
