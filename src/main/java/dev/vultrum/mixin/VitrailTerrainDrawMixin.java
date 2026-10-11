package dev.vultrum.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.vultrum.cull.ShadowPass;
import dev.vultrum.cull.ShadowRegion;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Vitrail's shadow-cull planning; absent without Vitrail. Injections are optional: if one stops
 * matching, Vultrum's shadow-pass culling stays paused.
 */
@Pseudo
@Mixin(targets = "dev.vitrail.render.TerrainDraw")
abstract class VitrailTerrainDrawMixin {
    @Unique
    private static final String PLAN = "shadowCullPlan(Lorg/joml/Vector3f;Lorg/joml/Matrix4f;)Ldev/vitrail/render/ShadowCullPlan;";

    /** Whether the injection below has run; if it stops matching, the cull pauses instead of guessing. */
    @Unique
    private static boolean vultrum$voxeliseRead;
    @Unique
    private static boolean vultrum$voxelised;

    @ModifyExpressionValue(method = PLAN, at = @At(value = "INVOKE", target = "Ldev/vitrail/render/TerrainDraw;voxelisesShadow()Z"), require = 0)
    private static boolean vultrum$readVoxelised(boolean voxelised) {
        vultrum$voxeliseRead = true;
        vultrum$voxelised = voxelised;
        return voxelised;
    }

    /** The plan record's {@code bound()}, looked up once for its class, and a class that has none. */
    @Unique
    private static Class<?> vultrum$planClass, vultrum$unreadable;
    @Unique
    private static MethodHandle vultrum$bound;

    /** {@code light} is an out parameter: when the plan isn't null, Vitrail has filled it with the world-space direction toward the shadow light. */
    @Inject(method = PLAN, at = @At("RETURN"), require = 0)
    private static void vultrum$light(Vector3f light, Matrix4f camera, CallbackInfoReturnable<?> info) {
        Object plan = info.getReturnValue();
        ShadowPass.plan(plan == null ? null : light, vultrum$voxeliseRead ? vultrum$voxelised : null, vultrum$bound(plan));
    }

    /** The half side of the box around the camera Vitrail walks for the light, negative for none, NaN if unreadable. */
    @Unique
    private static float vultrum$bound(Object plan) {
        if (plan == null || plan.getClass() == vultrum$unreadable) return Float.NaN;
        try {
            if (plan.getClass() != vultrum$planClass) {
                vultrum$bound = MethodHandles.publicLookup().findVirtual(plan.getClass(), "bound", MethodType.methodType(float.class));
                vultrum$planClass = plan.getClass();
            }
            return (float) vultrum$bound.invoke(plan);
        } catch (Throwable unreadable) {
            vultrum$unreadable = plan.getClass();
            vultrum$planClass = null;
            return Float.NaN;
        }
    }

    /** The light's drawn matrix, which Vitrail reads just before planning: the depth window the map is drawn in. */
    @Inject(method = "shadowFrustum(Lorg/joml/Matrix4f;)Lorg/joml/Matrix4f;", at = @At("RETURN"), require = 0)
    private static void vultrum$shadowMatrix(Matrix4f dest, CallbackInfoReturnable<Matrix4f> info) {
        ShadowRegion.shadowMatrix(info.getReturnValue());
    }
}
