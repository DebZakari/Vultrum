package dev.vultrum.cull;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import dev.vultrum.VultrumClient;
import dev.vultrum.VultrumConfig;
import dev.vultrum.VultrumMixinPlugin;
import dev.vultrum.mixin.FrontendGpuDeviceAccessor;
import dev.vultrum.mixin.VKIndirectDrawBatchAccessor;
import dev.vultrum.mixin.VulkanCommandEncoderAccessor;
import net.caffeinemc.mods.sodium.client.gpu.device.batch.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.gpu.device.batch.VKIndirectDrawBatch;
import net.caffeinemc.mods.sodium.client.gpu.device.context.DrawContext;
import net.caffeinemc.mods.sodium.client.gpu.device.context.VKIndirectContext;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceSubgroupProperties;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Two-phase GPU occlusion cull over Sodium's indirect draws, inside Vitrail's level pass (shader packs).
 *
 * <p>Sodium's main-view prepare writes this frame's commands into its indirect ring; this class records,
 * per command, the section it draws. Around {@code drawChunkLayer(OPAQUE)}:
 * <ol>
 *   <li>Phase 1: a compute pass sets each command's instance count to 1 if its section was visible last frame, and Sodium draws.</li>
 *   <li>The Hi-Z pyramid is built from the depth phase 1 wrote.</li>
 *   <li>Phase 2: every section is tested against it. Visible ones are remembered for the next frame, and the
 *       newly visible ones (not drawn in phase 1) are drawn by a second Sodium draw.</li>
 * </ol>
 * Renderpearl transfers end the terrain pass Vitrail holds open, so a buffer write before each compute
 * block leaves no render pass open.
 *
 * <p>A {@link CullGate} skips whole frames in scenes where the cull leaves out too few triangles to pay
 * for itself; skipped frames draw Sodium's commands untouched. GPU counters come back through
 * readbacks collected once their submit has finished.
 *
 * <p>On unless {@code occlusion-cull=false} in {@code config/vultrum.properties}, and only with Sodium and
 * Vitrail in their supported ranges and a shader pack enabled at launch. A pack switched off in game leaves it on
 * over vanilla's frames: Vitrail opens its level pass with or without a pack, and the main depth is the same.
 */
public final class GpuOcclusionCull {
    /** Read once at launch. Also forces Sodium's indirect backend, whose draw arguments the GPU can edit. */
    public static final boolean CULL = VultrumConfig.get().occlusionCull() && VultrumMixinPlugin.supports("dev.vitrail.")
            && vitrailWantsPack();

    private static final int COMMAND_BYTES = 20; // VkDrawIndexedIndirectCommand
    private static final int FLUSH_BYTES = 16;
    private static final int CULL_GROUP = 64;
    private static final int READBACK_SLOTS = 4;
    private static final String LEVEL_PASS = "dev.vitrail.render.LevelPass";

    // The main view's prepare in this frame.
    private static boolean shadow, preparing, prepared, broken;
    private static boolean levelPass = true; // the last main opaque draw ran in Vitrail's level pass
    private static VKIndirectContext context;
    private static int firstCommand, commandCount, maxRegion;
    private static int[] sections = new int[4096 * 4];

    private static long setLayout, pipelineLayout, cullPipeline, hizPipeline;
    private static GpuBuffer frameData, visibility, hiz, counters;
    private static final GpuBuffer[] readback = new GpuBuffer[READBACK_SLOTS];
    private static final long[] readbackSubmit = new long[READBACK_SLOTS]; // 0: free
    private static VulkanCommandEncoder vulkanEncoder;
    private static final CullGate gate = new CullGate();
    private static ByteBuffer upload, flush;
    private static boolean clearVisibility, pinned;
    /** Benchmark only: true culls every frame, false none, null leaves it to the gate. */
    private static Boolean override;
    private static int frame = 1, culls;

    private static CullCounters totals = CullCounters.ZERO;
    private static long statFrames, statPrepares, statCulls;

    private GpuOcclusionCull() { }

