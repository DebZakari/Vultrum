package dev.vultrum.mixin;

import dev.vultrum.bench.Builds;
import dev.vultrum.bench.Timeline;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.estimation.UploadResourceBudget;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkJobCollector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Bench only (see VultrumMixinPlugin): the section builds Sodium submits, for {@link Builds} and {@link Timeline}. */
@Mixin(RenderSectionManager.class)
abstract class SodiumBuildSubmitMixin {
    // Every build and sort goes through this overload, from the important sets and from the walk's deferred list.
    @Inject(method = "submitSectionTask(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/executor/ChunkJobCollector;Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;ILnet/caffeinemc/mods/sodium/client/render/chunk/compile/estimation/UploadResourceBudget;Z)V",
            at = @At("HEAD"))
    private void vultrum$submitted(ChunkJobCollector collector, RenderSection section, int type, UploadResourceBudget budget,
            boolean blocking, CallbackInfo ci) {
        Builds.submitted();
        Timeline.submitted(section.getChunkX(), section.getChunkY(), section.getChunkZ(), type);
    }

    @Inject(method = "submitDeferredSectionTasks", at = @At("HEAD"))
    private void vultrum$deferredStart(CallbackInfo ci) {
        Timeline.deferred(true);
    }

    @Inject(method = "submitDeferredSectionTasks", at = @At("RETURN"))
    private void vultrum$deferredEnd(CallbackInfo ci) {
        Timeline.deferred(false);
    }
}
