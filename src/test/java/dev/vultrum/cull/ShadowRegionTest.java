package dev.vultrum.cull;

import dev.vultrum.cull.ShadowRegion.Bounds;
import dev.vultrum.cull.ShadowRegion.ColumnTops;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ShadowRegionTest {
    private static final float INF = Float.POSITIVE_INFINITY;
    /** Columns -48 to 48 with every section built. */
    private static final ColumnTops BUILT = flat(ColumnTops.EMPTY);

    /** A region holding every section: what beta 1 assumed. */
    static Bounds everywhere() {
        return Bounds.of(0, 1, 0, 0, 0, 0, INF, INF, INF, flat(ColumnTops.EMPTY), BUILT, depthRow(0, 0, 0, 0));
    }

    /** Columns -48 to 48 on x and z, each with its top at world y {@code top}. */
    private static ColumnTops flat(int top) {
        return tops(-48, 48, (x, z) -> top);
    }

    private static ColumnTops tops(int from, int to, java.util.function.IntBinaryOperator top) {
        var tops = new ColumnTops();
        tops.reset(from, from, to - from + 1);
        for (int x = from; x <= to; x++) for (int z = from; z <= to; z++) tops.set(x, z, top.applyAsInt(x, z));
        tops.build();
        return tops;
    }

    /** An orthographic matrix with the given depth row, column-major. */
    private static float[] depthRow(float a, float b, float c, float d) {
        float[] m = new float[16];
        m[0] = m[5] = m[15] = 1;
        m[2] = a;
        m[6] = b;
        m[10] = c;
        m[14] = d;
        return m;
    }

    /** Depth along the light, clipped {@code depth} blocks either side of the camera. */
    private static float[] window(Vector3f light, float depth) {
        return depthRow(-light.x / depth, -light.y / depth, -light.z / depth, 0);
    }

    private static Vector3f sun(double degreesUp) {
        double up = Math.toRadians(degreesUp);
        return new Vector3f((float) Math.cos(up), (float) Math.sin(up), 0);
    }

    /**
     * The region around a camera at (0, {@code camY}, 0): Vitrail's box of ±{@code bound}, render distance
     * {@code renderDistance} with every column in, terrain up to {@code top} (world y), depth window ±{@code depth}.
     */
    private static Bounds region(Vector3f light, double camY, float bound, int renderDistance, float top, float depth) {
        float reach = renderDistance * 16 - 32;
        return Bounds.of(light.x, light.y, light.z, 0, camY, 0, bound, reach, reach, flat((int) top), BUILT, window(light, depth));
    }

    @Test
    void coversTerrainUnderAHighSun() {
        Bounds noon = region(sun(60), 70, 192, 16, 80, 100);
        assertTrue(noon.covers(0, 4, 0), "the camera's section");
        assertTrue(noon.covers(3, 3, -2));
        assertTrue(noon.covers(-3, 2, 9));
        assertFalse(noon.covers(11, 4, 0), "against Vitrail's box");
        assertFalse(noon.covers(0, -10, 0), "the rise to the surface leaves the depth window");
    }

    @Test
    void leavesTheAncientCityOutUnderALowSun() {
        // The deep-dark sun shafts: the city at y -42, terrain up to y 96, the sun 15° up at 07:00. A ray from the city
        // runs over 500 blocks sideways before it clears the terrain, far past Vitrail's ±192.
        Bounds morning = region(sun(15), -42, 192, 16, 96, 1000);
        for (int x = -12; x <= 12; x++) for (int y = -4; y <= -2; y++) assertFalse(morning.covers(x, y, 0));
        // Nearly overhead the rays clear the terrain 25 blocks sideways, inside the box and the depth window.
        Bounds noon = region(sun(80), -42, 192, 16, 96, 1000);
        assertTrue(noon.covers(0, -3, 0));
        assertFalse(region(sun(80), -42, 192, 16, 96, 100).covers(0, -3, 0), "past a 100-block depth window");
    }

    @Test
    void noRegionWithoutALightFromAbove() {
        float[] m = depthRow(0, 0, 0, 0);
        assertSame(Bounds.NONE, Bounds.of(0.3f, 0, 0.2f, 0, 0, 0, INF, INF, INF, flat(0), BUILT, m), "at the horizon");
        assertSame(Bounds.NONE, Bounds.of(0.3f, -0.5f, 0.2f, 0, 0, 0, INF, INF, INF, flat(0), BUILT, m), "the moon's underside");
        assertSame(Bounds.NONE, Bounds.of(0.3f, Float.NaN, 0.2f, 0, 0, 0, INF, INF, INF, flat(0), BUILT, m));
        assertFalse(Bounds.NONE.covers(0, 0, 0));
    }

    @Test
    void noRegionFromUnusableInputs() {
        float[] m = depthRow(0, 0.01f, 0, 0);
        assertSame(Bounds.NONE, Bounds.of(Float.NaN, 1, 0, 0, 0, 0, INF, INF, INF, flat(0), BUILT, m));
        assertSame(Bounds.NONE, Bounds.of(0, 1, 0, 0, 0, 0, INF, 0, INF, flat(0), BUILT, m), "no render distance");
        assertSame(Bounds.NONE, Bounds.of(0, 1, 0, 0, 0, 0, INF, Float.NaN, INF, flat(0), BUILT, m));
        assertSame(Bounds.NONE, Bounds.of(0, 1, 0, 0, 0, 0, 0, INF, INF, flat(0), BUILT, m), "a box smaller than a section");
        float[] nan = m.clone();
        nan[14] = Float.NaN;
        assertSame(Bounds.NONE, Bounds.of(0, 1, 0, 0, 0, 0, INF, INF, INF, flat(0), BUILT, nan));
        float[] perspective = new float[16];
        new Matrix4f().perspective(1.2f, 1, 0.05f, 300).get(perspective);
        assertFalse(Bounds.orthographic(perspective));
        assertSame(Bounds.NONE, Bounds.of(0, 1, 0, 0, 0, 0, INF, INF, INF, flat(0), BUILT, perspective));
        float[] ortho = new float[16];
        new Matrix4f().ortho(-160, 160, -160, 160, -100, 300).get(ortho);
        assertTrue(Bounds.orthographic(ortho));
    }

    @Test
    void onlyColumnsUnderTheRaysRaiseThem() {
        // A cave at y 32 to 48 under ground at y 64, the sun 45° up toward +x: the rays clear the ground 16 to 32
        // blocks toward the sun, in columns 1 and 2.
        Vector3f sun = sun(45);
        float[] m = window(sun, 100);
        assertTrue(Bounds.of(sun.x, sun.y, sun.z, 0, 40, 0, INF, INF, INF, flat(64), BUILT, m).covers(0, 2, 0));
        // A tower to y 160 in column 2 lifts them to its top, past the depth window.
        ColumnTops tower = tops(-48, 48, (x, z) -> x == 2 && z == 0 ? 160 : 64);
        assertFalse(Bounds.of(sun.x, sun.y, sun.z, 0, 40, 0, INF, INF, INF, tower, BUILT, m).covers(0, 2, 0));
        assertTrue(Bounds.of(sun.x, sun.y, sun.z, 0, 40, 0, INF, INF, INF, tower, BUILT, window(sun, 300)).covers(0, 2, 0));
        // Behind the cave, away from the sun, or past where the rays have cleared the ground, it changes nothing.
        ColumnTops behind = tops(-48, 48, (x, z) -> x == -3 && z == 0 ? 160 : 64);
        assertTrue(Bounds.of(sun.x, sun.y, sun.z, 0, 40, 0, INF, INF, INF, behind, BUILT, m).covers(0, 2, 0));
        ColumnTops beyond = tops(-48, 48, (x, z) -> x == 3 && z == 0 ? 160 : 64);
        assertTrue(Bounds.of(sun.x, sun.y, sun.z, 0, 40, 0, INF, INF, INF, beyond, BUILT, m).covers(0, 2, 0));
        // An unknown column under the rays: nothing is known above it.
        ColumnTops unknown = tops(-48, 48, (x, z) -> x == 1 && z == 0 ? ColumnTops.UNKNOWN : 64);
        assertFalse(Bounds.of(sun.x, sun.y, sun.z, 0, 40, 0, INF, INF, INF, unknown, BUILT, m).covers(0, 2, 0));
        // Past the grid every column is unknown.
        assertFalse(Bounds.of(sun.x, sun.y, sun.z, 0, 40, 0, INF, INF, INF, tops(-1, 1, (x, z) -> 64), BUILT, m).covers(0, 2, 0));
    }

    @Test
    void columnTopsMatchABruteForceMaximum() {
        var random = new Random(26_3_25);
        var tops = new ColumnTops();
        for (int trial = 0; trial < 200; trial++) {
            int size = 1 + random.nextInt(40), x0 = random.nextInt(200) - 100, z0 = random.nextInt(200) - 100;
            int[][] grid = new int[size][size];
            tops.reset(x0, z0, size);
            for (int i = 0; i < size; i++) for (int j = 0; j < size; j++) {
                int roll = random.nextInt(20);
                grid[i][j] = roll == 0 ? ColumnTops.EMPTY : roll == 1 ? ColumnTops.UNKNOWN : random.nextInt(384) - 64;
                if (roll != 1) tops.set(x0 + i, z0 + j, grid[i][j]);
            }
            // Some tables fill only the levels for narrow rectangles: wider ones read as unknown.
            int widest = random.nextBoolean() ? size : 1 + random.nextInt(size);
            tops.build(widest);
            for (int q = 0; q < 200; q++) {
                int xa = x0 - 2 + random.nextInt(size + 4), xb = xa + random.nextInt(size + 2);
                int za = z0 - 2 + random.nextInt(size + 4), zb = za + random.nextInt(size + 2);
                int expected = ColumnTops.UNKNOWN;
                boolean filled = Math.max(xb - xa, zb - za) + 1 < Integer.highestOneBit(widest) * 2;
                if (filled && xa >= x0 && za >= z0 && xb < x0 + size && zb < z0 + size) {
                    expected = ColumnTops.EMPTY;
                    for (int x = xa; x <= xb; x++) for (int z = za; z <= zb; z++) expected = Math.max(expected, grid[x - x0][z - z0]);
                }
                assertEquals(expected, tops.max(xa, xb, za, zb), "size " + size + ", columns " + xa + ".." + xb + " by " + za + ".." + zb);
            }
        }
    }

    @Test
    void depthWindowLimitsTheRise() {
        Vector3f up = new Vector3f(0, 1, 0);
        // Depth ±60 blocks along the light, less a section's 16 blocks of margin either side.
        assertFalse(Bounds.of(0, 1, 0, 0, 0, 0, INF, INF, INF, flat(80), BUILT, window(up, 60)).covers(0, -1, 0));
        assertTrue(Bounds.of(0, 1, 0, 0, 0, 0, INF, INF, INF, flat(44), BUILT, window(up, 60)).covers(0, -1, 0));
        assertFalse(Bounds.of(0, 1, 0, 0, 0, 0, INF, INF, INF, flat(44), BUILT, window(up, 60)).covers(0, -3, 0), "the far side");
    }

    @Test
    void reachLimitsTheRegion() {
        float[] m = depthRow(0, 0, 0, 0);
        // Sodium draws 24 blocks out: every ray must stay within them.
        Bounds near = Bounds.of(0, 1, 0, 0, 0, 0, INF, 24, INF, flat(16), BUILT, m);
        assertTrue(near.covers(0, 0, 0));
        assertTrue(near.covers(-1, 0, -1));
        assertFalse(near.covers(1, 0, 0));
        // The rays shift with a slanted light, toward the light only.
        Bounds slanted = Bounds.of(1, 1, 0, 0, 0, 0, INF, 34, INF, flat(16), BUILT, m);
        assertTrue(slanted.covers(-1, 0, 0), "rays from x -16..0 run to x 16");
        assertFalse(slanted.covers(0, 0, 0), "rays from x 0..16 run to x 32");
        assertFalse(slanted.covers(-1, -1, 0), "rays from y -16 run 32 blocks");
        Bounds vertical = Bounds.of(0, 1, 0, 0, 0, 0, INF, INF, 64, flat(16), BUILT, m);
        assertTrue(vertical.covers(0, -4, 0));
        assertFalse(vertical.covers(0, -5, 0));
        assertFalse(Bounds.of(0, 1, 0, 0, 0, 0, INF, INF, 64, flat(80), BUILT, m).covers(0, 0, 0), "terrain above the reach");
    }

    @Test
    void onlyUnbuiltSectionsTheRaysCrossCount() {
        // Ground at y 16, the light 45° up toward +x: rays from section (0, 0, 0) rise to y 16 over columns 0 and 1,
        // and from section (0, -1, 0) to y 16 over columns 0 to 2.
        float[] m = depthRow(0, 0, 0, 0);
        ColumnTops ground = flat(16);
        // Column 1 hasn't been sent: unknown.
        ColumnTops hole = tops(-48, 48, (x, z) -> x == 1 && z == 0 ? ColumnTops.UNKNOWN : ColumnTops.EMPTY);
        assertFalse(Bounds.of(1, 1, 0, 0, 0, 0, INF, INF, INF, ground, hole, m).covers(0, 0, 0));
        assertTrue(Bounds.of(1, 1, 0, 0, 0, 0, INF, INF, INF, ground, hole, m).covers(-2, 0, 0), "rays that stop short of it");
        assertTrue(Bounds.of(1, 1, 0, 0, 0, 0, INF, INF, INF, ground, hole, m).covers(2, 0, 0), "rays past it");
        // Section (1, -1, 0) is unbuilt: it lies under the rays from (0, 0, 0) but across those from (0, -1, 0).
        ColumnTops rock = tops(-48, 48, (x, z) -> x == 1 && z == 0 ? 0 : ColumnTops.EMPTY);
        assertTrue(Bounds.of(1, 1, 0, 0, 0, 0, INF, INF, INF, ground, rock, m).covers(0, 0, 0));
        assertFalse(Bounds.of(1, 1, 0, 0, 0, 0, INF, INF, INF, ground, rock, m).covers(0, -1, 0));
        // An unbuilt section right under a section is below its rays.
        ColumnTops under = tops(-48, 48, (x, z) -> x == 0 && z == 0 ? -16 : ColumnTops.EMPTY);
        assertTrue(Bounds.of(1, 1, 0, 0, 0, 0, INF, INF, INF, ground, under, m).covers(0, -1, 0));
        assertFalse(Bounds.of(1, 1, 0, 0, 0, 0, INF, INF, INF, ground, under, m).covers(0, -2, 0), "its own section");
    }

    // A voxel world's shadow map, traced with and without the culls.

    private static final int SX = 12, SY = 8, SZ = 12, X = SX * 16, Y = SY * 16, Z = SZ * 16;

    /** Blocks, the sections Vitrail and Sodium draw, and the region worked out as {@code ShadowRegion.measure} does. */
    private static final class World {
        final boolean[] solid = new boolean[X * Y * Z];
        final boolean[] holes = new boolean[SX * SZ];
        /** Sections Sodium hasn't built: its walk from the camera hasn't reached them. */
        final boolean[] unbuilt = new boolean[SX * SY * SZ];
        final boolean[] drawn = new boolean[SX * SY * SZ];
        final boolean[] covered = new boolean[SX * SY * SZ];
        double camX, camY, camZ;
        Vector3f light;
        float depth;
        /** Rays whose first drawn face was culled, where the shadow held through a face further on. */
        int saved;

        boolean solid(int x, int y, int z) {
            return solid[(y * Z + z) * X + x];
        }

        void set(int x, int y, int z, boolean block) {
            if (x >= 0 && x < X && y >= 0 && y < Y && z >= 0 && z < Z) solid[(y * Z + z) * X + x] = block;
        }

        static int section(int sx, int sy, int sz) {
            return (sy * SZ + sz) * SX + sx;
        }

        /** Rolling terrain with caves, overhangs, floating islands and pillars. */
        void generate(Random random) {
            Arrays.fill(solid, false);
            double f1 = 0.01 + random.nextDouble() * 0.05, f2 = 0.01 + random.nextDouble() * 0.05;
            double p1 = random.nextDouble() * 7, p2 = random.nextDouble() * 7;
            int base = 64 + random.nextInt(24), swing = 4 + random.nextInt(16);
            for (int x = 0; x < X; x++) for (int z = 0; z < Z; z++) {
                int height = (int) (base + swing * Math.sin(x * f1 + p1) + swing / 2.0 * Math.sin(z * f2 + p2)) + random.nextInt(2);
                for (int y = 0; y < Math.min(height, Y); y++) set(x, y, z, true);
            }
            for (int i = 0, caves = 20 + random.nextInt(40); i < caves; i++) {
                int cx = random.nextInt(X), cy = 4 + random.nextInt(60), cz = random.nextInt(Z), r = 3 + random.nextInt(10);
                ball(cx, cy, cz, r, false);
            }
            for (int i = 0, islands = random.nextInt(12); i < islands; i++) {
                box(random.nextInt(X), 70 + random.nextInt(40), random.nextInt(Z), 2 + random.nextInt(12), 1 + random.nextInt(4),
                        2 + random.nextInt(12), true);
            }
            for (int i = 0, pillars = random.nextInt(8); i < pillars; i++) {
                int px = random.nextInt(X), pz = random.nextInt(Z), w = 1 + random.nextInt(3);
                box(px, 0, pz, w, 100 + random.nextInt(Y - 100), w, true);
            }
        }

        /** Raises the ground toward the light by {@code slope} blocks per block, and lowers it away from the light. */
        void ramp(double slope) {
            double along = Math.hypot(light.x, light.z);
            if (along == 0) return;
            for (int x = 0; x < X; x++) for (int z = 0; z < Z; z++) {
                int surface = Y - 1;
                while (surface >= 0 && !solid(x, surface, z)) surface--;
                int lift = (int) (slope * ((x - X / 2.0) * light.x + (z - Z / 2.0) * light.z) / along);
                for (int y = Math.max(surface + 1, 0); y <= surface + lift; y++) set(x, y, z, true);
                for (int y = Math.max(surface + lift + 1, 0); y <= surface; y++) set(x, y, z, false);
            }
        }

        void ball(int cx, int cy, int cz, int r, boolean block) {
            for (int x = cx - r; x <= cx + r; x++) for (int y = cy - r; y <= cy + r; y++) for (int z = cz - r; z <= cz + r; z++) {
                int dx = x - cx, dy = y - cy, dz = z - cz;
                if (dx * dx + dy * dy + dz * dz <= r * r) set(x, y, z, block);
            }
        }

        void box(int x0, int y0, int z0, int w, int h, int d, boolean block) {
            for (int x = x0; x < x0 + w; x++) for (int y = y0; y < y0 + h; y++) for (int z = z0; z < z0 + d; z++) set(x, y, z, block);
        }

        /** Nearest horizontal distance from the camera to column (sx, sz). */
        double columnDistance(int sx, int sz) {
            double dx = Math.max(0, Math.max(sx * 16 - camX, camX - (sx * 16 + 16)));
            double dz = Math.max(0, Math.max(sz * 16 - camZ, camZ - (sz * 16 + 16)));
            return Math.sqrt(dx * dx + dz * dz);
        }

        /**
         * The drawn sections, as tight as Vitrail and Sodium could make them: reaching into Vitrail's box,
         * nearest point within the render distance (Sodium's cylinder), not in a hole, built. Then the region.
         * {@code region} null: worked out as {@code ShadowRegion.measure} does.
         */
        void draw(float bound, int renderDistance, Bounds region) {
            float far = renderDistance * 16;
            for (int sx = 0; sx < SX; sx++) for (int sz = 0; sz < SZ; sz++) for (int sy = 0; sy < SY; sy++) {
                double dy = Math.max(0, Math.max(sy * 16 - camY, camY - (sy * 16 + 16)));
                boolean inBox = sx * 16 < camX + bound && sx * 16 + 16 > camX - bound && sy * 16 < camY + bound
                        && sy * 16 + 16 > camY - bound && sz * 16 < camZ + bound && sz * 16 + 16 > camZ - bound;
                int s = section(sx, sy, sz);
                drawn[s] = !holes[sz * SX + sx] && !unbuilt[s] && columnDistance(sx, sz) < far && dy < far && inBox;
            }
            if (region == null) {
                // Columns in a hole stay unknown. The grid ends at the world's edge, past which nothing is drawn.
                var tops = new ColumnTops();
                var allTops = new ColumnTops();
                var unbuiltTops = new ColumnTops();
                tops.reset(0, 0, Math.max(SX, SZ));
                allTops.reset(0, 0, Math.max(SX, SZ));
                unbuiltTops.reset(0, 0, Math.max(SX, SZ));
                for (int sx = 0; sx < SX; sx++) for (int sz = 0; sz < SZ; sz++) {
                    if (holes[sz * SX + sx]) continue;
                    int top = ColumnTops.EMPTY, unbuiltTop = ColumnTops.EMPTY;
                    for (int sy = SY - 1; sy >= 0; sy--) {
                        if (!filled(sx, sy, sz)) continue;
                        if (top == ColumnTops.EMPTY) top = (sy + 1) * 16;
                        if (unbuilt[section(sx, sy, sz)] && unbuiltTop == ColumnTops.EMPTY) unbuiltTop = (sy + 1) * 16;
                    }
                    tops.set(sx, sz, top);
                    allTops.set(sx, sz, top);
                    unbuiltTops.set(sx, sz, unbuiltTop);
                }
                // As measure() builds it, and in full: the narrow table covers the same sections.
                tops.build((int) Math.ceil(Math.max(Math.abs(light.x), Math.abs(light.z)) / light.y) + 3);
                allTops.build();
                unbuiltTops.build();
                float reach = far - 32;
                region = Bounds.of(light.x, light.y, light.z, camX, camY, camZ, bound, reach, reach, tops, unbuiltTops,
                        window(light, depth));
                Bounds full = Bounds.of(light.x, light.y, light.z, camX, camY, camZ, bound, reach, reach, allTops,
                        unbuiltTops, window(light, depth));
                for (int sx = 0; sx < SX; sx++) for (int sz = 0; sz < SZ; sz++) for (int sy = 0; sy < SY; sy++) {
                    assertEquals(full.covers(sx, sy, sz), region.covers(sx, sy, sz), "section " + sx + " " + sy + " " + sz);
                }
            }
            for (int sx = 0; sx < SX; sx++) for (int sz = 0; sz < SZ; sz++) for (int sy = 0; sy < SY; sy++) {
                covered[section(sx, sy, sz)] = region.covers(sx, sy, sz);
            }
        }

        boolean filled(int sx, int sy, int sz) {
            for (int x = sx * 16; x < sx * 16 + 16; x++) for (int y = sy * 16; y < sy * 16 + 16; y++) for (int z = sz * 16; z < sz * 16 + 16; z++) {
                if (solid(x, y, z)) return true;
            }
            return false;
        }

        /** What {@link #trace} finds: no face, a face the culls keep, a face they drop. */
        static final int NO_FACE = 0, KEPT = 1, CULLABLE = 2;

        /**
         * The first face between (px, py, pz), in the air, and the light that the shadow map holds: the shadow test.
         * A face is drawn where its block's section is and its depth is inside the window. Faces pointing away from
         * the light in covered sections are {@link #CULLABLE}, and with {@code cull} they're skipped.
         */
        int trace(double px, double py, double pz, boolean cull) {
            float lx = light.x, ly = light.y, lz = light.z;
            int x = (int) Math.floor(px), y = (int) Math.floor(py), z = (int) Math.floor(pz);
            int stepX = lx > 0 ? 1 : -1, stepZ = lz > 0 ? 1 : -1;
            double tx = lx > 0 ? (x + 1 - px) / lx : lx < 0 ? (px - x) / -lx : Double.POSITIVE_INFINITY;
            double ty = (y + 1 - py) / ly;
            double tz = lz > 0 ? (z + 1 - pz) / lz : lz < 0 ? (pz - z) / -lz : Double.POSITIVE_INFINITY;
            double dx = lx != 0 ? 1 / Math.abs(lx) : Double.POSITIVE_INFINITY, dy = 1 / ly;
            double dz = lz != 0 ? 1 / Math.abs(lz) : Double.POSITIVE_INFINITY;
            boolean inside = solid(x, y, z);
            int lastX = x, lastY = y, lastZ = z;
            while (true) {
                double t;
                if (tx <= ty && tx <= tz) {
                    t = tx;
                    x += stepX;
                    tx += dx;
                } else if (ty <= tz) {
                    t = ty;
                    y++;
                    ty += dy;
                } else {
                    t = tz;
                    z += stepZ;
                    tz += dz;
                }
                if (x < 0 || x >= X || z < 0 || z >= Z) return NO_FACE; // Past what's drawn.
                boolean next = y < Y && solid(x, y, z); // Above the world is air: the top layer's faces are drawn.
                if (next != inside) {
                    // Entering a block: its face points back along the ray, away from the light. Leaving one: toward it.
                    int s = next ? section(x >> 4, y >> 4, z >> 4) : section(lastX >> 4, lastY >> 4, lastZ >> 4);
                    double along = (px + lx * t - camX) * lx + (py + ly * t - camY) * ly + (pz + lz * t - camZ) * lz;
                    if (drawn[s] && Math.abs(along) <= depth) {
                        if (!next || !covered[s]) return KEPT;
                        if (!cull) return CULLABLE;
                    }
                }
                if (y >= Y) return NO_FACE; // The sky.
                inside = next;
                lastX = x;
                lastY = y;
                lastZ = z;
            }
        }

        /** The air points whose shadow the culls change, of {@code rays} sampled; half below y 64, half anywhere. */
        int mismatches(Random random, int rays) {
            int wrong = 0;
            for (int i = 0; i < rays; i++) {
                double px, py, pz;
                int maxY = i % 2 == 0 ? 64 : Y;
                int tries = 0;
                do {
                    px = random.nextDouble() * X;
                    py = random.nextDouble() * maxY;
                    pz = random.nextDouble() * Z;
                } while (solid((int) px, (int) py, (int) pz) && ++tries < 200);
                if (solid((int) px, (int) py, (int) pz)) continue;
                int reference = trace(px, py, pz, false), culled = trace(px, py, pz, true);
                if ((reference != NO_FACE) != (culled != NO_FACE)) wrong++;
                else if (reference == CULLABLE) saved++;
            }
            return wrong;
        }
    }

    private static World world(Random random) {
        World world = new World();
        world.generate(random);
        double up = Math.toRadians(3 + random.nextDouble() * 87), around = random.nextDouble() * 2 * Math.PI;
        world.light = new Vector3f((float) (Math.cos(up) * Math.cos(around)), (float) Math.sin(up), (float) (Math.cos(up) * Math.sin(around)));
        if (random.nextInt(8) == 0) world.light.set(0, 1, 0);
        world.camX = X / 2.0 - 16 + random.nextDouble() * 32;
        world.camY = 8 + random.nextDouble() * 104;
        world.camZ = Z / 2.0 - 16 + random.nextDouble() * 32;
        world.depth = 40 + random.nextFloat() * 260;
        Arrays.fill(world.holes, false);
        for (int i = 0, holes = random.nextInt(4); i < holes; i++) world.holes[random.nextInt(SZ) * SX + random.nextInt(SX)] = true;
        // Sections Sodium's walk hasn't reached: some anywhere, and in some worlds all the rock below a depth.
        double scattered = random.nextDouble() * 0.3;
        int deep = random.nextInt(3) == 0 ? 1 + random.nextInt(4) : 0;
        for (int sy = 0; sy < SY; sy++) for (int sz = 0; sz < SZ; sz++) for (int sx = 0; sx < SX; sx++) {
            world.unbuilt[World.section(sx, sy, sz)] = sy < deep || random.nextDouble() < scattered;
        }
        return world;
    }

    @Test
    void theCullsNeverChangeAShadowInsideTheRegion() {
        var random = new Random(26_3_24);
        int saved = 0, coveredSections = 0;
        for (int trial = 0; trial < 40; trial++) {
            World world = world(random);
            float bound = new float[] {48, 64, 96, 128, -1}[random.nextInt(5)];
            world.draw(bound < 0 ? Float.POSITIVE_INFINITY : bound, 5 + random.nextInt(6), null);
            for (boolean c : world.covered) if (c) coveredSections++;
            assertEquals(0, world.mismatches(random, 3000), "trial " + trial + ": light " + world.light + ", camera y "
                    + world.camY + ", bound " + bound + ", depth " + world.depth);
            saved += world.saved;
        }
        assertTrue(coveredSections > 400, "the region covers sections: " + coveredSections);
        assertTrue(saved > 150, "rays whose first face was culled, the shadow held further on: " + saved);
    }

    @Test
    void theCullsHoldUnderGroundRisingTowardTheLight() {
        // Each raise moves where the rays are at h toward the light, over higher ground, so h rises in several steps.
        var random = new Random(26_3_26);
        int saved = 0, coveredSections = 0;
        for (int trial = 0; trial < 60; trial++) {
            World world = world(random);
            if (world.light.y > 0.95f) continue;
            world.ramp(0.3 + random.nextDouble() * 0.7);
            world.camY = 8 + random.nextDouble() * 56;
            float bound = new float[] {48, 64, 96, 128}[random.nextInt(4)];
            world.draw(bound, 5 + random.nextInt(6), null);
            for (boolean c : world.covered) if (c) coveredSections++;
            assertEquals(0, world.mismatches(random, 3000), "trial " + trial + ": light " + world.light + ", camera y "
                    + world.camY + ", bound " + bound + ", depth " + world.depth);
            saved += world.saved;
        }
        assertTrue(coveredSections > 100, "the region covers sections: " + coveredSections);
        assertTrue(saved > 20, "rays whose first face was culled, the shadow held further on: " + saved);
    }

    @Test
    void cullingEverywhereLeaksLightIntoCaves() {
        // Beta 1: the culls ran wherever Vitrail drew. Under a low sun the cave ceilings' undersides are culled and
        // their sunlit tops lie past Vitrail's box.
        var random = new Random(15);
        int leaks = 0;
        for (int trial = 0; trial < 10; trial++) {
            World world = world(random);
            double up = Math.toRadians(10 + random.nextDouble() * 10);
            world.light.set((float) Math.cos(up), (float) Math.sin(up), 0.1f).normalize();
            world.camY = 30;
            world.depth = 1000;
            world.draw(64, 8, everywhere());
            leaks += world.mismatches(random, 3000);
        }
        assertTrue(leaks > 1000, "light leaks the trace finds: " + leaks);
    }
}
