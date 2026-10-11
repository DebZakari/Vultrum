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
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.vkResetQueryPool;

/**
 * Vulkan pipeline-statistics queries around each terrain draw call ({@code drawChunkLayer}), for
 * benchmark runs with -Dvultrum.bench.stats=true. Fragment-shader invocations divided by the
 * framebuffer's pixels is terrain overdraw; fragments per primitive shows how small the triangles
 * are; vertex invocations is the geometry load. Same frame ring and reuse rule as {@link GpuTimer}.
 * The device feature comes from {@code VulkanFeatureSetsMixin}.
 *
 * <p>Under Vitrail only shadow-pass calls are counted: Vitrail hands Sodium a lazy pass and opens the real
 * main pass inside {@code drawChunkLayer}, and a query must begin and end inside one render pass instance.
 */
final class PipelineStats {
    /** Result order follows the flag bits, lowest first. */
    static final String[] NAMES = {"iaPrimitives", "vsInvocations", "clipInvocations", "clipPrimitives", "fsInvocations"};
    private static final int FLAGS = VK_QUERY_PIPELINE_STATISTIC_INPUT_ASSEMBLY_PRIMITIVES_BIT
            | VK_QUERY_PIPELINE_STATISTIC_VERTEX_SHADER_INVOCATIONS_BIT | VK_QUERY_PIPELINE_STATISTIC_CLIPPING_INVOCATIONS_BIT
            | VK_QUERY_PIPELINE_STATISTIC_CLIPPING_PRIMITIVES_BIT | VK_QUERY_PIPELINE_STATISTIC_FRAGMENT_SHADER_INVOCATIONS_BIT;
    private static final int RING = 8, MAX_CALLS = 16;

    private final VkDevice device;
    private final long pool;
    private final LongBuffer results = MemoryUtil.memAllocLong(MAX_CALLS * NAMES.length);
    private final int[] calls = new int[RING];
    private final String[][] groups = new String[RING][MAX_CALLS];
    private final boolean[] pending = new boolean[RING], recorded = new boolean[RING];
    /** "ordinal group" -> sums of each statistic, then the frame count in the last slot. */
    private final Map<String, double[]> sums = new LinkedHashMap<>();
    private int slot = -1;
    private VkCommandBuffer open;

    private PipelineStats(VkDevice device, long pool) {
        this.device = device;
        this.pool = pool;
    }

    /** Null when the device lacks the feature or the backend isn't Vulkan. */
    static PipelineStats create() {
        if (!(((FrontendGpuDeviceAccessor) RenderSystem.getDevice()).vultrum$backend() instanceof VulkanDevice vulkan)
                || !((VulkanDeviceAccessor) vulkan).vultrum$enabledFeatures().contains(VultrumFeatures.PIPELINE_STATISTICS)) {
            VultrumClient.LOGGER.warn("[bench] pipeline statistics unavailable on this device");
            return null;
        }
        VkDevice device = vulkan.vkDevice();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var info = VkQueryPoolCreateInfo.calloc(stack).sType$Default()
                    .queryType(VK_QUERY_TYPE_PIPELINE_STATISTICS).queryCount(RING * MAX_CALLS).pipelineStatistics(FLAGS);
            LongBuffer handle = stack.mallocLong(1);
            if (vkCreateQueryPool(device, info, null, handle) != VK_SUCCESS) return null;
            vkResetQueryPool(device, handle.get(0), 0, RING * MAX_CALLS);
            return new PipelineStats(device, handle.get(0));
        }
    }

    void beginFrame(long frame, boolean record) {
        slot = (int) (frame % RING);
        if (pending[slot]) vkResetQueryPool(device, pool, slot * MAX_CALLS, calls[slot]);
        pending[slot] = false;
        recorded[slot] = record;
        calls[slot] = 0;
    }

    void begin(RenderPass pass, String group) {
        if (slot < 0 || open != null || calls[slot] == MAX_CALLS) return;
        if (!(pass instanceof FrontendRenderPassAccessor frontend)
                || !(frontend.vultrum$backend() instanceof VulkanRenderPass vulkan)) return;
        open = ((VulkanRenderPassAccessor) vulkan).vultrum$commandBuffer();
        groups[slot][calls[slot]] = group;
        vkCmdBeginQuery(open, pool, slot * MAX_CALLS + calls[slot], 0);
    }

    void end() {
        if (open == null) return;
        vkCmdEndQuery(open, pool, slot * MAX_CALLS + calls[slot]++);
        open = null;
    }

    void endFrame() {
        if (slot < 0) return;
        end();
        pending[slot] = calls[slot] > 0;
        slot = -1;
    }

    /** Reads every finished frame without waiting, then resets its queries for reuse. */
    void collect() {
        for (int s = 0; s < RING; s++) {
            if (!pending[s] || s == slot) continue;
            int n = calls[s];
            results.clear();
            int status = vkGetQueryPoolResults(device, pool, s * MAX_CALLS, n, results, (long) NAMES.length * Long.BYTES, VK_QUERY_RESULT_64_BIT);
            if (status == VK_NOT_READY) continue;
            pending[s] = false;
            vkResetQueryPool(device, pool, s * MAX_CALLS, n);
            if (status != VK_SUCCESS || !recorded[s]) continue;
            for (int call = 0; call < n; call++) {
                double[] sum = sums.computeIfAbsent(call + " " + groups[s][call], k -> new double[NAMES.length + 1]);
                for (int i = 0; i < NAMES.length; i++) sum[i] += results.get(call * NAMES.length + i);
                sum[NAMES.length]++;
            }
        }
    }

    boolean hasPendingRecorded() {
        for (int s = 0; s < RING; s++) if (pending[s] && recorded[s]) return true;
        return false;
    }

    void clear() { sums.clear(); }

    /** Per-frame means for each terrain draw call, in call order. */
    List<Map<String, Object>> results() {
        var list = new ArrayList<Map<String, Object>>();
        for (var entry : sums.entrySet()) {
            double[] sum = entry.getValue();
            var row = new LinkedHashMap<String, Object>();
            String[] key = entry.getKey().split(" ", 2);
            row.put("call", Integer.parseInt(key[0]));
            row.put("group", key[1]);
            for (int i = 0; i < NAMES.length; i++) row.put(NAMES[i], Math.round(sum[i] / sum[NAMES.length]));
            row.put("frames", (long) sum[NAMES.length]);
            list.add(row);
        }
        return list;
    }
}
