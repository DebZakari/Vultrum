package dev.vultrum.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import dev.vultrum.cull.GpuOcclusionCull;
import org.lwjgl.vulkan.VK10;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Renderpearl has no storage-buffer usage. The GPU cull writes Sodium's indirect commands from a compute
 * shader and keeps its own data in storage buffers, so indirect-argument buffers also get storage usage.
 */
@Mixin(VulkanConst.class)
abstract class VulkanConstMixin {
    @ModifyReturnValue(method = "bufferUsageToVk", at = @At("RETURN"))
    private static int vultrum$storage(int vk, @Local(argsOnly = true) int usage) {
        return GpuOcclusionCull.CULL && (usage & GpuBuffer.USAGE_INDIRECT_PARAMETERS) != 0 ? vk | VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT : vk;
    }
}
