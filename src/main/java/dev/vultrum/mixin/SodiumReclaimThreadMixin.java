package dev.vultrum.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import dev.vultrum.upload.ReclaimThread;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * While Vultrum's reclaim thread is alive (ReclaimThread), the terrain setup skips its call to Sodium's
 * {@code reclaim(false)}. Other callers of {@code reclaim} run as before, such as {@code reclaim(true)}, Sodium's
 * emergency after a failed allocation.
 */
@Mixin(SodiumWorldRenderer.class)
abstract class SodiumReclaimThreadMixin {
    @WrapWithCondition(method = "setupTerrain", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/util/NativeBuffer;reclaim(Z)V"))
    private boolean vultrum$leaveToThread(boolean forceGc) {
        return forceGc || !ReclaimThread.skip();
    }
}
