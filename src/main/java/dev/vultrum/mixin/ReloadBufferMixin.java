package dev.vultrum.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer;
import dev.vultrum.bench.ReloadProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VulkanGpuBuffer.Direct.class)
abstract class ReloadBufferMixin {
    @WrapOperation(method = "destroy", at = @At(value = "INVOKE", target = "Lorg/lwjgl/util/vma/Vma;vmaDestroyBuffer(JJJ)V"))
    private void vultrum$destroy(long allocator, long buffer, long allocation, Operation<Void> original) {
        long start = System.nanoTime();
        try { original.call(allocator, buffer, allocation); }
        finally { ReloadProbe.destroyed((GpuBuffer) (Object) this, System.nanoTime() - start); }
    }
    @Inject(method = "close", at = @At("HEAD"))
    private void vultrum$close(CallbackInfo info) {
        var buffer = (GpuBuffer) (Object) this;
        if (!buffer.isClosed()) ReloadProbe.closed(buffer);
    }
}
