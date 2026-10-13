package dev.vultrum.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
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
    // Every build and sort goes through this overload, from the important sets and from the walk's deferred list. Only
    // a job handed to the build threads counts: a disposed section, an empty one or a sort with nothing to do has none.
    @Inject(method = "submitSectionTask(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/executor/ChunkJobCollector;Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;ILnet/caffeinemc/mods/sodium/client/render/chunk/compile/estimation/UploadResourceBudget;Z)V",
            at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/executor/ChunkJobCollector;addSubmittedJob(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/executor/ChunkJob;)V"))
    private void vultrum$submitted(ChunkJobCollector collector, RenderSection section, int type, UploadResourceBudget budget,
            boolean blocking, CallbackInfo ci) {
        Builds.submitted();
        Timeline.submitted(section.getChunkX(), section.getChunkY(), section.getChunkZ(), type);
    }

    // Cleared in finally: the flag must not outlive the call if it throws.
    @WrapMethod(method = "submitDeferredSectionTasks")
    private void vultrum$deferred(ChunkJobCollector collector, UploadResourceBudget budget, Operation<Void> original) {
        Timeline.deferred(true);
        try {
            original.call(collector, budget);
        } finally {
            Timeline.deferred(false);
        }
    }
}
