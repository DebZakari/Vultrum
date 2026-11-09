package dev.vultrum.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.vultrum.compat.VitrailShadowDraw;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** Marks Vitrail's entity shadow draw, where 26.3 runs the deferred entity setup for the shadow map. */
@Pseudo
@Mixin(targets = "dev.vitrail.render.ShadowGeometry")
abstract class VitrailShadowGeometryMixin {
    @WrapMethod(method = "draw(Lnet/minecraft/world/phys/Vec3;)V", require = 0)
    private static void vultrum$shadowDraw(Vec3 camera, Operation<Void> original) {
        VitrailShadowDraw.set(true);
        try {
            original.call(camera);
        } finally {
            VitrailShadowDraw.set(false);
        }
    }
}
