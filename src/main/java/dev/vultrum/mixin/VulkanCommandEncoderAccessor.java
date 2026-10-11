package dev.vultrum.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(VulkanCommandEncoder.class)
public interface VulkanCommandEncoderAccessor {
    /** The frame's command buffer, begun on first use. Throws inside a render pass. */
    @Invoker("commandBuffer") VkCommandBuffer vultrum$commandBuffer();

    @Accessor("currentRenderPass") VulkanRenderPass vultrum$currentRenderPass();

    /** The submit being recorded. */
    @Accessor("currentSubmitIndex") long vultrum$currentSubmitIndex();

    /** The latest submit known to have finished; at least two behind the current one. */
    @Accessor("completedSubmitIndex") long vultrum$completedSubmitIndex();
}
