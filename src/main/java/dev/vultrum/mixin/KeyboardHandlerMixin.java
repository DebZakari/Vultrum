package dev.vultrum.mixin;

import dev.vultrum.bench.Bench;
import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Drops key events during a benchmark run so a stray keypress can't move the camera or open a menu. */
@Mixin(KeyboardHandler.class)
abstract class KeyboardHandlerMixin {
    @Inject(method = {"keyPress", "charTyped"}, at = @At("HEAD"), cancellable = true)
    private void vultrum$lockInput(CallbackInfo ci) {
        if (Bench.locksInput()) ci.cancel();
    }
}
