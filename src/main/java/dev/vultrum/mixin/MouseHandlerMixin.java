package dev.vultrum.mixin;

import dev.vultrum.bench.Bench;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Drops mouse events during a benchmark run so a stray mouse move can't turn the camera. */
@Mixin(MouseHandler.class)
abstract class MouseHandlerMixin {
    @Inject(method = {"onButton", "onScroll", "onMove"}, at = @At("HEAD"), cancellable = true)
    private void vultrum$lockInput(CallbackInfo ci) {
        if (Bench.locksInput()) ci.cancel();
    }
}
