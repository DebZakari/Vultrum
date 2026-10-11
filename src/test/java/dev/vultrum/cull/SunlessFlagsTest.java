package dev.vultrum.cull;

import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.DataLayer;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SunlessFlagsTest {
    private static final int[][] NEIGHBOURS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    /** Sky-dark everywhere except the sections a test places. Counts lookups. */
    private static final class World implements SunlessFlags.SkyLight {
        private final Map<Long, DataLayer> placed = new HashMap<>();
        int lookups;

        World put(int x, int y, int z, DataLayer layer) {
            placed.put(SectionPos.asLong(x, y, z), layer);
            return this;
        }

        @Override
        public DataLayer layer(int x, int y, int z) {
            lookups++;
            long key = SectionPos.asLong(x, y, z);
            return placed.containsKey(key) ? placed.get(key) : new DataLayer();
        }
    }

    private static DataLayer litAt(int x, int y, int z) {
        var layer = new DataLayer();
        layer.set(x, y, z, 1);
        return layer;
    }

    @Test
    void darkMeansSkyLightZeroThroughout() {
        assertTrue(SunlessFlags.dark(new DataLayer()), "homogeneous zero");
        assertTrue(SunlessFlags.dark(new DataLayer(new byte[DataLayer.SIZE])), "stored zeros");
        assertFalse(SunlessFlags.dark(new DataLayer(15)), "homogeneous full light");
        assertFalse(SunlessFlags.dark(litAt(0, 0, 0)));
        assertFalse(SunlessFlags.dark(litAt(15, 15, 15)), "the last nibble counts");
        assertFalse(SunlessFlags.dark(null), "no light data counts as lit");
    }

    @Test
    void homogeneousLayersStayUnallocated() {
        var dark = new DataLayer();
        var lit = new DataLayer(15);
        SunlessFlags.dark(dark);
        SunlessFlags.dark(lit);
        assertTrue(dark.isDefinitelyHomogenous());
        assertTrue(lit.isDefinitelyHomogenous());
    }

    @Test
    void aSectionIsSunlessOnlyWithItsSixFaceNeighboursDark() {
        assertTrue(new SunlessFlags(64).sunless(new World(), 0, 0, 0));
        assertFalse(new SunlessFlags(64).sunless(new World().put(0, 0, 0, litAt(8, 8, 8)), 0, 0, 0), "itself lit");
        for (int[] n : NEIGHBOURS)
            assertFalse(new SunlessFlags(64).sunless(new World().put(n[0], n[1], n[2], litAt(0, 0, 0)), 0, 0, 0),
                    "neighbour " + n[0] + "," + n[1] + "," + n[2] + " lit");
        assertTrue(new SunlessFlags(64).sunless(new World().put(1, 1, 0, new DataLayer(15)).put(2, 0, 0, new DataLayer(15)), 0, 0, 0),
                "diagonal and farther sections don't count");
    }

    @Test
    void aNeighbourWithoutLightDataCountsAsLit() {
        assertFalse(new SunlessFlags(64).sunless(new World().put(0, -1, 0, null), 0, 0, 0));
    }

    @Test
    void flagsAreKeptUntilTheSectionOrAFaceNeighbourChanges() {
        var flags = new SunlessFlags(64);
        var world = new World();
        assertTrue(flags.sunless(world, 0, 0, 0));
        assertEquals(7, world.lookups);

        world.put(1, 0, 0, litAt(0, 4, 4));
        assertTrue(flags.sunless(world, 0, 0, 0), "cached");
        assertEquals(7, world.lookups);

        flags.changed(2, 0, 0);
        assertTrue(flags.sunless(world, 0, 0, 0), "a section two away doesn't drop it");
        assertEquals(7, world.lookups);

        flags.changed(1, 0, 0);
        assertFalse(flags.sunless(world, 0, 0, 0), "recomputed after the neighbour changed");
    }

    @Test
    void eachLayerIsReadOnceUntilItsSectionChanges() {
        var flags = new SunlessFlags(64);
        var world = new World();
        flags.sunless(world, 0, 0, 0);
        flags.sunless(world, 1, 0, 0);
        assertEquals(12, world.lookups, "the two sections share two layers");
        flags.changed(1, 0, 0);
        flags.sunless(world, 0, 0, 0);
        assertEquals(13, world.lookups, "only the changed section's layer is read again");
    }

    @Test
    void aChangeDropsTheSectionAndItsSixFaceNeighbours() {
        var flags = new SunlessFlags(64);
        var world = new World();
        flags.sunless(world, 0, 0, 0);
        for (int[] n : NEIGHBOURS) flags.sunless(world, n[0], n[1], n[2]);
        flags.sunless(world, 1, 1, 0);
        assertEquals(8, flags.size());
        flags.changed(0, 0, 0);
        assertEquals(1, flags.size(), "only the diagonal section is kept");
    }

    @Test
    void everythingIsDroppedPastTheCap() {
        var flags = new SunlessFlags(4);
        var world = new World();
        for (int x = 0; x < 4; x++) flags.sunless(world, x, 0, 0);
        assertEquals(4, flags.size());
        flags.sunless(world, 4, 0, 0);
        assertEquals(1, flags.size());
    }
}
