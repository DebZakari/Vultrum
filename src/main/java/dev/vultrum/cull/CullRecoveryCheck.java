package dev.vultrum.cull;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import dev.vultrum.VultrumClient;
import dev.vultrum.mixin.VulkanCommandEncoderAccessor;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferCopy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/** Benchmark-only check of the actual cull SPIR-V on isolated commands; never touches Sodium's ring. */
final class CullRecoveryCheck {
    private CullRecoveryCheck() { }

    static void record(GpuDevice device, VulkanCommandEncoder encoder, VulkanGpuTextureView depth, CullPipelines pipelines) {
        var owned = new ArrayList<GpuBuffer>();
        boolean queued = false;
        try {
            int storage = GpuBuffer.USAGE_INDIRECT_PARAMETERS | GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_COPY_SRC;
            GpuBuffer frame = allocate(device, owned, storage, CullFrame.BYTES + 4 * CullFrame.SECTION_BYTES);
            GpuBuffer commands = allocate(device, owned, storage, 80);
            GpuBuffer visibility = allocate(device, owned, storage, 8);
            GpuBuffer hiz = allocate(device, owned, storage, 4);
            GpuBuffer counters = allocate(device, owned, storage, CullCounters.BYTES);
            GpuBuffer history = allocate(device, owned, storage, 16);
            GpuBuffer read = allocate(device, owned, GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, 248);
            var frontend = device.createCommandEncoder();
            ByteBuffer input = bytes((int) frame.size());
            new CullFrame(new Matrix4f(), 0, 0, 0, false, 0, 0, 0, 2, 0, 4, 1, 1, 1, 1, 1).pack(input);
            input.position(CullFrame.BYTES);
            for (int key : new int[]{0, 1, -1, -2}) input.putInt(0).putInt(0).putInt(0).putInt(key);
            input.flip();
            frontend.writeToBuffer(frame.slice(), input);
            frontend.writeToBuffer(commands.slice(), commandInput(1, 1, 1, 7));
            frontend.writeToBuffer(visibility.slice(), bytes(8).putInt(1).putInt(0).flip());
            frontend.writeToBuffer(counters.slice(), bytes(CullCounters.BYTES));
            var cmd = ((VulkanCommandEncoderAccessor) encoder).vultrum$commandBuffer();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                GpuOcclusionCull.barrier(cmd, stack, VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                        VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
                vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipelines.cull);
                GpuOcclusionCull.descriptors(cmd, stack, pipelines.layout,
                        new long[]{vk(frame), vk(commands), vk(visibility), vk(hiz), vk(counters), vk(history)}, depth);
                dispatch(cmd, stack, pipelines.layout, 0);
                toCopy(cmd, stack);
                copy(cmd, stack, commands, read, 0, 80);
                copy(cmd, stack, history, read, 80, 8);
                // Stand in for phase 2 having overwritten both live commands; keep excluded commands untouched.
                GpuOcclusionCull.barrier(cmd, stack, VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_READ_BIT,
                        VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT);
                vkCmdUpdateBuffer(cmd, vk(commands), 0, commandInput(0, 0, 1, 7));
                GpuOcclusionCull.barrier(cmd, stack, VK_PIPELINE_STAGE_2_TRANSFER_BIT | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                        VK_ACCESS_2_TRANSFER_WRITE_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                        VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
                dispatch(cmd, stack, pipelines.layout, 3);
                toCopy(cmd, stack);
                copy(cmd, stack, commands, read, 88, 80);
                GpuOcclusionCull.barrier(cmd, stack, VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_READ_BIT,
                        VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
                dispatch(cmd, stack, pipelines.layout, 2);
                toCopy(cmd, stack);
                copy(cmd, stack, commands, read, 168, 80);
                GpuOcclusionCull.barrier(cmd, stack, VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                        VK_PIPELINE_STAGE_2_HOST_BIT, VK_ACCESS_2_HOST_READ_BIT);
            }
            // Renderpearl's same completion callback mechanism used by screenshots. Mapping happens after the submit.
            encoder.queueForDestroy(() -> {
                try (var mapped = read.map(true, false)) {
                    ByteBuffer result = mapped.data().order(ByteOrder.LITTLE_ENDIAN);
                    expect(result, 0, new int[]{1, 0, 1, 7});
                    if (result.getInt(80) != 1 || result.getInt(84) != 0) throw new IllegalStateException("GPU phase 1 history mismatch");
                    expect(result, 88, new int[]{0, 1, 1, 7});
                    expect(result, 168, new int[]{1, 1, 1, 7});
                    VultrumClient.LOGGER.info("Cull recovery GPU check passed: phase 1 history, remaining opaque draws, untouched translucent/gap commands");
                } finally { owned.forEach(GpuBuffer::close); }
            });
            queued = true;
        } finally { if (!queued) owned.forEach(GpuBuffer::close); }
    }

    private static GpuBuffer allocate(GpuDevice device, ArrayList<GpuBuffer> owned, int usage, int size) {
        GpuBuffer buffer = device.createBuffer(() -> "Vultrum recovery check", usage, size);
        owned.add(buffer);
        return buffer;
    }
    private static ByteBuffer bytes(int size) { return ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN); }
    private static ByteBuffer commandInput(int... instances) {
        ByteBuffer input = bytes(instances.length * 20);
        for (int count : instances) input.putInt(3).putInt(count).putInt(0).putInt(0).putInt(0);
        return input.flip();
    }
    private static long vk(GpuBuffer buffer) { return ((VulkanGpuBuffer) buffer).vkBuffer(); }
    private static void dispatch(org.lwjgl.vulkan.VkCommandBuffer cmd, MemoryStack stack, long layout, int mode) {
        vkCmdPushConstants(cmd, layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, stack.ints(mode, 0));
        vkCmdDispatch(cmd, 1, 1, 1);
    }
    private static void toCopy(org.lwjgl.vulkan.VkCommandBuffer cmd, MemoryStack stack) {
        GpuOcclusionCull.barrier(cmd, stack, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_READ_BIT);
    }
    private static void copy(org.lwjgl.vulkan.VkCommandBuffer cmd, MemoryStack stack, GpuBuffer from, GpuBuffer to, long offset, long bytes) {
        vkCmdCopyBuffer(cmd, vk(from), vk(to), VkBufferCopy.calloc(1, stack).srcOffset(0).dstOffset(offset).size(bytes));
    }
    private static void expect(ByteBuffer data, int offset, int[] instances) {
        for (int i = 0; i < instances.length; i++)
            if (data.getInt(offset + i * 20 + 4) != instances[i])
                throw new IllegalStateException("GPU recovery command mismatch at " + offset + "/" + i);
    }
}
