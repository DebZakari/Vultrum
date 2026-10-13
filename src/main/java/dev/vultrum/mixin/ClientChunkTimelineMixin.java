package dev.vultrum.mixin;

import dev.vultrum.bench.Timeline;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Bench only (see VultrumMixinPlugin): the chunks the client loads and drops and its light changes, for {@link Timeline}. */
@Mixin(ClientChunkCache.class)
abstract class ClientChunkTimelineMixin {
    // At return: the client ignores a chunk outside its storage range, and returns null for it.
    @Inject(method = "replaceWithPacketData", at = @At("RETURN"))
    private void vultrum$load(int x, int z, ClientboundLevelChunkPacketData data, CallbackInfoReturnable<?> cir) {
        Timeline.event("load", x, 0, z, cir.getReturnValue() != null ? "" : "ignored");
    }

    // An attempt: dropping a chunk the client doesn't hold changes nothing. Loads and drops don't replay into the
    // client's chunk count exactly: read getLoadedChunksCount for that.
    @Inject(method = "drop", at = @At("HEAD"))
    private void vultrum$drop(ChunkPos pos, CallbackInfo ci) {
        Timeline.event("drop", pos.x(), 0, pos.z(), "");
    }

    @Inject(method = "updateViewCenter", at = @At("HEAD"))
    private void vultrum$center(int x, int z, CallbackInfo ci) {
        Timeline.event("center", x, 0, z, "");
    }

    @Inject(method = "onLightUpdate", at = @At("HEAD"))
    private void vultrum$light(LightLayer layer, SectionPos pos, CallbackInfo ci) {
        Timeline.event("light", pos.x(), pos.y(), pos.z(), layer.name());
    }
}
