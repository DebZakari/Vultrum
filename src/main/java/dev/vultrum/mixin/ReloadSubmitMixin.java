package dev.vultrum.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.vulkan.DestructionQueue;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import dev.vultrum.bench.ReloadProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(VulkanCommandEncoder.class)
abstract class ReloadSubmitMixin {
    @WrapOperation(method = "submit", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/backend/vulkan/DestructionQueue;rotate()Z"))
    private boolean vultrum$rotate(DestructionQueue<?> queue, Operation<Boolean> original) {
        long start = System.nanoTime();
        try { return original.call(queue); }
        finally { ReloadProbe.rotate(System.nanoTime() - start); }
    }
}
