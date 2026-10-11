package dev.vultrum.cull;

import net.minecraft.world.level.chunk.DataLayer;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SunlessShadowTest {
    private static final Vector3f SUN = new Vector3f(0.3f, 0.9f, 0.2f);
    private static final SunlessFlags.SkyLight DARK = (x, y, z) -> new DataLayer();
    private static final SunlessFlags.SkyLight LIT = (x, y, z) -> new DataLayer(15);

    private static boolean plannedAfter(Vector3f light, Boolean voxelised) {
        ShadowPass.plan(light, voxelised, 192);
        return SunlessShadow.planned();
    }

    @AfterEach
    void reset() {
        ShadowPass.configure(false, false, false);
    }

    @Test
    void plansWithTheFaceCullOff() {
        ShadowPass.configure(false, true, true);
        assertTrue(plannedAfter(SUN, false));
    }

    @Test
    void pausesWithoutALightOrForVoxelisingPacks() {
        ShadowPass.configure(true, true, true);
        assertFalse(plannedAfter(null, false), "no pack draws terrain");
        assertFalse(plannedAfter(SUN, true), "voxelising pack");
        assertFalse(plannedAfter(SUN, null), "voxelise flag unreadable");
        assertTrue(plannedAfter(SUN, false), "resumes for a surface-only pack");
    }

    @Test
    void offWhenConfiguredOffOrUnhooked() {
        ShadowPass.configure(true, false, true);
        assertFalse(plannedAfter(SUN, false));
        ShadowPass.configure(true, true, false);
        assertFalse(plannedAfter(SUN, false));
    }

    /** Whether a planned shadow pass in {@code world} lit by {@code light} skips section (0, -4, 0). */
    private static boolean skippedIn(Object world, SunlessFlags.SkyLight light) {
        return skippedIn(world, light, ShadowRegionTest.everywhere());
    }

    private static boolean skippedIn(Object world, SunlessFlags.SkyLight light, ShadowRegion.Bounds region) {
        ShadowRegion.use(region);
        SunlessShadow.begin(world, light);
        try { return SunlessShadow.skip(0, -4, 0); }
        finally { ShadowPass.end(); }
    }

    @Test
    void skipsSunlessSectionsOnlyInsideAShadowPass() {
        ShadowPass.configure(false, true, true);
        plannedAfter(SUN, false);
        assertFalse(SunlessShadow.skip(0, -4, 0), "before the pass");
        assertTrue(skippedIn(new Object(), DARK));
        assertFalse(SunlessShadow.skip(0, -4, 0), "after the pass");
    }

    @Test
    void skipsOnlyInsideTheShadowRegion() {
        ShadowPass.configure(false, true, true);
        plannedAfter(SUN, false);
        assertFalse(skippedIn(new Object(), DARK, ShadowRegion.Bounds.NONE));
    }

    @Test
    void flagsLastUntilASectionChangesOrTheLevelDoes() {
        ShadowPass.configure(false, true, true);
        plannedAfter(SUN, false);
        var level = new Object();
        assertTrue(skippedIn(level, DARK));
        assertTrue(skippedIn(level, LIT), "cached");
        SunlessShadow.sectionChanged(0, -3, 0);
        assertFalse(skippedIn(level, LIT), "a neighbour changed");
        assertFalse(skippedIn(level, DARK), "still cached as lit");
        assertTrue(skippedIn(new Object(), DARK), "another level starts over");
    }
}
