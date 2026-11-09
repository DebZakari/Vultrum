package dev.vultrum.mixin;

import dev.vultrum.compat.DisplayShadowReach;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A display with no culling box skips the frustum, so the shadow walk's reach has to be asked here. */
@Pseudo
@Mixin(targets = "dev.vitrail.render.ShadowGeometry")
abstract class VitrailDisplayShadowReachMixin {
    @Inject(method = "visible(Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;Lnet/minecraft/world/phys/Vec3;F)Z",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void vultrum$displayReach(Minecraft minecraft, EntityRenderDispatcher entities, Entity entity, Frustum frustum,
                                             Vec3 at, float partial, CallbackInfoReturnable<Boolean> callback) {
        if (entity instanceof Display display && DisplayShadowReach.hidden(display, at, partial)) callback.setReturnValue(false);
    }
}
