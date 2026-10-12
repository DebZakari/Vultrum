package dev.vultrum.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.vultrum.upload.UnloadBudget;
import dev.vultrum.upload.RegistrationQueue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import it.unimi.dsi.fastutil.longs.LongCollection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import org.spongepowered.asm.mixin.Unique;
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
    @Shadow private RenderSectionManager renderSectionManager;
    @Unique private final RegistrationQueue vultrum$registration = new RegistrationQueue();
    @Unique private static final boolean vultrum$registrationEnabled = Boolean.getBoolean("vultrum.registrationBudget");

    @WrapOperation(method = "initRenderer", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/map/ChunkTracker;forEachChunk(Lit/unimi/dsi/fastutil/longs/LongCollection;Lnet/caffeinemc/mods/sodium/client/render/chunk/map/ChunkTracker$ChunkEventHandler;)V"))
    private void vultrum$initialColumns(LongCollection chunks, ChunkTracker.ChunkEventHandler add, Operation<Void> original) {
        if (!vultrum$registrationEnabled) { original.call(chunks, add); return; }
        var tracker = (SodiumChunkTrackerAccessor) ChunkTrackerHolder.get(level);
        vultrum$registration.seed(chunks, tracker.vultrum$loadQueue());
    }

    @Inject(method = "deleteRendererState", at = @At("HEAD"))
    private void vultrum$discardGeneration(CallbackInfo ci) { vultrum$registration.reset(); }

    @Inject(method = "processChunkEvents", at = @At("TAIL"))
    private void vultrum$registerBatch(CallbackInfo ci) {
        if (!vultrum$registrationEnabled || vultrum$registration.pending() == 0) return;
        long start = System.nanoTime();
        int count = vultrum$registration.drain(ChunkTrackerHolder.get(level).getReadyChunks(),
                pos -> renderSectionManager.onChunkAdded(ChunkPos.getX(pos), ChunkPos.getZ(pos)));
        dev.vultrum.bench.ReloadProbe.stage("registrationBatch-" + count, start);
    }

    @ModifyReturnValue(method = "isTerrainRenderComplete", at = @At("RETURN"))
    private boolean vultrum$registrationComplete(boolean original) { return original && vultrum$registration.pending() == 0; }

    @WrapOperation(method = "processChunkEvents", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/map/ChunkTracker;forEachEvent(Lnet/caffeinemc/mods/sodium/client/render/chunk/map/ChunkTracker$ChunkEventHandler;Lnet/caffeinemc/mods/sodium/client/render/chunk/map/ChunkTracker$ChunkEventHandler;)V"))
    private void vultrum$budgetUnloads(ChunkTracker tracker, ChunkTracker.ChunkEventHandler load, ChunkTracker.ChunkEventHandler unload,
                                           Operation<Void> original) {
        var queues = (SodiumChunkTrackerAccessor) tracker;
        if (vultrum$registrationEnabled) vultrum$registration.events(queues.vultrum$loadQueue(), queues.vultrum$unloadQueue());
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