    /** Vitrail draws its shadow pass, which prepares and draws Sodium's commands for the light's view. */
    public static void shadow(boolean drawing) {
        shadow = drawing;
    }

    /** Once per frame, before the main view's prepare. */
    public static void beginPrepare() {
        if (!CULL || broken || shadow) return;
        collect();
        statPrepares++;
        // Off where the last opaque draw wasn't in Vitrail's level pass. Pinned benchmark shots measure the cull itself,
        // so the gate stays out of them.
        boolean cull = override != null ? override : pinned || gate.cull();
        if (!levelPass || !cull) {
            prepared = false;
            return;
        }
        preparing = true;
        prepared = false;
        firstCommand = -1;
        commandCount = 0;
        maxRegion = -1;
    }

    /** Sodium copied this batch's commands into its indirect ring. Translucent commands are left alone. */
    public static void prepared(MultiDrawBatch batch, TerrainRenderPass pass, RenderRegion region, DrawContext drawContext) {
        if (!preparing || !(batch instanceof VKIndirectDrawBatch) || !(drawContext instanceof VKIndirectContext indirect)) return;
        context = indirect;
        int first = (int) (((VKIndirectDrawBatchAccessor) batch).vultrum$offset() / COMMAND_BYTES);
        if (firstCommand < 0) firstCommand = first;
        int at = first - firstCommand, end = at + batch.size;
        if (at < commandCount) {
            preparing = false; // not one contiguous range: skip this frame
            return;
        }
        if (end * 4 > sections.length) sections = Arrays.copyOf(sections, Math.max(end * 4, sections.length * 2));
        Arrays.fill(sections, commandCount * 4, at * 4, -1);
        int id = region.getId();
        if (pass.isTranslucent() || id < 0) {
            Arrays.fill(sections, at * 4, end * 4, -1);
        } else {
            var recorded = (CullBatch) batch;
            if (recorded.vultrum$sections().length < batch.size * 4) {
                preparing = false; // commands added outside fillCommandBuffer's two calls: skip this frame
                return;
            }
            CullBatch.copySections(recorded.vultrum$sections(), batch.size, recorded.vultrum$region(), id, sections, at);
            maxRegion = Math.max(maxRegion, id);
        }
        commandCount = end;
    }

    public static void endPrepare() {
        if (!preparing) return;
        preparing = false;
        prepared = commandCount > 0 && maxRegion >= 0;
    }

    /** Wraps {@code drawChunkLayer}; {@code draw} is Sodium's own. */
    public static void draw(RenderPass pass, ChunkSectionLayerGroup group, ChunkRenderMatrices matrices, double x, double y, double z, Runnable draw) {
        if (!CULL || broken || shadow || group != ChunkSectionLayerGroup.OPAQUE) {
            draw.run();
            return;
        }
        levelPass = pass.getClass().getName().equals(LEVEL_PASS);
        if (!prepared || !levelPass) {
            draw.run();
            return;
        }
        prepared = false;
        int drawn = 0;
        try {
            drawn = cull(matrices, x, y, z, draw);
        } catch (RuntimeException e) {
            broken = true;
            VultrumClient.LOGGER.error("GPU occlusion cull stopped", e);
        }
        if (drawn == 0) draw.run();
    }

