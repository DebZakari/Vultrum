package dev.vultrum.bench;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import dev.vultrum.VultrumClient;
import dev.vultrum.mixin.FrontendGpuDeviceAccessor;
import dev.vultrum.mixin.FrontendRenderPassAccessor;
import dev.vultrum.mixin.VulkanDeviceAccessor;
import dev.vultrum.mixin.VulkanRenderPassAccessor;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.vkResetQueryPool;

/** Diagnostic terrain-query transport; results retain their frame and recording identity until collected. */
final class PipelineStats {
    static final String[] NAMES = {"iaPrimitives", "vsInvocations", "clipInvocations", "clipPrimitives", "fsInvocations"};
    private static final int FLAGS = VK_QUERY_PIPELINE_STATISTIC_INPUT_ASSEMBLY_PRIMITIVES_BIT
            | VK_QUERY_PIPELINE_STATISTIC_VERTEX_SHADER_INVOCATIONS_BIT | VK_QUERY_PIPELINE_STATISTIC_CLIPPING_INVOCATIONS_BIT
            | VK_QUERY_PIPELINE_STATISTIC_CLIPPING_PRIMITIVES_BIT | VK_QUERY_PIPELINE_STATISTIC_FRAGMENT_SHADER_INVOCATIONS_BIT;
    private static final int RING = 8, MAX_CALLS = 16;
    record Call(int ordinal, String group, long[] values) {}
    record Sample(long frame, long window, List<Call> calls, long dropped) {}
    interface Queries {
        boolean begin(int query, RenderPass pass);
        void end(int query);
        /** Null until all results are available. Errors must propagate without resetting pending queries. */
        long[] read(int first, int count);
        void reset(int first, int count);
    }

    private final Queries queries;
    private final int[] calls = new int[RING];
    private final String[][] groups = new String[RING][MAX_CALLS];
    private final boolean[] pending = new boolean[RING];
    private final long[] frames = new long[RING], windows = new long[RING], dropped = new long[RING];
    private int slot = -1;
    private boolean open;

    PipelineStats(Queries queries) { this.queries = queries; }

    /** Under Vitrail's lazy main pass, only shadow draws expose an actual Vulkan pass here. */
    static PipelineStats create() {
        if (!(((FrontendGpuDeviceAccessor) RenderSystem.getDevice()).vultrum$backend() instanceof VulkanDevice vulkan)
                || !((VulkanDeviceAccessor) vulkan).vultrum$enabledFeatures().contains(VultrumFeatures.PIPELINE_STATISTICS)) {
            VultrumClient.LOGGER.warn("[bench] pipeline statistics unavailable on this device");
            return null;
        }
        var device = vulkan.vkDevice();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var info = VkQueryPoolCreateInfo.calloc(stack).sType$Default()
                    .queryType(VK_QUERY_TYPE_PIPELINE_STATISTICS).queryCount(RING * MAX_CALLS).pipelineStatistics(FLAGS);
            var handle = stack.mallocLong(1);
            if (vkCreateQueryPool(device, info, null, handle) != VK_SUCCESS) return null;
            vkResetQueryPool(device, handle.get(0), 0, RING * MAX_CALLS);
            return new PipelineStats(new DeviceQueries(device, handle.get(0)));
        }
    }

    void beginFrame(long frame, long window, Consumer<Sample> sink) {
        int next = (int) (frame % RING);
        slot = -1;
        if (pending[next]) {
            sink.accept(new Sample(frame, window, List.of(), 1));
            return;
        }
        slot = next;
        frames[slot] = frame; windows[slot] = window;
        calls[slot] = 0; dropped[slot] = 0;
    }
    void begin(RenderPass pass, String group) {
        if (slot < 0 || open) return;
        if (calls[slot] == MAX_CALLS) { dropped[slot]++; return; }
        if (!queries.begin(slot * MAX_CALLS + calls[slot], pass)) return;
        open = true;
        groups[slot][calls[slot]] = group;
    }
    void end() {
        if (!open) return;
        queries.end(slot * MAX_CALLS + calls[slot]++);
        open = false;
    }
    void endFrame(Consumer<Sample> sink) {
        if (slot < 0) return;
        end();
        pending[slot] = calls[slot] > 0;
        if (!pending[slot]) sink.accept(new Sample(frames[slot], windows[slot], List.of(), dropped[slot]));
        slot = -1;
    }
    void collect(Consumer<Sample> sink) {
        for (int s = 0; s < RING; s++) {
            if (!pending[s] || s == slot) continue;
            long[] values = queries.read(s * MAX_CALLS, calls[s]);
            if (values == null) continue;
            queries.reset(s * MAX_CALLS, calls[s]);
            pending[s] = false;
            var result = new ArrayList<Call>();
            for (int call = 0; call < calls[s]; call++)
                result.add(new Call(call, groups[s][call],
                        java.util.Arrays.copyOfRange(values, call * NAMES.length, (call + 1) * NAMES.length)));
            sink.accept(new Sample(frames[s], windows[s], result, dropped[s]));
        }
    }

    private static final class DeviceQueries implements Queries {
        private final VkDevice device;
        private final long pool;
        private final LongBuffer results = MemoryUtil.memAllocLong(MAX_CALLS * NAMES.length);
        private VkCommandBuffer open;
        DeviceQueries(VkDevice device, long pool) { this.device = device; this.pool = pool; }
        public boolean begin(int query, RenderPass pass) {
            if (!(pass instanceof FrontendRenderPassAccessor frontend)
                    || !(frontend.vultrum$backend() instanceof VulkanRenderPass vulkan)) return false;
            open = ((VulkanRenderPassAccessor) vulkan).vultrum$commandBuffer();
            vkCmdBeginQuery(open, pool, query, 0);
            return true;
        }
        public void end(int query) { vkCmdEndQuery(open, pool, query); open = null; }
        public long[] read(int first, int count) {
            results.clear();
            int status = vkGetQueryPoolResults(device, pool, first, count, results, (long) NAMES.length * Long.BYTES, VK_QUERY_RESULT_64_BIT);
            if (status == VK_NOT_READY) return null;
            if (status != VK_SUCCESS) throw new IllegalStateException("Pipeline statistics query failed: " + status);
            var copy = new long[count * NAMES.length];
            results.get(0, copy);
            return copy;
        }
        public void reset(int first, int count) { vkResetQueryPool(device, pool, first, count); }
    }
}
