package dev.vultrum.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import dev.vultrum.bench.VultrumFeatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.HashSet;
import java.util.Set;

/** Adds Vultrum's optional device features. Renderpearl enables an optional set only when the device supports it. */
@Mixin(VulkanFeatureSets.class)
abstract class VulkanFeatureSetsMixin {
    @ModifyReturnValue(method = "optionalFeatureSets", at = @At("RETURN"))
    private static Set<FeatureSet> vultrum$addFeatures(Set<FeatureSet> sets) {
        if (!VultrumFeatures.statsRequested()) return sets;
        var all = new HashSet<>(sets);
        all.add(VultrumFeatures.PIPELINE_STATISTICS);
        return all;
    }
}
