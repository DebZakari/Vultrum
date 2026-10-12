package dev.vultrum.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.vultrum.VultrumConfig;
import dev.vultrum.upload.RegistrationQueue;
import dev.vultrum.upload.UnloadBudget;
import it.unimi.dsi.fastutil.longs.LongCollection;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
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
    @Shadow private RenderSectionManager renderSectionManager;
    @Unique private RegistrationQueue vultrum$registration;

    /** Keep state lazy so early teardown and completion checks need no constructor-initialized mixin field. */
    @Unique
    private RegistrationQueue vultrum$queue() {
        if (vultrum$registration == null) vultrum$registration = new RegistrationQueue();
        return vultrum$registration;
    }

    @WrapOperation(method = "initRenderer", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/map/ChunkTracker;forEachChunk(Lit/unimi/dsi/fastutil/longs/LongCollection;Lnet/caffeinemc/mods/sodium/client/render/chunk/map/ChunkTracker$ChunkEventHandler;)V"))
    private void vultrum$initialColumns(LongCollection chunks, ChunkTracker.ChunkEventHandler add, Operation<Void> original) {
        if (!VultrumConfig.get().reloadRegistration()) {
            original.call(chunks, add);
            return;
        }
        var tracker = (SodiumChunkTrackerAccessor) ChunkTrackerHolder.get(level);
        vultrum$queue().seed(chunks, tracker.vultrum$loadQueue());
    }

    @Inject(method = "deleteRendererState", at = @At("HEAD"))
    private void vultrum$discardGeneration(CallbackInfo ci) {
        if (vultrum$registration != null) vultrum$registration.reset();
    }

    /** Sodium has already called beforeSectionUpdates; additions still precede render preparation and builds. */
    @Inject(method = "processChunkEvents", at = @At("TAIL"))
    private void vultrum$registerBatch(CallbackInfo ci) {
        if (vultrum$registration == null || vultrum$registration.pending() == 0) return;
        vultrum$registration.drain(ChunkTrackerHolder.get(level).getReadyChunks(),
                pos -> renderSectionManager.onChunkAdded(ChunkPos.getX(pos), ChunkPos.getZ(pos)));
    }

    @ModifyReturnValue(method = "isTerrainRenderComplete", at = @At("RETURN"))
    private boolean vultrum$registrationComplete(boolean original) {
        return original && (vultrum$registration == null || vultrum$registration.pending() == 0);
    }

    @WrapOperation(method = "processChunkEvents", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/map/ChunkTracker;forEachEvent(Lnet/caffeinemc/mods/sodium/client/render/chunk/map/ChunkTracker$ChunkEventHandler;Lnet/caffeinemc/mods/sodium/client/render/chunk/map/ChunkTracker$ChunkEventHandler;)V"))
    private void vultrum$budgetUnloads(ChunkTracker tracker, ChunkTracker.ChunkEventHandler load, ChunkTracker.ChunkEventHandler unload,
                                      Operation<Void> original) {
        var queues = (SodiumChunkTrackerAccessor) tracker;
        if (vultrum$registration != null && vultrum$registration.pending() != 0)
            vultrum$registration.events(queues.vultrum$loadQueue(), queues.vultrum$unloadQueue());
        UnloadBudget.drainClient(queues.vultrum$loadQueue(), queues.vultrum$unloadQueue(),
                pos -> load.apply(ChunkPos.getX(pos), ChunkPos.getZ(pos)), pos -> unload.apply(ChunkPos.getX(pos), ChunkPos.getZ(pos)),
                () -> original.call(tracker, load, unload));
    }

    /** After a reload, removals for the old manager apply to neither the new manager nor its pending snapshot. */
    @Inject(method = "initRenderer", at = @At("TAIL"))
    private void vultrum$dropStaleUnloads(CallbackInfo ci) {
        UnloadBudget.reloaded(((SodiumChunkTrackerAccessor) ChunkTrackerHolder.get(level)).vultrum$unloadQueue());
    }
}
