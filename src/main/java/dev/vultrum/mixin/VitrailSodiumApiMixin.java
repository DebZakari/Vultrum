package dev.vultrum.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.renderpearl.api.textures.GpuSampler;
import dev.vultrum.cull.GpuOcclusionCull;
import dev.vultrum.cull.ShadowPass;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** Vitrail's shadow-pass terrain entry point; absent without Vitrail. Optional: if it stops matching, nothing is culled. */
@Pseudo
@Mixin(targets = "dev.vitrail.sodium.SodiumApi")
abstract class VitrailSodiumApiMixin {
    @WrapMethod(method = "drawShadowLayer(Lnet/caffeinemc/mods/sodium/client/render/SodiumWorldRenderer;"
            + "Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lnet/caffeinemc/mods/sodium/client/render/chunk/ChunkRenderMatrices;"
            + "Lnet/minecraft/world/phys/Vec3;Lcom/mojang/renderpearl/api/textures/GpuSampler;)V", require = 0)
    private static void vultrum$shadowPass(SodiumWorldRenderer renderer, ChunkSectionLayerGroup group, ChunkRenderMatrices matrices,
            Vec3 camera, GpuSampler sampler, Operation<Void> original) {
        try {
            ShadowPass.begin(camera.x, camera.y, camera.z);
            GpuOcclusionCull.shadow(true);
            original.call(renderer, group, matrices, camera, sampler);
        } finally {
            ShadowPass.end();
            GpuOcclusionCull.shadow(false);
        }
    }
}
