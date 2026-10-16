package dev.vultrum.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.vultrum.upload.ColumnLifecycle;
import it.unimi.dsi.fastutil.longs.LongCollection;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTracker;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTrackerHolder;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Spreads initial reload registration and queued removals, preserving Sodium's ordinary streaming additions. */
@Mixin(SodiumWorldRenderer.class)
abstract class SodiumChunkEventsMixin {
    @Shadow private ClientLevel level;
    @Unique private ColumnLifecycle vultrum$columns;

    /** Keep state lazy so early teardown and completion checks need no constructor-initialized mixin field. */
    @Unique
    private ColumnLifecycle vultrum$lifecycle() {
        if (vultrum$columns == null) vultrum$columns = new ColumnLifecycle();
        return vultrum$columns;
    }

    @WrapOperation(method = "initRenderer", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/map/ChunkTracker;forEachChunk(Lit/unimi/dsi/fastutil/longs/LongCollection;Lnet/caffeinemc/mods/sodium/client/render/chunk/map/ChunkTracker$ChunkEventHandler;)V"))
    private void vultrum$initialColumns(LongCollection chunks, ChunkTracker.ChunkEventHandler add, Operation<Void> original) {
        var tracker = (SodiumChunkTrackerAccessor) ChunkTrackerHolder.get(level);
        vultrum$lifecycle().initialize(chunks, tracker.vultrum$loadQueue(), tracker.vultrum$unloadQueue(),
                () -> original.call(chunks, add));
    }

    @Inject(method = "deleteRendererState", at = @At("HEAD"))
    private void vultrum$discardGeneration(CallbackInfo ci) {
        if (vultrum$columns != null) vultrum$columns.reset();
    }

    @ModifyReturnValue(method = "isTerrainRenderComplete", at = @At("RETURN"))
    private boolean vultrum$registrationComplete(boolean original) {
        return vultrum$columns == null ? original : vultrum$columns.complete(original);
    }

    @WrapOperation(method = "processChunkEvents", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/map/ChunkTracker;forEachEvent(Lnet/caffeinemc/mods/sodium/client/render/chunk/map/ChunkTracker$ChunkEventHandler;Lnet/caffeinemc/mods/sodium/client/render/chunk/map/ChunkTracker$ChunkEventHandler;)V"))
    private void vultrum$budgetUnloads(ChunkTracker tracker, ChunkTracker.ChunkEventHandler load, ChunkTracker.ChunkEventHandler unload,
                                      Operation<Void> original) {
        var queues = (SodiumChunkTrackerAccessor) tracker;
        vultrum$lifecycle().process(tracker.getReadyChunks(), queues.vultrum$loadQueue(), queues.vultrum$unloadQueue(),
                pos -> load.apply(ChunkPos.getX(pos), ChunkPos.getZ(pos)), pos -> unload.apply(ChunkPos.getX(pos), ChunkPos.getZ(pos)),
                () -> original.call(tracker, load, unload));
    }
}