    /** Returns how many times it ran Sodium's draw. */
    private static int cull(ChunkRenderMatrices matrices, double x, double y, double z, Runnable draw) {
        GpuDevice device = RenderSystem.getDevice();
        var encoder = device.createCommandEncoder();
        if (!(((FrontendGpuDeviceAccessor) device).vultrum$backend() instanceof VulkanDevice vulkan))
            return stop("the device is not Vulkan");
        VulkanCommandEncoder vk = vulkan.createCommandEncoder();
        var access = (VulkanCommandEncoderAccessor) vk;
        if (!(DefaultTerrainRenderPasses.SOLID.getTarget().getDepthTextureView() instanceof VulkanGpuTextureView depth))
            return stop("main depth is not a Vulkan texture view");
        int width = depth.texture().getWidth(0), height = depth.texture().getHeight(0);
        var pyramid = HizLevels.of(width, height);
        if (cullPipeline == 0 && !computeSupported(vulkan))
            return stop("compute shaders lack subgroup arithmetic or " + HizLevels.GROUP + "-wide workgroups");
        ensurePipelines(vulkan);
        ensureBuffers(device, pyramid.texels());
        if (!pinned) frame++;
        culls++;
        statCulls++;
        vulkanEncoder = vk;

        // Header and per-command sections.
        var viewProjection = new Matrix4f(matrices.projection()).mul(matrices.modelView());
        int cx = (int) Math.floor(x), cy = (int) Math.floor(y), cz = (int) Math.floor(z);
        int bytes = CullFrame.BYTES + commandCount * CullFrame.SECTION_BYTES;
        upload.clear();
        // Pinned frames pose as the frame after the last unpinned one, so phase 1 draws what that frame recorded.
        new CullFrame(viewProjection, cx, cy, cz, pinned, (float) (x - cx), (float) (y - cy), (float) (z - cz),
                pinned ? frame + 1 : frame, firstCommand, commandCount, pyramid.levels(), width, height,
                pyramid.width0(), pyramid.height0()).pack(upload);
        upload.position(CullFrame.BYTES);
        upload.asIntBuffer().put(sections, 0, commandCount * 4);
        upload.position(0).limit(bytes);
        // Also ends the terrain pass Vitrail holds open and suspends its level pass.
        encoder.writeToBuffer(frameData.slice(0, bytes), upload);
        if (access.vultrum$currentRenderPass() != null) return stop("a render pass is still open before phase 1");

        VkCommandBuffer commands = access.vultrum$commandBuffer();
        long ring = ((VulkanGpuBuffer) context.mappedView.slice().buffer()).vkBuffer();
        int slot = culls % READBACK_SLOTS;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (clearVisibility) {
                vkCmdFillBuffer(commands, vkBuffer(visibility), 0, VK_WHOLE_SIZE, 0);
                clearVisibility = false;
            }
            vkCmdFillBuffer(commands, vkBuffer(counters), 0, VK_WHOLE_SIZE, 0);
            barrier(commands, stack, VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
            vkCmdBindPipeline(commands, VK_PIPELINE_BIND_POINT_COMPUTE, cullPipeline);
            pushDescriptors(commands, stack, ring, depth);
            pushMode(commands, stack, 0, 0);
            vkCmdDispatch(commands, Math.ceilDiv(commandCount, CULL_GROUP), 1, 1);
            barrier(commands, stack, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                    VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT, VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT);
        }
        draw.run();

        encoder.writeToBuffer(frameData.slice(frameData.size() - FLUSH_BYTES, FLUSH_BYTES), flush.clear());
        if (access.vultrum$currentRenderPass() != null) {
            stop("a render pass is still open after phase 1");
            return 1;
        }
        commands = access.vultrum$commandBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var barriers = VkMemoryBarrier2.calloc(2, stack);
            barriers.get(0).sType$Default()
                    .srcStageMask(VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT)
                    .srcAccessMask(VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT).dstAccessMask(VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
            // Phase 2 rewrites the instance counts phase 1's draws read.
            barriers.get(1).sType$Default()
                    .srcStageMask(VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT).srcAccessMask(0)
                    .dstStageMask(VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT).dstAccessMask(VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(commands, VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(barriers));

            vkCmdBindPipeline(commands, VK_PIPELINE_BIND_POINT_COMPUTE, hizPipeline);
            pushDescriptors(commands, stack, ring, depth);
            pushMode(commands, stack, 0, 0);
            vkCmdDispatch(commands, Math.ceilDiv(pyramid.width0(), HizLevels.TILE), Math.ceilDiv(pyramid.height0(), HizLevels.TILE), 1);
            int tail = pyramid.tailLevel();
            for (int level = HizLevels.TILE_LEVELS; level < tail; level++) {
                hizBarrier(commands, stack);
                pushMode(commands, stack, 1, level);
                vkCmdDispatch(commands, (int) Math.ceilDiv(pyramid.levelTexels(level), HizLevels.GROUP), 1, 1);
            }
            if (tail < pyramid.levels()) {
                hizBarrier(commands, stack);
                pushMode(commands, stack, 2, tail);
                vkCmdDispatch(commands, 1, 1, 1);
            }
            hizBarrier(commands, stack);

            vkCmdBindPipeline(commands, VK_PIPELINE_BIND_POINT_COMPUTE, cullPipeline); // same layout: the descriptors stay
            pushMode(commands, stack, 1, 0);
            vkCmdDispatch(commands, Math.ceilDiv(commandCount, CULL_GROUP), 1, 1);
            // Instance counts to the draw, counters to the copy; the depth reads finish before phase 2 writes depth.
            barrier(commands, stack, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                    VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT | VK_PIPELINE_STAGE_2_TRANSFER_BIT
                            | VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT,
                    VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_2_TRANSFER_READ_BIT);
            if (readbackSubmit[slot] == 0) { // else the slot is still in flight: skip this frame's counters
                vkCmdCopyBuffer(commands, vkBuffer(counters), vkBuffer(readback[slot]),
                        VkBufferCopy.calloc(1, stack).srcOffset(0).dstOffset(0).size(CullCounters.BYTES));
                barrier(commands, stack, VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                        VK_PIPELINE_STAGE_2_HOST_BIT, VK_ACCESS_2_HOST_READ_BIT);
                readbackSubmit[slot] = access.vultrum$currentSubmitIndex();
            }
        }
        draw.run();
        return 2;
    }

    private static int stop(String why) {
        broken = true;
        VultrumClient.LOGGER.error("GPU occlusion cull stopped: {}", why);
        return 0;
    }

    /** Adds the counters of each readback whose submit has finished, and feeds them to the gate. */
    private static void collect() {
        if (vulkanEncoder == null) return;
        long completed = ((VulkanCommandEncoderAccessor) vulkanEncoder).vultrum$completedSubmitIndex();
        for (int slot = 0; slot < READBACK_SLOTS; slot++) {
            if (readbackSubmit[slot] == 0 || readbackSubmit[slot] > completed) continue;
            CullCounters counts;
            try (var view = readback[slot].map(true, false)) {
                counts = CullCounters.unpack(view.data().order(ByteOrder.LITTLE_ENDIAN));
            }
            totals = totals.plus(counts);
            statFrames++;
            readbackSubmit[slot] = 0;
            gate.hidden(counts.hiddenTriangles());
        }
    }

    /**
     * Whether Vitrail's pack file asks for a shader pack, read with Vitrail's own parser. Sodium picks its backend
     * once per launch, so with shaders off at launch the cull stays off rather than force the indirect backend
     * for a level pass that never runs. Enabling a pack in game needs a restart to get the cull.
     */
    private static boolean vitrailWantsPack() {
        // PackChoice.packFile's path; loading PackChoice itself would link game classes this early.
        Path file = FabricLoader.getInstance().getGameDir().resolve("vitrail").resolve("pack.txt");
        try {
            var packFile = Class.forName("dev.vitrail.settings.PackFile");
            return (boolean) packFile.getMethod("wantsPack").invoke(packFile.getMethod("read", Path.class).invoke(null, file));
        } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
            VultrumClient.LOGGER.warn("Can't read Vitrail's pack file, so the GPU occlusion cull assumes a shader pack", e);
            return true;
        }
    }

