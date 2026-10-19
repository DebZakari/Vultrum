package dev.vultrum.mixin;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.PlayerSkinRenderCache;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps player glyphs from retaining a skin view closed by a resource reload. */
@Mixin(PlayerSkinRenderCache.RenderInfo.class)
public abstract class PlayerSkinRenderInfoMixin {
    @Shadow private @Nullable GpuTextureView textureView;

    @Inject(method = "textureView", at = @At("HEAD"))
    private void vultrum$refreshClosedSkinView(CallbackInfoReturnable<GpuTextureView> cir) {
        // Let the original lookup obtain the current view from the reloaded texture.
        if (textureView != null && textureView.isClosed()) textureView = null;
    }
}
