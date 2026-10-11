package dev.vultrum.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.vultrum.bench.FrameTimer;
import dev.vultrum.bench.FrameTimer.LoopPhase;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Times the client's light work, which {@code Minecraft.renderFrame} runs between the ticks and extract: the
 * queued light packets, then the light engine. Optional, because light-engine mods may rewrite this method,
 * and a bench timer must not keep the game from starting.
 */
@Mixin(ClientLevel.class)
abstract class ClientLevelMixin {
    @WrapOperation(method = "update", require = 0, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;pollLightUpdates()V"))
    private void vultrum$timeLightQueue(ClientLevel level, Operation<Void> original) {
        long start = FrameTimer.beginLoopPhase();
        try { original.call(level); }
        finally { FrameTimer.endLoopPhase(LoopPhase.LIGHT_QUEUE, start); }
    }

    @WrapOperation(method = "update", require = 0, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/lighting/LevelLightEngine;runLightUpdates()I"))
    private int vultrum$timeLightEngine(LevelLightEngine engine, Operation<Integer> original) {
        long start = FrameTimer.beginLoopPhase();
        try { return original.call(engine); }
        finally { FrameTimer.endLoopPhase(LoopPhase.LIGHT_ENGINE, start); }
    }
}
