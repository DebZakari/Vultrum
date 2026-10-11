package dev.vultrum.cull;

import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing.*;
import static org.junit.jupiter.api.Assertions.*;

class ShadowFaceCullTest {
    private static final TerrainRenderPass SOLID = new TerrainRenderPass(null, false, false);
    private static final TerrainRenderPass CUTOUT = new TerrainRenderPass(null, false, true);
    private static final TerrainRenderPass TRANSLUCENT = new TerrainRenderPass(null, true, false);
    private static final Vector3f SUN = new Vector3f(0.3f, 0.9f, 0.2f);
    private static final int SUN_FACINGS = bits(POS_X, POS_Y, POS_Z, UNASSIGNED);

    private static int bits(ModelQuadFacing... facings) {
        int mask = 0;
        for (var facing : facings) mask |= 1 << facing.ordinal();
        return mask;
    }

    /** What Sodium's solid pass draws of a section inside {@code region} during a shadow draw after this plan. */
    private static int drawnAfter(Vector3f light, Boolean voxelised, TerrainRenderPass pass, ShadowRegion.Bounds region) {
        ShadowPass.plan(light, voxelised, 192);
        ShadowRegion.use(region);
        ShadowPass.begin(0, 0, 0);
        try { return ShadowFaceCull.filter(ModelQuadFacing.ALL, pass, 0, 0, 0); }
        finally { ShadowPass.end(); }
    }

    private static int drawnAfter(Vector3f light, Boolean voxelised, TerrainRenderPass pass) {
        return drawnAfter(light, voxelised, pass, ShadowRegionTest.everywhere());
    }

    @AfterEach
    void reset() {
        ShadowPass.configure(false, false, false);
    }

    @Test
    void cullsOnlySolidShadowGeometry() {
        ShadowPass.configure(true, false, true);
        assertEquals(SUN_FACINGS, drawnAfter(SUN, false, SOLID));
        assertEquals(ModelQuadFacing.ALL, drawnAfter(SUN, false, CUTOUT), "leaf holes show far faces");
        assertEquals(ModelQuadFacing.ALL, drawnAfter(SUN, false, TRANSLUCENT));
        assertEquals(ModelQuadFacing.ALL, ShadowFaceCull.filter(ModelQuadFacing.ALL, SOLID, 0, 0, 0), "outside a shadow draw");
    }

    @Test
    void cullsOnlyInsideTheShadowRegion() {
        ShadowPass.configure(true, false, true);
        assertEquals(ModelQuadFacing.ALL, drawnAfter(SUN, false, SOLID, ShadowRegion.Bounds.NONE));
        assertEquals(SUN_FACINGS, drawnAfter(SUN, false, SOLID));
    }

    @Test
    void cullsNothingWhenVitrailsRegionIsUnread() {
        ShadowPass.configure(true, false, true);
        // No region set by the test: the plan's own. An unread bound, an unread matrix, a perspective matrix.
        assertEquals(ModelQuadFacing.ALL, measuredAfter(Float.NaN, new Matrix4f().ortho(-160, 160, -160, 160, -100, 300)));
        assertEquals(ModelQuadFacing.ALL, measuredAfter(192, null));
        assertEquals(ModelQuadFacing.ALL, measuredAfter(192, new Matrix4f().perspective(1.2f, 1, 0.05f, 300)));
    }

    /** What the solid pass draws when the region is worked out from Vitrail's plan and matrix. */
    private static int measuredAfter(float bound, Matrix4f matrix) {
        ShadowRegion.shadowMatrix(matrix);
        ShadowPass.plan(SUN, false, bound);
        ShadowPass.begin(0, 0, 0);
        try { return ShadowFaceCull.filter(ModelQuadFacing.ALL, SOLID, 0, 0, 0); }
        finally { ShadowPass.end(); }
    }

    @Test
    void pausesWithoutALightOrForVoxelisingPacks() {
        ShadowPass.configure(true, false, true);
        assertEquals(ModelQuadFacing.ALL, drawnAfter(null, false, SOLID), "no pack draws terrain");
        assertEquals(ModelQuadFacing.ALL, drawnAfter(SUN, true, SOLID), "voxelising pack");
        assertEquals(ModelQuadFacing.ALL, drawnAfter(SUN, null, SOLID), "voxelise flag unreadable");
        assertEquals(SUN_FACINGS, drawnAfter(SUN, false, SOLID), "resumes for a surface-only pack");
    }

    @Test
    void offWhenConfiguredOffOrUnhooked() {
        ShadowPass.configure(false, false, true);
        assertEquals(ModelQuadFacing.ALL, drawnAfter(SUN, false, SOLID));
        ShadowPass.configure(true, false, false);
        assertEquals(ModelQuadFacing.ALL, drawnAfter(SUN, false, SOLID));
    }

    @Test
    void drawsTheFacingsThatPointAtTheLight() {
        assertEquals(bits(POS_X, POS_Y, POS_Z, UNASSIGNED), ShadowFaceCull.towardLight(0.3f, 0.9f, 0.2f));
        assertEquals(bits(NEG_X, POS_Y, NEG_Z, UNASSIGNED), ShadowFaceCull.towardLight(-0.5f, 0.7f, -0.1f));
        // The moon below the horizon on the other side: the underside faces it.
        assertEquals(bits(POS_X, NEG_Y, POS_Z, UNASSIGNED), ShadowFaceCull.towardLight(0.5f, -0.7f, 0.1f));
    }

    @Test
    void dropsBothFacingsOfAnAxisTheLightIsParallelTo() {
        // Straight overhead: walls are edge-on and cover no shadow-map texels.
        assertEquals(bits(POS_Y, UNASSIGNED), ShadowFaceCull.towardLight(0, 1, 0));
        assertEquals(bits(NEG_X, POS_Y, UNASSIGNED), ShadowFaceCull.towardLight(-0.6f, 0.8f, -0.0f));
    }

    @Test
    void unusableDirectionsCullNothing() {
        assertEquals(ShadowFaceCull.NO_CULL, ShadowFaceCull.towardLight(0, 0, 0));
        assertEquals(ShadowFaceCull.NO_CULL, ShadowFaceCull.towardLight(Float.NaN, 1, 0));
        assertEquals(ShadowFaceCull.NO_CULL, ShadowFaceCull.towardLight(0.5f, Float.POSITIVE_INFINITY, 0));
    }

    @Test
    void keepsExactlyOneFacingPerAxisForAnyGeneralDirection() {
        var random = new Random(26_3);
        for (int i = 0; i < 1000; i++) {
            float x = random.nextFloat() * 2 - 1, y = random.nextFloat() * 2 - 1, z = random.nextFloat() * 2 - 1;
            int mask = ShadowFaceCull.towardLight(x, y, z);
            assertNotEquals(0, mask & bits(UNASSIGNED), "unassigned faces are always drawn");
            assertEquals(1, Integer.bitCount(mask & bits(POS_X, NEG_X)));
            assertEquals(1, Integer.bitCount(mask & bits(POS_Y, NEG_Y)));
            assertEquals(1, Integer.bitCount(mask & bits(POS_Z, NEG_Z)));
            assertEquals(x > 0, (mask & bits(POS_X)) != 0);
            assertEquals(y > 0, (mask & bits(POS_Y)) != 0);
            assertEquals(z > 0, (mask & bits(POS_Z)) != 0);
        }
    }
}
