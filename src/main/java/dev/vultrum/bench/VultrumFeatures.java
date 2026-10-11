package dev.vultrum.bench;

import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;

import java.util.Set;

/** Optional Vulkan device features Vultrum asks renderpearl for (see {@code VulkanFeatureSetsMixin}). */
public final class VultrumFeatures {
    /** Pipeline-statistics queries, for benchmark runs with -Dvultrum.bench.stats=true only. */
    public static final FeatureSet PIPELINE_STATISTICS = new FeatureSet("Vultrum pipeline statistics", Set.of(),
            Set.of(new VulkanFeature(VulkanFeatureSets.VK10_FEATURES_STRUCT, "pipelineStatisticsQuery")));

    private VultrumFeatures() { }

    public static boolean statsRequested() { return Boolean.getBoolean("vultrum.bench.stats"); }
}
