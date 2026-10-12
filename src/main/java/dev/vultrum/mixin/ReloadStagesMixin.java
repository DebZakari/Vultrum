package dev.vultrum.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.vultrum.bench.ReloadProbe;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.SortBehavior;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTracker;
import it.unimi.dsi.fastutil.longs.LongCollection;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(SodiumWorldRenderer.class)
abstract class ReloadStagesMixin {
    @WrapMethod(method = "initRenderer")
    private void vultrum$init(Operation<Void> original) {
        long start = System.nanoTime();
        try { original.call(); }
        finally { ReloadProbe.stage("initRenderer", start); }
    }
    @WrapMethod(method = "deleteRendererState")
    private void vultrum$delete(Operation<Void> original) {
        long start = System.nanoTime();
        try { original.call(); }
        finally { ReloadProbe.stage("deleteRendererState", start); }
    }
    @WrapOperation(method = "initRenderer", at = @At(value = "NEW", target = "net/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager"))
    private RenderSectionManager vultrum$sections(ClientLevel level, int distance, SortBehavior sorting, Operation<RenderSectionManager> original) {
        long start = System.nanoTime();
        try { return original.call(level, distance, sorting); }
        finally { ReloadProbe.stage("newSectionManager", start); }
    }
    @WrapOperation(method = "initRenderer", at = @At(value = "NEW", target = "net/caffeinemc/mods/sodium/client/render/chunk/UniformBufferManager"))
    private UniformBufferManager vultrum$uniforms(ClientLevel level, int distance, Operation<UniformBufferManager> original) {
        long start = System.nanoTime();
        try { return original.call(level, distance); }
        finally { ReloadProbe.stage("newUniformManager", start); }
    }
    @WrapOperation(method = "initRenderer", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/map/ChunkTracker;forEachChunk(Lit/unimi/dsi/fastutil/longs/LongCollection;Lnet/caffeinemc/mods/sodium/client/render/chunk/map/ChunkTracker$ChunkEventHandler;)V"))
    private void vultrum$chunks(LongCollection chunks, ChunkTracker.ChunkEventHandler handler, Operation<Void> original) {
        long start = System.nanoTime();
        try { original.call(chunks, handler); }
        finally { ReloadProbe.stage("registerLoadedChunks-" + chunks.size(), start); }
    }
}
