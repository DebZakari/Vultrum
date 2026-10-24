package dev.vultrum.cull;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import dev.vultrum.VultrumClient;
import dev.vultrum.VultrumConfig;
import dev.vultrum.LaunchHooks;
import dev.vultrum.mixin.FrontendGpuDeviceAccessor;
import dev.vultrum.mixin.VKIndirectDrawBatchAccessor;
import dev.vultrum.mixin.VulkanCommandEncoderAccessor;
import net.caffeinemc.mods.sodium.client.gpu.device.batch.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.gpu.device.backend.DrawBackend;
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
import java.nio.file.Files;
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
    public static final boolean CULL = VultrumConfig.get().occlusionCull() && LaunchHooks.get().vitrail()
            && vitrailWantsPack();

    private static final int COMMAND_BYTES = 20; // VkDrawIndexedIndirectCommand
    private static final int FLUSH_BYTES = 16;
    private static final int CULL_GROUP = 64;
    private static final int READBACK_SLOTS = 4;
    private static final String LEVEL_PASS = "dev.vitrail.render.LevelPass";

    private static boolean shadow, broken;
    private static boolean levelPass = true;
    private static final CullPreparation preparation = new CullPreparation();
    private static CullPipelines pipelines;
    private static CullResources resources;
    private static ComputePath computePath;
    private static VulkanCommandEncoder vulkanEncoder;
    private static final CullGate gate = new CullGate();
    private static boolean pinned;
    private static Boolean override;
    private static int frame = 1, culls;
    private static String fallback = CULL ? null : launchReason();
    private static final CullFaults faults = CullFaults.read(FabricLoader.getInstance().getGameDir().resolve("vultrum/cull-failure"),
            System.getProperty("vultrum.bench") != null);
    private static boolean recoveryChecked;

    private static CullCounters totals = CullCounters.ZERO;
    private static long statFrames, statPrepares, statCulls;

    private GpuOcclusionCull() { }

    /** Vitrail draws its shadow pass, which prepares and draws Sodium's commands for the light's view. */
    public static void shadow(boolean drawing) {
        shadow = drawing;
    }

    /** Owns the main-view preparation scope, including interruption and skipped frames. */
    public static void prepare(Runnable original) {
        if (shadow) { original.run(); return; }
        if (resources != null) {
            try { collect(); }
            catch (RuntimeException failure) { stop("readback failure: " + failure); close(); }
        }
        if (CULL && !broken) statPrepares++;
        boolean cull = override != null ? override : pinned || gate.cull();
        preparation.run(CULL && !broken && levelPass && cull, () -> {
            original.run();
            faults.hit("prepare-interrupted");
        });
    }

    /** Sodium copied this batch's commands into its indirect ring. */
    public static void prepared(MultiDrawBatch batch, TerrainRenderPass pass, RenderRegion region, DrawContext drawContext) {
        if (!(batch instanceof VKIndirectDrawBatch) || !(drawContext instanceof VKIndirectContext)) return;
        long offset = ((VKIndirectDrawBatchAccessor) batch).vultrum$offset();
        int command = offset < 0 || offset % COMMAND_BYTES != 0 ? -1 : Math.toIntExact(offset / COMMAND_BYTES);
        var recorded = (CullBatch) batch;
        preparation.append(drawContext, command, batch.size, region.getId(), pass.isTranslucent(),
                recorded.vultrum$region(), recorded.vultrum$sections());
    }

    /** Wraps {@code drawChunkLayer}; {@code draw} is Sodium's own. */
    public static void draw(RenderPass pass, ChunkSectionLayerGroup group, ChunkRenderMatrices matrices, double x, double y, double z, Runnable draw) {
        if (!CULL || broken || shadow || group != ChunkSectionLayerGroup.OPAQUE) {
            draw.run();
            return;
        }
        levelPass = pass.getClass().getName().equals(LEVEL_PASS);
        if (!levelPass || !preparation.take()) {
            draw.run();
            return;
        }
        try { new CullExecution().run(new VulkanCommands(matrices, x, y, z), draw); }
        finally { if (broken) close(); }
    }

    private static final class VulkanCommands implements CullExecution.Commands {
        private final ChunkRenderMatrices matrices;
        private final double x, y, z;
        private GpuDevice device;
        private CommandEncoder encoder;
        private VulkanCommandEncoder vk;
        private VulkanCommandEncoderAccessor access;
        private VulkanGpuTextureView depth;
        private HizLevels pyramid;
        private VkCommandBuffer commands;
        private long ring;
        private int slot;

        VulkanCommands(ChunkRenderMatrices matrices, double x, double y, double z) {
            this.matrices = matrices; this.x = x; this.y = y; this.z = z;
        }

        @Override public void prepare() {
            device = RenderSystem.getDevice();
            encoder = device.createCommandEncoder();
            if (!(((FrontendGpuDeviceAccessor) device).vultrum$backend() instanceof VulkanDevice vulkan))
                throw unavailable("unsupported hardware: device is not Vulkan");
            vk = vulkan.createCommandEncoder();
            vulkanEncoder = vk;
            access = (VulkanCommandEncoderAccessor) vk;
            if (!(DefaultTerrainRenderPasses.SOLID.getTarget().getDepthTextureView() instanceof VulkanGpuTextureView depthView))
                throw unavailable("unsupported render target: main depth is not Vulkan");
            depth = depthView;
            int width = depth.texture().getWidth(0), height = depth.texture().getHeight(0);
            pyramid = HizLevels.of(width, height);
            if (pipelines == null) {
                computePath = computePath(vulkan);
                faults.hit("unsupported-compute");
                if (computePath == null) throw unavailable("unsupported hardware: cull needs " + ComputePath.MIN_GROUP
                        + "-wide compute workgroups and six storage-buffer descriptors");
            }
            ensurePipelines(vulkan);
            ensureResources(device, pyramid.texels());
            if (!pinned) frame++;
            culls++;
            statCulls++;
            vulkanEncoder = vk;

            // Header and per-command sections.
            var viewProjection = new Matrix4f(matrices.projection()).mul(matrices.modelView());
            int cx = (int) Math.floor(x), cy = (int) Math.floor(y), cz = (int) Math.floor(z);
            int bytes = CullFrame.BYTES + preparation.count * CullFrame.SECTION_BYTES;
            resources.upload.clear();
            // Pinned frames pose as the frame after the last unpinned one, so phase 1 draws what that frame recorded.
            new CullFrame(viewProjection, cx, cy, cz, pinned, (float) (x - cx), (float) (y - cy), (float) (z - cz),
                    pinned ? frame + 1 : frame, preparation.first, preparation.count, pyramid.levels(), width, height,
                    pyramid.width0(), pyramid.height0()).pack(resources.upload);
            resources.upload.position(CullFrame.BYTES);
            resources.upload.asIntBuffer().put(preparation.sections, 0, preparation.count * 4);
            resources.upload.position(0).limit(bytes);
            // Also ends the terrain pass Vitrail holds open and suspends its level pass.
            encoder.writeToBuffer(resources.frame.slice(0, bytes), resources.upload);
            if (access.vultrum$currentRenderPass() != null) throw unavailable("runtime failure: render pass still open before phase 1");
            if (faults.checkRecovery() && !recoveryChecked) {
                recoveryChecked = true;
                CullRecoveryCheck.record(device, vk, depth, pipelines);
            }
            ring = ((VulkanGpuBuffer) ((VKIndirectContext) preparation.context).mappedView.slice().buffer()).vkBuffer();
            slot = culls % READBACK_SLOTS;
            faults.hit("before-phase1");
        }

        @Override public void phase1() {
            commands = access.vultrum$commandBuffer();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                if (resources.clearVisibility) {
                    vkCmdFillBuffer(commands, vkBuffer(resources.visibility), 0, VK_WHOLE_SIZE, 0);
                    resources.clearVisibility = false;
                }
                vkCmdFillBuffer(commands, vkBuffer(resources.counters), 0, VK_WHOLE_SIZE, 0);
                barrier(commands, stack, VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                        VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
                vkCmdBindPipeline(commands, VK_PIPELINE_BIND_POINT_COMPUTE, pipelines.cull);
                pushDescriptors(commands, stack, ring, depth);
                pushMode(commands, stack, 0, 0);
                vkCmdDispatch(commands, Math.ceilDiv(preparation.count, CULL_GROUP), 1, 1);
                barrier(commands, stack, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                        VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT, VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT);
            }
            faults.hit("after-phase1-commands");
        }

        @Override public void phase2() {
            faults.hit("after-phase1-draw");

            encoder.writeToBuffer(resources.frame.slice(resources.frame.size() - FLUSH_BYTES, FLUSH_BYTES), resources.flush.clear());
            if (access.vultrum$currentRenderPass() != null) {
                throw unavailable("runtime failure: render pass still open after phase 1");
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

                vkCmdBindPipeline(commands, VK_PIPELINE_BIND_POINT_COMPUTE, pipelines.hiz);
                pushDescriptors(commands, stack, ring, depth);
                pushMode(commands, stack, 0, 0);
                vkCmdDispatch(commands, Math.ceilDiv(pyramid.width0(), HizLevels.TILE), Math.ceilDiv(pyramid.height0(), HizLevels.TILE), 1);
                int tail = pyramid.tailLevel();
                for (int level = HizLevels.TILE_LEVELS; level < tail; level++) {
                    hizBarrier(commands, stack);
                    pushMode(commands, stack, 1, level);
                    vkCmdDispatch(commands, (int) Math.ceilDiv(pyramid.levelTexels(level), computePath.hizGroup()), 1, 1);
                }
                if (tail < pyramid.levels()) {
                    hizBarrier(commands, stack);
                    pushMode(commands, stack, 2, tail);
                    vkCmdDispatch(commands, 1, 1, 1);
                }
                hizBarrier(commands, stack);

                vkCmdBindPipeline(commands, VK_PIPELINE_BIND_POINT_COMPUTE, pipelines.cull); // same layout: the descriptors stay
                pushMode(commands, stack, 1, 0);
                vkCmdDispatch(commands, Math.ceilDiv(preparation.count, CULL_GROUP), 1, 1);
                // Instance counts to the draw, counters to the copy; depth reads finish before phase 2 writes depth.
                barrier(commands, stack, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                        VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT | VK_PIPELINE_STAGE_2_TRANSFER_BIT
                                | VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT,
                        VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_2_TRANSFER_READ_BIT);
            }
            faults.hit("after-phase2-commands");
        }

        @Override public void readback() {
            commands = access.vultrum$commandBuffer();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                if (resources.reads.free(slot)) { // else the slot is still in flight: skip this frame's counters
                    vkCmdCopyBuffer(commands, vkBuffer(resources.counters), vkBuffer(resources.readback[slot]),
                            VkBufferCopy.calloc(1, stack).srcOffset(0).dstOffset(0).size(CullCounters.BYTES));
                    barrier(commands, stack, VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                            VK_PIPELINE_STAGE_2_HOST_BIT, VK_ACCESS_2_HOST_READ_BIT);
                    resources.reads.recorded(slot, access.vultrum$currentSubmitIndex());
                }
            }
            faults.hit("after-readback");
        }

        @Override public void recover(boolean phase1Drawn) {
            // End/suspend any terrain pass before recording compute. Never use Hi-Z during recovery.
            encoder.writeToBuffer(resources.frame.slice(resources.frame.size() - FLUSH_BYTES, FLUSH_BYTES), resources.flush.clear());
            commands = access.vultrum$commandBuffer();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                barrier(commands, stack, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
                        VK_ACCESS_2_MEMORY_WRITE_BIT | VK_ACCESS_2_MEMORY_READ_BIT,
                        VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                        VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
                vkCmdBindPipeline(commands, VK_PIPELINE_BIND_POINT_COMPUTE, pipelines.cull);
                pushDescriptors(commands, stack, ring, depth);
                pushMode(commands, stack, phase1Drawn ? 3 : 2, 0);
                vkCmdDispatch(commands, Math.ceilDiv(preparation.count, CULL_GROUP), 1, 1);
                barrier(commands, stack, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                        VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT, VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT);
            }
        }

        @Override public void failed(RuntimeException failure) {
            String reason = failure.getMessage();
            stop(reason != null && reason.startsWith("unsupported") ? reason : "runtime failure: " + reason);
            VultrumClient.LOGGER.error("GPU occlusion cull failure", failure);
        }
    }

    private static IllegalStateException unavailable(String reason) { return new IllegalStateException(reason); }

    private static void stop(String why) {
        broken = true;
        fallback = why;
        VultrumClient.LOGGER.warn("GPU occlusion cull requested={}, effective=off, reason={}", VultrumConfig.get().occlusionCull(), why);
    }

    /** Frees host memory now and retires GPU buffers/handles through Renderpearl's completion queue. */
    public static void close() {
        preparation.discard();
        if (resources != null) { resources.close(); resources = null; }
        if (pipelines != null) { pipelines.close(); pipelines = null; }
    }

    private static void collect() {
        if (vulkanEncoder == null || resources == null) return;
        long completed = ((VulkanCommandEncoderAccessor) vulkanEncoder).vultrum$completedSubmitIndex();
        resources.reads.collect(completed, slot -> {
            CullCounters counts;
            try (var view = resources.readback[slot].map(true, false)) {
                counts = CullCounters.unpack(view.data().order(ByteOrder.LITTLE_ENDIAN));
            }
            totals = totals.plus(counts);
            statFrames++;
            gate.hidden(counts.hiddenTriangles());
        });
    }

    private static String launchReason() {
        if (!VultrumConfig.get().occlusionCull()) return "disabled setting";
        if (!LaunchHooks.get().vitrail()) return "unsupported or absent Sodium/Vitrail version";
        return "shader pack disabled or unreadable at launch; restart after enabling a pack";
    }

    public static void logState() {
        VultrumClient.LOGGER.info("GPU occlusion cull requested={}, effective={}, reason={}",
                VultrumConfig.get().occlusionCull(), CULL && !broken ? (pipelines == null ? "pending device validation" : "on") : "off",
                fallback == null ? "supported launch configuration" : fallback);
    }

    /** Keep backend selection and unsupported-device diagnostics with the execution owner. */
    public static DrawBackend backend(DrawBackend chosen, boolean multiDrawIndirect) {
        if (!CULL) return chosen;
        if (chosen == DrawBackend.VK_MULTIDRAW && multiDrawIndirect) return DrawBackend.VK_INDIRECT;
        if (chosen != DrawBackend.VK_INDIRECT) stop("unsupported hardware: Sodium backend " + chosen + ", multiDrawIndirect=" + multiDrawIndirect);
        return chosen;
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
            VultrumClient.LOGGER.warn("GPU occlusion cull off: cannot read shader pack launch state", e);
            return false;
        }
    }

    private static ComputePath computePath(VulkanDevice vulkan) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var subgroup = VkPhysicalDeviceSubgroupProperties.calloc(stack).sType$Default();
            var properties = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(subgroup);
            vkGetPhysicalDeviceProperties2(vulkan.vkDevice().getPhysicalDevice(), properties);
            int needed = VK_SUBGROUP_FEATURE_BASIC_BIT | VK_SUBGROUP_FEATURE_ARITHMETIC_BIT;
            var limits = properties.properties().limits();
            if (limits.maxPerStageDescriptorStorageBuffers() < 6 || limits.maxDescriptorSetStorageBuffers() < 6) return null;
            boolean arithmetic = (subgroup.supportedStages() & VK_SHADER_STAGE_COMPUTE_BIT) != 0
                    && (subgroup.supportedOperations() & needed) == needed;
            int invocations = limits.maxComputeWorkGroupInvocations(), sizeX = limits.maxComputeWorkGroupSize(0);
            // Benchmark only: exercise both fallback variants on a device that normally takes the fast path.
            if (System.getProperty("vultrum.bench") != null && Files.exists(
                    FabricLoader.getInstance().getGameDir().resolve("vultrum/portable-compute"))) {
                arithmetic = false;
                invocations = Math.min(invocations, ComputePath.MIN_GROUP);
                sizeX = Math.min(sizeX, ComputePath.MIN_GROUP);
            }
            return ComputePath.choose(invocations, sizeX, arithmetic);
        }
    }

    private static void hizBarrier(VkCommandBuffer commands, MemoryStack stack) {
        barrier(commands, stack, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_READ_BIT);
    }

    static void barrier(VkCommandBuffer commands, MemoryStack stack, long srcStage, long srcAccess, long dstStage, long dstAccess) {
        var barrier = VkMemoryBarrier2.calloc(1, stack).sType$Default()
                .srcStageMask(srcStage).srcAccessMask(srcAccess).dstStageMask(dstStage).dstAccessMask(dstAccess);
        KHRSynchronization2.vkCmdPipelineBarrier2KHR(commands, VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(barrier));
    }

    private static void pushDescriptors(VkCommandBuffer commands, MemoryStack stack, long ring, VulkanGpuTextureView depth) {
        long[] buffers = {vkBuffer(resources.frame), ring, vkBuffer(resources.visibility), vkBuffer(resources.hiz), vkBuffer(resources.counters), vkBuffer(resources.phase1)};
        descriptors(commands, stack, pipelines.layout, buffers, depth);
    }

    static void descriptors(VkCommandBuffer commands, MemoryStack stack, long layout, long[] buffers, VulkanGpuTextureView depth) {
        var writes = VkWriteDescriptorSet.calloc(buffers.length + 1, stack);
        for (int i = 0; i < buffers.length; i++) {
            writes.get(i).sType$Default().dstBinding(i).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack).buffer(buffers[i]).offset(0).range(VK_WHOLE_SIZE));
        }
        writes.get(buffers.length).sType$Default().dstBinding(buffers.length).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE)
                .pImageInfo(VkDescriptorImageInfo.calloc(1, stack).imageView(depth.vkImageView()).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
        KHRPushDescriptor.vkCmdPushDescriptorSetKHR(commands, VK_PIPELINE_BIND_POINT_COMPUTE, layout, 0, writes);
    }

    private static void pushMode(VkCommandBuffer commands, MemoryStack stack, int mode, int level) {
        vkCmdPushConstants(commands, pipelines.layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, stack.ints(mode, level));
    }

    private static long vkBuffer(GpuBuffer buffer) {
        return ((VulkanGpuBuffer) buffer).vkBuffer();
    }

    private static void ensureResources(GpuDevice device, long hizTexels) {
        if (resources == null) resources = new CullResources(new CullResources.Allocator() {
            public GpuBuffer buffer(String name, int usage, long bytes) {
                faults.hit("buffer-" + name);
                return device.createBuffer(() -> "Vultrum cull " + name, usage, bytes);
            }
            public ByteBuffer host(int bytes) { return MemoryUtil.memAlloc(bytes); }
            public void free(ByteBuffer buffer) { MemoryUtil.memFree(buffer); }
        });
        try { resources.ensure(preparation.count, preparation.maxRegion, hizTexels); }
        catch (RuntimeException | Error failure) { resources.close(); resources = null; throw failure; }
    }

    private static void ensurePipelines(VulkanDevice vulkan) {
        if (pipelines != null) return;
        VkDevice device = vulkan.vkDevice();
        pipelines = CullPipelines.create(new CullPipelines.Driver() {
            public long descriptorLayout() {
                faults.hit("descriptor-layout");
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    var bindings = VkDescriptorSetLayoutBinding.calloc(7, stack);
                    for (int i = 0; i < 7; i++) bindings.get(i).binding(i).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT)
                            .descriptorType(i < 6 ? VK_DESCRIPTOR_TYPE_STORAGE_BUFFER : VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE);
                    LongBuffer handle = stack.callocLong(1);
                    check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default()
                            .flags(KHRPushDescriptor.VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR).pBindings(bindings), null, handle), "set layout");
                    return handle.get(0);
                }
            }
            public long pipelineLayout(long descriptors) {
                faults.hit("pipeline-layout");
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    var push = VkPushConstantRange.calloc(1, stack).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(8);
                    LongBuffer handle = stack.callocLong(1);
                    check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                            .pSetLayouts(stack.longs(descriptors)).pPushConstantRanges(push), null, handle), "pipeline layout");
                    return handle.get(0);
                }
            }
            public long shader(String name) { faults.hit("shader-" + name); return shaderModule(device, name); }
            public long pipeline(long layout, long module) {
                faults.hit("compute-pipeline");
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    var stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                            .stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
                    var info = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default().stage(stage).layout(layout);
                    LongBuffer handle = stack.callocLong(1);
                    int result = vkCreateComputePipelines(device, VK_NULL_HANDLE, info, null, handle);
                    if (result != VK_SUCCESS && handle.get(0) != VK_NULL_HANDLE) vkDestroyPipeline(device, handle.get(0), null);
                    check(result, "compute pipeline");
                    return handle.get(0);
                }
            }
            public void destroyShader(long handle) { vkDestroyShaderModule(device, handle, null); }
            public void destroyPipeline(long handle) { vkDestroyPipeline(device, handle, null); }
            public void destroyPipelineLayout(long handle) { vkDestroyPipelineLayout(device, handle, null); }
            public void destroyDescriptorLayout(long handle) { vkDestroyDescriptorSetLayout(device, handle, null); }
            public void retire(Runnable destroy) { vulkan.createCommandEncoder().queueForDestroy(destroy::run); }
        }, computePath);
        logState();
        VultrumClient.LOGGER.info("Occlusion compute: {} counters, {}-thread Hi-Z",
                computePath.subgroupCounters() ? "subgroup" : "shared-memory", computePath.hizGroup());
    }

    private static long shaderModule(VkDevice device, String name) {
        byte[] spirv;
        try (InputStream in = GpuOcclusionCull.class.getResourceAsStream("/assets/vultrum/shaders/" + name + ".spv")) {
            if (in == null) throw new IllegalStateException("missing shader " + name);
            spirv = in.readAllBytes();
        } catch (IOException e) { throw new IllegalStateException("cannot read shader " + name, e); }
        ByteBuffer code = MemoryUtil.memAlloc(spirv.length).put(spirv).flip();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer handle = stack.callocLong(1);
            check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code), null, handle), name);
            return handle.get(0);
        } finally { MemoryUtil.memFree(code); }
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
        stats.put("requested", VultrumConfig.get().occlusionCull());
        stats.put("effective", !broken && pipelines != null);
        stats.put("fallbackReason", fallback);
        stats.put("subgroupCounters", computePath == null ? null : computePath.subgroupCounters());
        stats.put("hizWorkgroup", computePath == null ? null : computePath.hizGroup());
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
