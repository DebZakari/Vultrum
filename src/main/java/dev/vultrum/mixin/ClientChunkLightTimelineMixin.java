package dev.vultrum.mixin;

import dev.vultrum.bench.Timeline;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Bench only, the timeline's full mode (see VultrumMixinPlugin): the light changes the client applies, for {@link Timeline}. */
@Mixin(ClientChunkCache.class)
abstract class ClientChunkLightTimelineMixin {
    @Inject(method = "onLightUpdate", at = @At("HEAD"))
    private void vultrum$light(LightLayer layer, SectionPos pos, CallbackInfo ci) {
        Timeline.event("light", pos.x(), pos.y(), pos.z(), layer.name());
    }
}
