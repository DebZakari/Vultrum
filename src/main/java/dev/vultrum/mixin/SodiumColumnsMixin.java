package dev.vultrum.mixin;

import dev.vultrum.cull.ShadowRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The columns Sodium's render graph takes in and lets go, for {@link ShadowRegion}: a column's sections are built after it's added. */
@Mixin(RenderSectionManager.class)
abstract class SodiumColumnsMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void vultrum$created(CallbackInfo ci) {
        ShadowRegion.managerCreated();
    }

    @Inject(method = "onChunkAdded", at = @At("HEAD"))
    private void vultrum$columnAdded(int x, int z, CallbackInfo ci) {
        ShadowRegion.columnAdded(x, z);
    }

    @Inject(method = "onChunkRemoved", at = @At("HEAD"))
    private void vultrum$columnRemoved(int x, int z, CallbackInfo ci) {
        ShadowRegion.columnRemoved(x, z);
    }
}
