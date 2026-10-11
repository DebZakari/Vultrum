package dev.vultrum.cull;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class HizLevelsTest {
    @Test
    void fullHd() {
        var pyramid = HizLevels.of(1920, 1080);
        // 240x135, 120x68, 60x34, 30x17, 15x9, 8x5, 4x3, 2x2, 1x1
        assertEquals(new HizLevels(240, 135, 9, 43302), pyramid);
        assertEquals(510, pyramid.levelTexels(3)); // one mode-0 workgroup per level-3 texel
        assertEquals(4, pyramid.tailLevel()); // two dispatches
    }

    @Test
    void sizesRoundUp() {
        var pyramid = HizLevels.of(1000, 9); // 125x2, 63x1, 32x1, 16x1, 8x1, 4x1, 2x1, 1x1
        assertEquals(new HizLevels(125, 2, 8, 376), pyramid);
        long sum = 0;
        for (int level = 0; level < pyramid.levels(); level++) sum += pyramid.levelTexels(level);
        assertEquals(pyramid.texels(), sum);
        assertEquals(1, pyramid.levelTexels(pyramid.levels() - 1));
    }

    @Test
    void smallAndLargeTargets() {
        var pixel = HizLevels.of(1, 1);
        assertEquals(new HizLevels(1, 1, 1, 1), pixel);
        assertEquals(1, pixel.tailLevel()); // mode 0 alone
        var tile = HizLevels.of(64, 64); // 8x8 to 1x1: all four levels in mode 0
        assertEquals(4, tile.levels());
        assertEquals(4, tile.tailLevel());
        assertEquals(4, HizLevels.of(3840, 2160).tailLevel()); // level 4 is 30x17
        assertEquals(5, HizLevels.of(7680, 4320).tailLevel()); // level 4 is 60x34, over TAIL_TEXELS
    }

    @Test
    void shadersMatch() throws IOException {
        String hiz = ShaderBlocks.source("occlusion_hiz.comp"), cull = ShaderBlocks.source("occlusion_cull.comp");
        String levelSize = "uvec2 levelSize(uint level) { return (size0 + (1u << level) - 1u) >> level; }";
        assertTrue(hiz.contains(levelSize));
        assertTrue(cull.contains(levelSize));
        assertTrue(hiz.contains("const int BLOCK = " + HizLevels.BLOCK + ";"));
        assertTrue(cull.contains("const int BLOCK = " + HizLevels.BLOCK + ";"));
        String levelOffset = """
                uint levelOffset(uint level) {
                    uint offset = 0u;
                    for (uint l = 0u; l < level; l++) { uvec2 s = levelSize(l); offset += s.x * s.y; }
                    return offset;
                }""";
        assertTrue(hiz.contains(levelOffset));
        assertTrue(cull.contains(levelOffset));
        assertTrue(hiz.contains("const uint TAIL_TEXELS = " + HizLevels.TAIL_TEXELS + "u;"));
        assertTrue(hiz.contains("const uint GROUP = " + HizLevels.GROUP + "u;"));
        assertTrue(hiz.contains("layout(local_size_x = GROUP) in;"));
    }

    @Test
    void tileAndTailFitTheWorkgroup() {
        // tile() reduces through levels 0-3 by hand, one thread per quarter block, in 256 + 64 + 16 + 4 cells.
        assertEquals(4, HizLevels.TILE_LEVELS);
        assertEquals(256, HizLevels.GROUP);
        assertTrue(HizLevels.TAIL_TEXELS >= HizLevels.GROUP + 84);
        // tail() gives each thread TAIL_TEXELS / GROUP texels, so a remainder would go unbuilt.
        assertEquals(0, HizLevels.TAIL_TEXELS % HizLevels.GROUP);
    }
}
