package dev.vultrum.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.device.GpuDevice;
import dev.vultrum.bench.ReloadProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import java.util.function.Supplier;

@Mixin(targets = "net.caffeinemc.mods.sodium.client.gpu.arena.ArenaAggregator")
abstract class ReloadArenaMixin {
    @WrapOperation(method = "getBufferOfSizeAtLeast", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuDevice;createBuffer(Ljava/util/function/Supplier;IJ)Lcom/mojang/renderpearl/api/buffers/GpuBuffer;"))
    private GpuBuffer vultrum$create(GpuDevice device, Supplier<String> label, int usage, long size, Operation<GpuBuffer> original) {
        GpuBuffer buffer = original.call(device, label, usage, size);
        ReloadProbe.created(buffer);
        return buffer;
    }
}
