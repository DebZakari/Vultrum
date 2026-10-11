package dev.vultrum.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.vultrum.cull.GpuOcclusionCull;
import net.caffeinemc.mods.sodium.client.gpu.device.backend.DrawBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The GPU cull edits indirect draw arguments, so Sodium must not pick direct multi-draw. Only where the device can draw
 * indirect, as Sodium's own choice requires: otherwise its pick stands and the cull finds no indirect draws to edit.
 */
@Mixin(DrawBackend.class)
abstract class DrawBackendMixin {
    @ModifyReturnValue(method = "chooseBackend", at = @At("RETURN"))
    private static DrawBackend vultrum$forceIndirect(DrawBackend chosen) {
        return GpuOcclusionCull.CULL && chosen == DrawBackend.VK_MULTIDRAW
                && RenderSystem.getDevice().getDeviceInfo().features().multiDrawIndirect() ? DrawBackend.VK_INDIRECT : chosen;
    }
}
