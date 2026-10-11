package dev.vultrum.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.vultrum.bench.FrameTimer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(RenderSectionManager.class)
abstract class RenderSectionManagerMixin {
    /** The camera's call in setupTerrain, and Vitrail's for the shadow walk, which asks for an immediate update. */
    @WrapMethod(method = "finalizeRenderLists")
    private void vultrum$timeRenderLists(Camera camera, Viewport viewport, FogParameters fog, boolean immediately, Operation<Void> original) {
        long start = FrameTimer.beginSetupPhase();
        try { original.call(camera, viewport, fog, immediately); }
        finally { FrameTimer.endRenderLists(start); }
    }
}
