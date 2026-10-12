package dev.vultrum.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import dev.vultrum.cull.GpuOcclusionCull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Retire cull handles before Renderpearl drains the encoder and destroys the Vulkan device. */
@Mixin(VulkanDevice.class)
abstract class VulkanCullLifetimeMixin {
    @Inject(method = "close", at = @At("HEAD"))
    private void vultrum$closeCull(CallbackInfo ci) { GpuOcclusionCull.close(); }
}
