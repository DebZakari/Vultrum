package dev.vultrum.mixin;

import dev.vultrum.bench.Timeline;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Bench only (see VultrumMixinPlugin): each section marked dirty and who marked it, for {@link Timeline}. */
@Mixin(SodiumWorldRenderer.class)
abstract class SodiumDirtyTimelineMixin {
    @Inject(method = "scheduleRebuildForChunk", at = @At("HEAD"))
    private void vultrum$dirty(int x, int y, int z, boolean important, CallbackInfo ci) {
        Timeline.dirty(x, y, z, important);
    }
}
