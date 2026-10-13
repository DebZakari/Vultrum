package dev.vultrum.mixin;

import dev.vultrum.bench.Timeline;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Bench only (see VultrumMixinPlugin): the chunks the client's storage holds, for {@link Timeline}. Its chunk count
 * changes exactly where these fire, a load that takes the slot of an old chunk included, so they replay into
 * getLoadedChunksCount. A render distance change makes a new storage, which starts empty and adds the chunks it keeps.
 */
@Mixin(targets = "net.minecraft.client.multiplayer.ClientChunkCache$Storage")
abstract class ClientChunkStorageTimelineMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void vultrum$storage(CallbackInfo ci) {
        Timeline.event("storage", 0, 0, 0, "");
    }

    @Inject(method = "onChunkAdded", at = @At("HEAD"))
    private void vultrum$added(LevelChunk chunk, CallbackInfo ci) {
        Timeline.event("add", chunk.getPos().x(), 0, chunk.getPos().z(), "");
    }

    @Inject(method = "onChunkRemoved", at = @At("HEAD"))
    private void vultrum$removed(LevelChunk chunk, CallbackInfo ci) {
        Timeline.event("remove", chunk.getPos().x(), 0, chunk.getPos().z(), "");
    }
}
