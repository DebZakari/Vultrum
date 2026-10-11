package dev.vultrum.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuSurface;
import dev.vultrum.bench.Bench;
import dev.vultrum.bench.FrameTimer;
import dev.vultrum.bench.FrameTimer.LoopPhase;
import net.minecraft.client.Minecraft;
import net.minecraft.network.PacketProcessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class MinecraftMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void vultrum$benchTick(CallbackInfo info) {
        Bench.tick((Minecraft) (Object) this);
    }

    // The frame loop between one render and the next extract, so a hitch outside both can be placed.
    @WrapOperation(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/PacketProcessor;processQueuedPackets()V"))
    private void vultrum$timePackets(PacketProcessor processor, Operation<Void> original) {
        long start = FrameTimer.beginLoopPhase();
        try { original.call(processor); }
        finally { FrameTimer.endLoopPhase(LoopPhase.PACKETS, start); }
    }

    @WrapOperation(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;runAllTasks()V"))
    private void vultrum$timeTasks(Minecraft minecraft, Operation<Void> original) {
        long start = FrameTimer.beginLoopPhase();
        try { original.call(minecraft); }
        finally { FrameTimer.endLoopPhase(LoopPhase.TASKS, start); }
    }

    @WrapOperation(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;tick()V"))
    private void vultrum$timeTick(Minecraft minecraft, Operation<Void> original) {
        long start = FrameTimer.beginLoopPhase();
        try { original.call(minecraft); }
        finally { FrameTimer.endLoopPhase(LoopPhase.TICK, start); }
    }

    @WrapOperation(method = "renderFrame", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuSurface;acquireNextTexture()V"))
    private void vultrum$timeAcquire(GpuSurface surface, Operation<Void> original) {
        long start = FrameTimer.beginLoopPhase();
        try { original.call(surface); }
        finally { FrameTimer.endLoopPhase(LoopPhase.ACQUIRE, start); }
    }

    @WrapOperation(method = "renderFrame", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;submit()V"))
    private void vultrum$timeSubmit(CommandEncoder encoder, Operation<Void> original) {
        long start = FrameTimer.beginLoopPhase();
        try { original.call(encoder); }
        finally { FrameTimer.endLoopPhase(LoopPhase.SUBMIT, start); }
    }

    @WrapOperation(method = "renderFrame", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuSurface;present()V"))
    private void vultrum$timePresent(GpuSurface surface, Operation<Void> original) {
        long start = FrameTimer.beginLoopPhase();
        try { original.call(surface); }
        finally { FrameTimer.endLoopPhase(LoopPhase.PRESENT, start); }
    }
}