    private static boolean computeSupported(VulkanDevice vulkan) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var subgroup = VkPhysicalDeviceSubgroupProperties.calloc(stack).sType$Default();
            var properties = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(subgroup);
            vkGetPhysicalDeviceProperties2(vulkan.vkDevice().getPhysicalDevice(), properties);
            int needed = VK_SUBGROUP_FEATURE_BASIC_BIT | VK_SUBGROUP_FEATURE_ARITHMETIC_BIT;
            var limits = properties.properties().limits();
            return (subgroup.supportedStages() & VK_SHADER_STAGE_COMPUTE_BIT) != 0 && (subgroup.supportedOperations() & needed) == needed
                    && limits.maxComputeWorkGroupInvocations() >= HizLevels.GROUP && limits.maxComputeWorkGroupSize(0) >= HizLevels.GROUP;
        }
    }

    private static void hizBarrier(VkCommandBuffer commands, MemoryStack stack) {
        barrier(commands, stack, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_READ_BIT);
    }

    private static void barrier(VkCommandBuffer commands, MemoryStack stack, long srcStage, long srcAccess, long dstStage, long dstAccess) {
        var barrier = VkMemoryBarrier2.calloc(1, stack).sType$Default()
                .srcStageMask(srcStage).srcAccessMask(srcAccess).dstStageMask(dstStage).dstAccessMask(dstAccess);
        KHRSynchronization2.vkCmdPipelineBarrier2KHR(commands, VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(barrier));
    }

    private static void pushDescriptors(VkCommandBuffer commands, MemoryStack stack, long ring, VulkanGpuTextureView depth) {
        long[] buffers = {vkBuffer(frameData), ring, vkBuffer(visibility), vkBuffer(hiz), vkBuffer(counters)};
        var writes = VkWriteDescriptorSet.calloc(buffers.length + 1, stack);
        for (int i = 0; i < buffers.length; i++) {
            writes.get(i).sType$Default().dstBinding(i).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack).buffer(buffers[i]).offset(0).range(VK_WHOLE_SIZE));
        }
        writes.get(buffers.length).sType$Default().dstBinding(buffers.length).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE)
                .pImageInfo(VkDescriptorImageInfo.calloc(1, stack).imageView(depth.vkImageView()).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
        KHRPushDescriptor.vkCmdPushDescriptorSetKHR(commands, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, writes);
    }

    private static void pushMode(VkCommandBuffer commands, MemoryStack stack, int mode, int level) {
        vkCmdPushConstants(commands, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, stack.ints(mode, level));
    }

    private static long vkBuffer(GpuBuffer buffer) {
        return ((VulkanGpuBuffer) buffer).vkBuffer();
    }

    private static void ensureBuffers(GpuDevice device, long hizTexels) {
        // Indirect-parameter usage makes them storage buffers (VulkanConstMixin).
        int storage = GpuBuffer.USAGE_INDIRECT_PARAMETERS;
        long frameBytes = CullFrame.BYTES + (long) commandCount * CullFrame.SECTION_BYTES + FLUSH_BYTES;
        if (frameData == null || frameData.size() < frameBytes) {
            long size = frameData == null ? frameBytes : Math.max(frameBytes, frameData.size() * 2);
            if (frameData != null) frameData.close();
            frameData = device.createBuffer(() -> "Vultrum cull frame", storage | GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_COPY_SRC, size);
            if (upload != null) MemoryUtil.memFree(upload);
            upload = MemoryUtil.memAlloc((int) size).order(ByteOrder.LITTLE_ENDIAN);
        }
        long visibilityBytes = (long) (maxRegion + 1) * CullBatch.SECTIONS_PER_REGION * Integer.BYTES;
        if (visibility == null || visibility.size() < visibilityBytes) {
            long size = visibility == null ? visibilityBytes : Math.max(visibilityBytes, visibility.size() * 2);
            if (visibility != null) visibility.close();
            visibility = device.createBuffer(() -> "Vultrum cull visibility", storage | GpuBuffer.USAGE_COPY_DST, size);
            clearVisibility = true;
        }
        if (hiz == null || hiz.size() < hizTexels * Float.BYTES) {
            if (hiz != null) hiz.close();
            hiz = device.createBuffer(() -> "Vultrum cull Hi-Z", storage, hizTexels * Float.BYTES);
        }
        if (counters == null) {
            counters = device.createBuffer(() -> "Vultrum cull counters", storage | GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_COPY_SRC, CullCounters.BYTES);
            for (int i = 0; i < READBACK_SLOTS; i++)
                readback[i] = device.createBuffer(() -> "Vultrum cull counter readback", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, CullCounters.BYTES);
            flush = MemoryUtil.memCalloc(FLUSH_BYTES);
        }
    }

    private static void ensurePipelines(VulkanDevice vulkan) {
        if (cullPipeline != 0) return;
        VkDevice device = vulkan.vkDevice();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var bindings = VkDescriptorSetLayoutBinding.calloc(6, stack);
            for (int i = 0; i < 6; i++)
                bindings.get(i).binding(i).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT)
                        .descriptorType(i < 5 ? VK_DESCRIPTOR_TYPE_STORAGE_BUFFER : VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE);
            LongBuffer handle = stack.mallocLong(1);
            check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default()
                    .flags(KHRPushDescriptor.VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR).pBindings(bindings), null, handle), "set layout");
            setLayout = handle.get(0);
            var push = VkPushConstantRange.calloc(1, stack).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(8);
            check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(setLayout)).pPushConstantRanges(push), null, handle), "pipeline layout");
            pipelineLayout = handle.get(0);
            hizPipeline = pipeline(device, stack, "occlusion_hiz");
            cullPipeline = pipeline(device, stack, "occlusion_cull");
        }
    }

    private static long pipeline(VkDevice device, MemoryStack stack, String name) {
        byte[] spirv;
        try (InputStream in = GpuOcclusionCull.class.getResourceAsStream("/assets/vultrum/shaders/" + name + ".spv")) {
            if (in == null) throw new IllegalStateException("missing shader " + name);
            spirv = in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("cannot read shader " + name, e);
        }
        ByteBuffer code = MemoryUtil.memAlloc(spirv.length).put(spirv).flip();
        LongBuffer handle = stack.mallocLong(1);
        try {
            check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code), null, handle), name);
        } finally {
            MemoryUtil.memFree(code);
        }
        long module = handle.get(0);
        var stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                .stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
        var info = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default().stage(stage).layout(pipelineLayout);
        check(vkCreateComputePipelines(device, VK_NULL_HANDLE, info, null, handle), name);
        vkDestroyShaderModule(device, module, null);
        return handle.get(0);
    }

    private static void check(int result, String what) {
        if (result != VK_SUCCESS) throw new IllegalStateException("Vulkan error " + result + " creating " + what);
    }

    /**
     * Benchmark only. While pinned, phase 1 keeps drawing the sections visible in the last unpinned frame
     * and phase 2 records nothing, so after a camera jump every frame is the first frame after the jump.
     */
    public static void pinHistory(boolean pin) {
        pinned = pin;
    }

    /** Benchmark only: true culls every frame, false none (the visual check's reference), null leaves it to the gate. */
    public static void override(Boolean cull) {
        override = cull;
    }

    /** Before a benchmark case records. */
    public static void resetStats() {
        totals = CullCounters.ZERO;
        statFrames = statPrepares = statCulls = 0;
    }

    /** Per-culled-frame means of the GPU counters over the recording, or null when the cull is off. */
    public static Map<String, Object> stats() {
        if (!CULL) return null;
        var stats = new LinkedHashMap<String, Object>();
        stats.put("frames", statFrames);
        stats.put("broken", broken);
        stats.put("culledFramePct", statPrepares == 0 ? 0 : Math.round(1000.0 * statCulls / statPrepares) / 10.0);
        stats.put("gateOn", gate.on());
        stats.put("commands", mean(totals.commands()));
        stats.put("phase1Commands", mean(totals.phase1Commands()));
        stats.put("phase2Commands", mean(totals.phase2Commands()));
        stats.put("triangles", mean(totals.triangles()));
        stats.put("phase1Triangles", mean(totals.phase1Triangles()));
        stats.put("phase2Triangles", mean(totals.phase2Triangles()));
        long drawn = totals.phase1Triangles() + totals.phase2Triangles();
        stats.put("drawnTrianglePct", totals.triangles() == 0 ? 0 : Math.round(1000.0 * drawn / totals.triangles()) / 10.0);
        return stats;
    }

    private static long mean(long total) {
        return statFrames == 0 ? 0 : Math.round((double) total / statFrames);
    }
}
