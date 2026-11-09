package dev.vultrum.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.vultrum.compat.VitrailShadowDraw;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * EMF's shadow frame skip asks only Iris: answer yes during Vitrail's entity shadow draw. Its config check and first-person
 * exclusion stay its own. Each injector is optional and alone leaves EMF's decision unchanged, so a moved target only loses the skip.
 */
@Pseudo
@Mixin(targets = "traben.entity_model_features.utils.EMFLODHandler")
abstract class EmfShadowSkipMixin {
    @ModifyExpressionValue(method = "isLODSkippingThisFrame",
            at = @At(value = "FIELD", target = "Ltraben/entity_texture_features/ETF;IRIS_DETECTED:Z"), require = 0)
    private static boolean vultrum$detected(boolean iris) {
        return iris || VitrailShadowDraw.active();
    }

    @ModifyExpressionValue(method = "isLODSkippingThisFrame",
            at = @At(value = "INVOKE", target = "Ltraben/entity_model_features/mod_compat/IrisShadowPassDetection;inShadowPass()Z"), require = 0)
    private static boolean vultrum$inShadow(boolean iris) {
        return iris || VitrailShadowDraw.active();
    }
}
