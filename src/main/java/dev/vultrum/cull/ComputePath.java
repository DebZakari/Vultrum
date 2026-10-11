package dev.vultrum.cull;

/** Precompiled compute variants selected within the device's reported workgroup and subgroup limits. */
record ComputePath(boolean subgroupCounters, int hizGroup) {
    static final int MIN_GROUP = 128;

    static ComputePath choose(int maxInvocations, int maxSizeX, boolean arithmetic) {
        int limit = Math.min(maxInvocations, maxSizeX);
        if (limit < MIN_GROUP) return null;
        return new ComputePath(arithmetic, limit >= HizLevels.GROUP ? HizLevels.GROUP : MIN_GROUP);
    }

    String cullShader() { return subgroupCounters ? "occlusion_cull" : "occlusion_cull_portable"; }
    String hizShader() { return hizGroup == HizLevels.GROUP ? "occlusion_hiz" : "occlusion_hiz_128"; }
}
