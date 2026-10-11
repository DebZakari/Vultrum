package dev.vultrum.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.vultrum.bench.Bench;
import dev.vultrum.bench.FrameTimer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
    @WrapMethod(method = "render")
    private void vultrum$timeFrame(Operation<Void> original) {
        FrameTimer.beginFrame();
        try { original.call(); }
        finally { FrameTimer.endFrame(); }
    }

    @WrapMethod(method = "extract")
    private void vultrum$timeExtract(DeltaTracker deltaTracker, boolean advanceGameTime, Operation<Void> original) {
        // The camera is set up in extract, so a benchmark's camera motion goes first.
        Bench.frame(Minecraft.getInstance());
        FrameTimer.beginExtract();
        try { original.call(deltaTracker, advanceGameTime); }
        finally { FrameTimer.endExtract(); }
    }
}
