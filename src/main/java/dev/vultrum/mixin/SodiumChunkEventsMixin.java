package dev.vultrum.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.vultrum.upload.UnloadBudget;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTracker;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTrackerHolder;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hands the columns Sodium has queued to add and remove to {@link UnloadBudget}, which may leave removals for later frames. */
@Mixin(SodiumWorldRenderer.class)
abstract class SodiumChunkEventsMixin {
    @Shadow private ClientLevel level;

    @WrapOperation(method = "processChunkEvents", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/map/ChunkTracker;forEachEvent(Lnet/caffeinemc/mods/sodium/client/render/chunk/map/ChunkTracker$ChunkEventHandler;Lnet/caffeinemc/mods/sodium/client/render/chunk/map/ChunkTracker$ChunkEventHandler;)V"))
    private void vultrum$budgetUnloads(ChunkTracker tracker, ChunkTracker.ChunkEventHandler load, ChunkTracker.ChunkEventHandler unload,
                                           Operation<Void> original) {
        var queues = (SodiumChunkTrackerAccessor) tracker;
        UnloadBudget.drainClient(queues.vultrum$loadQueue(), queues.vultrum$unloadQueue(),
                pos -> load.apply(ChunkPos.getX(pos), ChunkPos.getZ(pos)), pos -> unload.apply(ChunkPos.getX(pos), ChunkPos.getZ(pos)),
                () -> original.call(tracker, load, unload));
    }

    /** A reload (a new level, a render distance change, F3+A) has just filled a new section manager from the ready columns. */
    @Inject(method = "initRenderer", at = @At("TAIL"))
    private void vultrum$dropStaleUnloads(CallbackInfo ci) {
        UnloadBudget.reloaded(((SodiumChunkTrackerAccessor) ChunkTrackerHolder.get(level)).vultrum$unloadQueue());
    }
}
