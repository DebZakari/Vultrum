package dev.vultrum.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.vultrum.bench.FrameTimer;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.trigger.CameraMovement;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Bench only (see VultrumMixinPlugin): applied to runs started with -Dvultrum.bench. */
@Mixin(SodiumWorldRenderer.class)
abstract class SodiumSetupPhasesMixin {
    // Phases of setupTerrain, so a slow frame can be traced to Sodium's own step. RenderSectionManagerMixin
    // times the render lists. What isn't wrapped (prepareFrame, prepareRender, tickVisibleRenders) is the
    // setup time left over. Timed apart, the last two never took over 0.4 ms each.
    @WrapOperation(method = "setupTerrain", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/SodiumWorldRenderer;processChunkEvents()V"))
    private void vultrum$timeChunkEvents(SodiumWorldRenderer renderer, Operation<Void> original) {
        long start = FrameTimer.beginSetupPhase();
        try { original.call(renderer); }
        finally { FrameTimer.endSetupPhase(FrameTimer.SetupPhase.CHUNK_EVENTS, start); }
    }

    @WrapOperation(method = "setupTerrain", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager;processGFNIMovement(Lnet/caffeinemc/mods/sodium/client/render/chunk/translucent_sorting/trigger/CameraMovement;)V"))
    private void vultrum$timeSortTrigger(RenderSectionManager manager, CameraMovement movement, Operation<Void> original) {
        long start = FrameTimer.beginSetupPhase();
        try { original.call(manager, movement); }
        finally { FrameTimer.endSetupPhase(FrameTimer.SetupPhase.SORT_TRIGGER, start); }
    }

    @WrapOperation(method = "setupTerrain", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager;prepareRenderTrees(Lnet/caffeinemc/mods/sodium/client/render/viewport/Viewport;Lnet/caffeinemc/mods/sodium/client/util/FogParameters;Z)V"))
    private void vultrum$timeCullTrees(RenderSectionManager manager, Viewport viewport, FogParameters fog, boolean spectator, Operation<Void> original) {
        long start = FrameTimer.beginSetupPhase();
        try { original.call(manager, viewport, fog, spectator); }
        finally { FrameTimer.endSetupPhase(FrameTimer.SetupPhase.CULL_TREES, start); }
    }

    @WrapOperation(method = "setupTerrain", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager;updateChunks(Lnet/caffeinemc/mods/sodium/client/render/viewport/Viewport;Z)V"))
    private void vultrum$timeSchedule(RenderSectionManager manager, Viewport viewport, boolean immediately, Operation<Void> original) {
        long start = FrameTimer.beginSetupPhase();
        try { original.call(manager, viewport, immediately); }
        finally { FrameTimer.endSetupPhase(FrameTimer.SetupPhase.SCHEDULE, start); }
    }

    @WrapOperation(method = "setupTerrain", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager;processChunkBuilds(Lnet/caffeinemc/mods/sodium/client/render/viewport/Viewport;Lnet/caffeinemc/mods/sodium/client/render/chunk/UniformBufferManager;)V"))
    private void vultrum$timeUpload(RenderSectionManager manager, Viewport viewport, UniformBufferManager uniforms, Operation<Void> original) {
        long start = FrameTimer.beginSetupPhase();
        try { original.call(manager, viewport, uniforms); }
        finally { FrameTimer.endSetupPhase(FrameTimer.SetupPhase.UPLOAD, start); }
    }

    /** Sodium's first step: it takes every buffer reference the GC queued since the last frame (SodiumNativeBufferMixin counts them). */
    @WrapOperation(method = "setupTerrain", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/util/NativeBuffer;reclaim(Z)V"))
    private void vultrum$timeReclaim(boolean forceGc, Operation<Void> original) {
        long start = FrameTimer.beginSetupPhase();
        try { original.call(forceGc); }
        finally { FrameTimer.endSetupPhase(FrameTimer.SetupPhase.RECLAIM, start); }
    }

    /** The cloned-section cache's cleanup, the staging buffer's flip, the arenas' upkeep and each region's update. */
    @WrapOperation(method = "setupTerrain", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager;cleanupAndFlip(Lnet/caffeinemc/mods/sodium/client/render/chunk/UniformBufferManager;)V"))
    private void vultrum$timeCleanup(RenderSectionManager manager, UniformBufferManager uniforms, Operation<Void> original) {
        long start = FrameTimer.beginSetupPhase();
        try { original.call(manager, uniforms); }
        finally { FrameTimer.endSetupPhase(FrameTimer.SetupPhase.CLEANUP, start); }
    }
}
