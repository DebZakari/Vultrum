package dev.vultrum.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.vultrum.cull.CullBatch;
import dev.vultrum.cull.GpuOcclusionCull;
import dev.vultrum.cull.ShadowFaceCull;
import dev.vultrum.cull.SunlessShadow;
import net.caffeinemc.mods.sodium.client.gpu.device.batch.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.gpu.device.context.DrawContext;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(DefaultChunkRenderer.class)
abstract class DefaultChunkRendererMixin {
    /** With face culling off (Vitrail's shadow pass), each section draws ModelQuadFacing.ALL; narrow solid geometry to sun-facing faces. */
    @ModifyExpressionValue(method = "fillCommandBuffer", at = @At(value = "FIELD",
            target = "Lnet/caffeinemc/mods/sodium/client/model/quad/properties/ModelQuadFacing;ALL:I"))
    private static int vultrum$shadowFaces(int all, @Local(argsOnly = true) TerrainRenderPass pass, @Local(name = "chunkX") int x,
            @Local(name = "chunkY") int y, @Local(name = "chunkZ") int z) {
        return ShadowFaceCull.filter(all, pass, x, y, z);
    }

    // Every command must get its section, so both calls must match.
    @WrapOperation(method = "fillCommandBuffer", require = 2, at = {
            @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/"
                    + "DefaultChunkRenderer;addLocalIndexedDrawCommands(Lnet/caffeinemc/mods/sodium/client/gpu/device/batch/MultiDrawBatch;JI)V"),
            @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/"
                    + "DefaultChunkRenderer;addSharedIndexedDrawCommands(Lnet/caffeinemc/mods/sodium/client/gpu/device/batch/MultiDrawBatch;JI)V")})
    private static void vultrum$section(MultiDrawBatch batch, long mesh, int mask, Operation<Void> original,
            @Local(argsOnly = true) RenderRegion region, @Local(name = "chunkX") int x, @Local(name = "chunkY") int y,
            @Local(name = "chunkZ") int z, @Local(name = "sectionIndex") int index) {
        int from = batch.size;
        if (!SunlessShadow.skip(x, y, z)) original.call(batch, mesh, mask);
        if (GpuOcclusionCull.CULL) ((CullBatch) batch).vultrum$section(from, batch.size, x, y, z, region.getId(), index);
    }

    @WrapMethod(method = "prepare")
    private void vultrum$prepare(ChunkRenderListIterable lists, CameraTransform camera, boolean cull, Operation<Void> original) {
        GpuOcclusionCull.prepare(() -> original.call(lists, camera, cull));
    }

    @WrapOperation(method = "prepare", at = @At(value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/client/gpu/device/batch/MultiDrawBatch;prepare(Lnet/caffeinemc/mods/sodium/client/gpu/device/context/DrawContext;)V"))
    private void vultrum$prepared(MultiDrawBatch batch, DrawContext context, Operation<Void> original,
            @Local TerrainRenderPass pass, @Local RenderRegion region) {
        original.call(batch, context);
        GpuOcclusionCull.prepared(batch, pass, region, context);
    }

}
