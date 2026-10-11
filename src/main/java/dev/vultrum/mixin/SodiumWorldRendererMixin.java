package dev.vultrum.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import dev.vultrum.bench.FrameTimer;
import dev.vultrum.cull.GpuOcclusionCull;
import dev.vultrum.cull.ShadowRegion;
import dev.vultrum.cull.SunlessShadow;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.oit.OitStage;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SodiumWorldRenderer.class)
abstract class SodiumWorldRendererMixin {
    @WrapMethod(method = "setupTerrain")
    private void vultrum$timeSetup(Camera camera, Viewport viewport, FogParameters fog, boolean spectator,
            boolean updateChunksImmediately, Matrix4f projection, Operation<Void> original) {
        long start = FrameTimer.beginTerrainSetup();
        try { original.call(camera, viewport, fog, spectator, updateChunksImmediately, projection); }
        finally { FrameTimer.endTerrainSetup(start); }
    }

    @WrapMethod(method = "drawChunkLayer")
    private void vultrum$timeDraw(RenderPass pass, ChunkSectionLayerGroup group, ChunkRenderMatrices matrices,
            double x, double y, double z, GpuSampler sampler, OitStage oit, Operation<Void> original) {
        long start = FrameTimer.beginTerrainDraw(pass, group);
        try { GpuOcclusionCull.draw(pass, group, matrices, x, y, z, () -> original.call(pass, group, matrices, x, y, z, sampler, oit)); }
        finally { FrameTimer.endTerrainDraw(start); }
    }

    /** Every section-dirty event ends here, light updates included (LevelExtractor's setSectionDirty family, overridden by Sodium). */
    @Inject(method = "scheduleRebuildForChunk", at = @At("HEAD"))
    private void vultrum$sectionChanged(int x, int y, int z, boolean important, CallbackInfo ci) {
        SunlessShadow.sectionChanged(x, y, z);
        ShadowRegion.sectionChanged(x, y, z);
    }
}
