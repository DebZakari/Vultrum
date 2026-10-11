package dev.vultrum.cull;

/**
 * Layout of the cull's Hi-Z pyramid, shared with {@code occlusion_hiz.comp} and {@code occlusion_cull.comp}.
 * A level-0 texel covers {@link #BLOCK}² pixels, and each level halves the one below, rounding up, down to 1×1.
 * Levels sit back to back in one buffer of {@link #texels()} floats.
 *
 * <p>The build runs in two dispatches at common resolutions: mode 0 builds levels 0 to {@link #TILE_LEVELS} - 1,
 * one workgroup per {@link #TILE}² level-0 texels, and mode 2 builds the rest in one workgroup once a level fits in
 * {@link #TAIL_TEXELS}. Levels between the two (none up to 4K) get a mode-1 dispatch each. All three modes run
 * {@link #GROUP}-thread workgroups, or 128 threads when the device's limit requires it.
 *
 * @param width0 level-0 width in texels
 * @param height0 level-0 height in texels
 */
record HizLevels(int width0, int height0, int levels, long texels) {
    static final int BLOCK = 8;
    static final int TILE_LEVELS = 4;
    static final int TILE = 1 << (TILE_LEVELS - 1);
    static final int TAIL_TEXELS = 1024;
    /** Hi-Z workgroup size: mode 0 has one thread per 4×4-pixel quarter of the tile's level-0 texels. */
    static final int GROUP = 4 * TILE * TILE;

    /** The pyramid over a depth target of {@code width} × {@code height} pixels. */
    static HizLevels of(int width, int height) {
        int width0 = Math.ceilDiv(width, BLOCK), height0 = Math.ceilDiv(height, BLOCK);
        int levels = 1;
        long texels = (long) width0 * height0;
        for (int w = width0, h = height0; w > 1 || h > 1; levels++) {
            w = Math.ceilDiv(w, 2);
            h = Math.ceilDiv(h, 2);
            texels += (long) w * h;
        }
        return new HizLevels(width0, height0, levels, texels);
    }

    long levelTexels(int level) {
        return (long) Math.ceilDiv(width0, 1 << level) * Math.ceilDiv(height0, 1 << level);
    }

    /** The first level mode 2 builds; {@link #levels} if there is none. */
    int tailLevel() {
        int level = Math.min(TILE_LEVELS, levels);
        while (level < levels && levelTexels(level) > TAIL_TEXELS) level++;
        return level;
    }
}
