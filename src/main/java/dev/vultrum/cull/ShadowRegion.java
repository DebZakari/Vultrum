package dev.vultrum.cull;

import dev.vultrum.VultrumClient;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.joml.Matrix4fc;
import org.joml.Vector3fc;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Which sections the shadow-pass culls may touch: those whose rays toward the light stay inside what Vitrail draws
 * into the shadow map until they have risen above all terrain.
 *
 * <p>{@link ShadowFaceCull} drops faces pointing away from the light, and {@link SunlessShadow} drops sections the
 * sun can't reach. Both rely on the terrain between such a face and the light being drawn: for a solid, the side
 * that faces the light. Vitrail draws a bounded region, and a ray that leaves it first finds nothing: in a deep cave
 * at a low sun, the rock's sunlit side lies hundreds of blocks away, and without the cave ceiling's underside the
 * cave fills with light (sun shafts in the deep dark). The region is bounded by:
 * <ul>
 *   <li>Vitrail's walk: a box of ±{@code bound} blocks around the camera on x, y and z when the pack's shadow
 *       distance bounds it ({@code ShadowCull}), inside the shape it chose. It keeps every section that reaches into
 *       the box (Sodium pads each by 1.125 blocks), around the camera this pass is given. Its swept shape holds every
 *       ray from a point the camera sees toward the light, so only the box limits those rays.</li>
 *   <li>Sodium's walk: the render distance, horizontally and vertically, and only built sections of ready
 *       columns. Nothing is known of a column the server hasn't sent or one not yet ready. Sodium builds a section
 *       once its walk from the camera reaches it, so some are never built: rock under the ground, a cave the camera
 *       can't see into.</li>
 *   <li>The shadow projection's depth window: what lies beyond its near plane toward the light is clipped.</li>
 * </ul>
 * The x and y extent of the shadow map doesn't matter: a ray toward the light keeps its place in the map.
 *
 * <p>The test, per section: a height {@code h} at which every ray from the section toward the light is in open air.
 * A column's top is the top of its highest non-empty section: above it the column is air, in full sunlight.
 * {@code h} starts at the section's top and rises to the highest column top under where the rays are at {@code h},
 * until none is higher. A ray that reaches {@code h} inside the region has left every solid it entered through a
 * face that points at the light, and those faces are drawn. The rays from a section, up to {@code h}, lie in the box
 * spanned by the section and its shift along the light, and that box must lie in the region: inside Vitrail's box,
 * and two sections inside the render distance. Its columns must be ready, and none may hold an unbuilt section above the section's bottom: the
 * rays only rise. Terrain the rays pass above {@code h} doesn't matter. With no light from above, or a bound Vultrum
 * couldn't read, no section qualifies.
 *
 * <p>Packs that scale depth down in their shadow vertex shader (Complementary) only widen the window, so reading
 * it from the matrix is conservative. A pack that widened its x and y extent only changes the map's footprint.
 * Everything runs on the render thread.
 */
public final class ShadowRegion {
    /** New columns are checked for unbuilt sections at most this many a frame, nearest first; the rest stay unknown. */
    private static final int NEW_CHECKS = 64;
    /** Columns known to hold unbuilt sections are checked again every this many frames, a share each frame. */
    private static final int RECHECK_FRAMES = 8;

    /** {@link #unbuilt}'s answer for a column not in Sodium's render graph: nothing of it is drawn. */
    private static final int NOT_DRAWN = Integer.MIN_VALUE + 1;
    /**
     * The columns in Sodium's render graph: per column, the top of its highest unbuilt non-empty section when last
     * checked, or {@link ColumnTops#EMPTY} once all are built; {@link ColumnTops#UNKNOWN} for a column not checked yet.
     * A section stays built until its column is removed, and an empty one is built as it's added, so this only falls
     * between checks. Except for a section emptied before its check, which may hold blocks again unbuilt: a change
     * above the recorded top makes the column unknown again ({@link #sectionChanged}).
     */
    private static final Long2IntOpenHashMap unbuilt = new Long2IntOpenHashMap();

    static {
        unbuilt.defaultReturnValue(NOT_DRAWN);
    }

    /** Why {@link Bounds#check} leaves a section out, for the bench: its index there, 1 up. */
    private static final String[] MISSES = {"terrain", "box", "reach", "depth", "unbuilt"};

    private static boolean enabled;
    /** From Vitrail's plan for the coming shadow passes: the direction toward the light, or null, and its box. */
    private static float lightX, lightY, lightZ;
    private static boolean lit;
    private static float bound = Float.NaN;
    /** Vitrail's drawn shadow matrix, read just before its plan. */
    private static final float[] matrix = new float[16];
    private static boolean matrixRead, matrixPlanned;
    /** Whether the bounds below are for the current plan. */
    private static boolean measured;
    private static Bounds bounds = Bounds.NONE;
    /** The column tops and unbuilt tops the bounds read, refilled by each measure. */
    private static final ColumnTops tops = new ColumnTops(), unbuiltTops = new ColumnTops();
    /** The measure's grid of columns as indexes, nearest the centre first, for a grid of {@code orderSize}. */
    private static int[] order = new int[0];
    private static int orderSize, measures;
    private static final String UNMEASURED = "not measured yet";
    /** Why the region covers nothing, or null when it's bounded, for logging changes only. */
    private static String emptyReason = UNMEASURED;
    private static long seen, covered;
    private static int passes;
    /** The bench's counts, kept apart from the log's, and the time its measures took. */
    private static long benchSeen, benchCovered, benchMeasures, benchMeasureNs;
    private static final long[] benchMissed = new long[MISSES.length];

    private ShadowRegion() {}

    static void configure(boolean on) {
        enabled = on;
        unbuilt.clear();
        lit = false;
        matrixRead = matrixPlanned = measured = false;
        bounds = Bounds.NONE;
        emptyReason = UNMEASURED;
    }

    /** Vitrail's drawn shadow matrix (legacy depth, camera-relative), or null. Read before each plan. */
    public static void shadowMatrix(Matrix4fc drawn) {
        if (!enabled) return;
        matrixRead = drawn != null;
        if (drawn != null) drawn.get(matrix);
    }

    /** Vitrail planned the coming shadow passes. {@code light} as in {@link ShadowPass#plan}; {@code bound} is NaN when unread. */
    static void plan(Vector3fc light, float bound) {
        lit = light != null;
        if (lit) {
            lightX = light.x();
            lightY = light.y();
            lightZ = light.z();
        }
        ShadowRegion.bound = bound;
        matrixPlanned = matrixRead;
        matrixRead = false;
        measured = false;
    }

    /** Sodium added a column to its render graph; its sections are built later. */
    public static void columnAdded(int x, int z) {
        if (enabled) unbuilt.put(ChunkPos.pack(x, z), ColumnTops.UNKNOWN);
    }

    public static void columnRemoved(int x, int z) {
        if (enabled) unbuilt.remove(ChunkPos.pack(x, z));
    }

    /** Sodium was told section (x, y, z) changed: its blocks, or light in or next to it. */
    public static void sectionChanged(int x, int y, int z) {
        if (!enabled) return;
        long pos = ChunkPos.pack(x, z);
        int top = unbuilt.get(pos);
        if (top != NOT_DRAWN && (y + 1) * 16 > top) unbuilt.put(pos, ColumnTops.UNKNOWN);
    }

    /** Sodium made a new section manager: its columns are added again. */
    public static void managerCreated() {
        unbuilt.clear();
    }

    /** A shadow pass starts with the camera at (x, y, z). Works the region out once a plan. */
    static void begin(double x, double y, double z) {
        if (measured) return;
        measured = true;
        bounds = Bounds.NONE;
        long start = System.nanoTime();
        String why = measure(x, y, z);
        benchMeasureNs += System.nanoTime() - start;
        benchMeasures++;
        if (!Objects.equals(why, emptyReason)) {
            emptyReason = why;
            if (why == null) VultrumClient.LOGGER.info("Shadow-pass culling bounded by Vitrail's shadow region: box ±{}, depth window {}",
                    bound < 0 ? "none" : bound, window());
            else VultrumClient.LOGGER.info("Shadow-pass culling covers nothing: {}", why);
        }
    }

    static void end() {
        if (enabled && ++passes % 4096 == 0) {
            VultrumClient.LOGGER.debug("Shadow region: {} of {} shadow-pass sections inside", covered, seen);
            seen = covered = 0;
        }
    }

    /** Whether section (x, y, z)'s rays toward the light stay inside what's drawn until they clear the terrain. */
    static boolean covers(int x, int y, int z) {
        seen++;
        benchSeen++;
        int miss = bounds.check(x, y, z);
        if (miss > 0) {
            benchMissed[miss - 1]++;
            return false;
        }
        covered++;
        benchCovered++;
        return true;
    }

    /** How far the planned matrix's depth window reaches along the light, in blocks from the camera. */
    private static String window() {
        float toward = matrix[2] * lightX + matrix[6] * lightY + matrix[10] * lightZ;
        if (toward == 0) return "unbounded along the light";
        float a = (-1 - matrix[14]) / toward, b = (1 - matrix[14]) / toward;
        return String.format(Locale.ROOT, "%.1f to %.1f blocks along the light", Math.min(a, b), Math.max(a, b));
    }

    /** Benchmark only: forgets the counts {@link #stats} reports. */
    public static void resetStats() {
        benchSeen = benchCovered = benchMeasures = benchMeasureNs = 0;
        Arrays.fill(benchMissed, 0);
    }

    /**
     * Benchmark only: shadow-pass sections asked about since {@link #resetStats}, how many were inside, why the others
     * weren't, the measures and their time in all, and why none.
     */
    public static Map<String, Object> stats() {
        var stats = new LinkedHashMap<String, Object>();
        stats.put("enabled", enabled);
        stats.put("sections", benchSeen);
        stats.put("covered", benchCovered);
        var missed = new LinkedHashMap<String, Long>();
        for (int i = 0; i < MISSES.length; i++) missed.put(MISSES[i], benchMissed[i]);
        stats.put("missed", missed);
        stats.put("measures", benchMeasures);
        stats.put("measureMs", benchMeasureNs / 1e6);
        stats.put("bound", Float.isNaN(bound) ? null : bound);
        stats.put("emptyReason", emptyReason);
        return stats;
    }

    /** Sets the bounds directly. Tests only. */
    static void use(Bounds region) {
        bounds = region;
        measured = true;
    }

    /** Works out {@link #bounds}, which stay {@link Bounds#NONE} otherwise, or returns why the region covers nothing. */
    private static String measure(double camX, double camY, double camZ) {
        if (!lit) return "no light";
        if (Float.isNaN(bound)) return "Vultrum can't read Vitrail's shadow distance";
        if (!matrixPlanned) return "Vultrum can't read Vitrail's shadow matrix";
        if (!Bounds.orthographic(matrix)) return "the shadow projection isn't orthographic";
        if (!(lightY > Bounds.MIN_LIGHT_Y)) return "the light is at the horizon";
        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client.level;
        SodiumWorldRenderer renderer = SodiumWorldRenderer.instanceNullable();
        if (level == null || renderer == null) return "no level";

        float reach = client.options.getEffectiveRenderDistance() * 16f - 2 * Bounds.SECTION;
        float cube = bound < 0 ? Float.POSITIVE_INFINITY : bound;
        float span = Math.min(cube, reach);
        if (!(span > 0)) return "the region is smaller than a section";
        int cx = (int) Math.floor(camX) >> 4, cz = (int) Math.floor(camZ) >> 4;
        int n = (int) Math.ceil(span / 16f) + 1, size = 2 * n + 1;
        // Columns not drawn stay unknown in both: those Sodium hasn't added, and those the level has let go.
        tops.reset(cx - n, cz - n, size);
        unbuiltTops.reset(cx - n, cz - n, size);
        int[] nearest = order(size);
        int checks = NEW_CHECKS, share = measures++ % RECHECK_FRAMES;
        for (int i = 0; i < nearest.length; i++) {
            int x = cx - n + nearest[i] % size, z = cz - n + nearest[i] / size;
            long pos = ChunkPos.pack(x, z);
            int unbuiltTop = unbuilt.get(pos);
            if (unbuiltTop == NOT_DRAWN) continue;
            LevelChunk chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
            if (chunk == null) continue;
            int index = chunk.getHighestFilledSectionIndex();
            tops.set(x, z, index < 0 ? ColumnTops.EMPTY : (level.getSectionYFromSectionIndex(index) + 1) * 16);
            if (unbuiltTop == ColumnTops.UNKNOWN ? checks-- > 0 : unbuiltTop != ColumnTops.EMPTY && i % RECHECK_FRAMES == share) {
                unbuiltTop = unbuiltTop(renderer, level, chunk, x, z, unbuiltTop);
                unbuilt.put(pos, unbuiltTop);
            }
            unbuiltTops.set(x, z, unbuiltTop);
        }
        // Where the rays are at h, they span the light's shift over a section's height, a column either side and one
        // for rounding.
        tops.build((int) Math.ceil(Math.max(Math.abs(lightX), Math.abs(lightZ)) / lightY) + 3);
        unbuiltTops.build(size);
        bounds = Bounds.of(lightX, lightY, lightZ, camX, camY, camZ, cube, reach, reach, tops, unbuiltTops, matrix);
        return bounds == Bounds.NONE ? "the shadow matrix isn't finite" : null;
    }

    /** A grid of {@code size} by {@code size} columns as indexes (x + z * size), nearest its centre first. */
    private static int[] order(int size) {
        if (size == orderSize) return order;
        long[] keyed = new long[size * size];
        for (int i = 0; i < keyed.length; i++) {
            long dx = i % size - size / 2, dz = i / size - size / 2;
            keyed[i] = (dx * dx + dz * dz) << 32 | i;
        }
        Arrays.sort(keyed);
        order = new int[keyed.length];
        for (int i = 0; i < keyed.length; i++) order[i] = (int) keyed[i];
        orderSize = size;
        return order;
    }

    /**
     * The top of a column's highest unbuilt non-empty section, or {@link ColumnTops#EMPTY} when Sodium has built
     * them all. {@code from}: the last answer, or {@link ColumnTops#UNKNOWN}; the sections above it were built then.
     */
    private static int unbuiltTop(SodiumWorldRenderer renderer, ClientLevel level, LevelChunk chunk, int x, int z, int from) {
        LevelChunkSection[] sections = chunk.getSections();
        int start = from == ColumnTops.UNKNOWN ? sections.length - 1 : level.getSectionIndexFromSectionY((from >> 4) - 1);
        for (int i = Math.min(start, sections.length - 1); i >= 0; i--) {
            int sectionY = level.getSectionYFromSectionIndex(i);
            if (!sections[i].hasOnlyAir() && !renderer.isSectionReady(x, sectionY, z)) return (sectionY + 1) * 16;
        }
        return ColumnTops.EMPTY;
    }

    /**
     * The region for one frame's shadow passes, camera-relative. Pure arithmetic, so it can be tested off the game.
     */
    static final class Bounds {
        static final int SECTION = 16;
        /** Below this the light grazes the horizon and no section's rays reach the top inside a bound. */
        static final float MIN_LIGHT_Y = 1e-3f;
        static final Bounds NONE = new Bounds();

        private final boolean any;
        private final double camX, camY, camZ;
        /** Horizontal shift along the light per block of rise. */
        private final float kx, kz;
        private final float cube, radius, vertical;
        private final ColumnTops tops, unbuilt;
        /** The matrix's depth row, and the depth a section's size spans. */
        private final float za, zb, zc, zd, zMargin;

        private Bounds() {
            any = false;
            camX = camY = camZ = 0;
            kx = kz = cube = radius = vertical = za = zb = zc = zd = zMargin = 0;
            tops = unbuilt = null;
        }

        private Bounds(float kx, float kz, double camX, double camY, double camZ, float cube, float radius,
                       float vertical, ColumnTops tops, ColumnTops unbuilt, float za, float zb, float zc, float zd) {
            this.any = true;
            this.kx = kx;
            this.kz = kz;
            this.camX = camX;
            this.camY = camY;
            this.camZ = camZ;
            this.cube = cube;
            this.radius = radius;
            this.vertical = vertical;
            this.tops = tops;
            this.unbuilt = unbuilt;
            this.za = za;
            this.zb = zb;
            this.zc = zc;
            this.zd = zd;
            this.zMargin = SECTION * (float) Math.sqrt(za * za + zb * zb + zc * zc);
        }

        /**
         * {@code light}: toward the light. {@code cube}: the half side of Vitrail's box, or infinite. Every point
         * inside it lies in a drawn section. {@code radius}: how far from the camera, horizontally, Sodium draws. {@code vertical}:
         * how far Sodium's walk reaches up and down. {@code tops}: the column tops; {@code unbuilt}: the tops of their
         * highest unbuilt sections. Both built, unknown where a column isn't drawn; the bounds read them while they're
         * in use. {@code matrix}: the drawn shadow matrix, column-major, legacy depth.
         */
        static Bounds of(float lightX, float lightY, float lightZ, double camX, double camY, double camZ, float cube,
                         float radius, float vertical, ColumnTops tops, ColumnTops unbuilt, float[] matrix) {
            if (!(lightY > MIN_LIGHT_Y) || !Float.isFinite(lightX) || !Float.isFinite(lightZ)) return NONE;
            if (!(radius > 0) || !(vertical > 0) || !(cube > 0) || tops == null || unbuilt == null || !orthographic(matrix)) {
                return NONE;
            }
            for (int i = 0; i < 16; i++) if (!Float.isFinite(matrix[i])) return NONE;
            return new Bounds(lightX / lightY, lightZ / lightY, camX, camY, camZ, cube, radius, vertical, tops, unbuilt,
                    matrix[2], matrix[6], matrix[10], matrix[14]);
        }

        /** An orthographic matrix's last row is (0, 0, 0, 1): depth is affine, and its extremes over a box are corners. */
        static boolean orthographic(float[] m) {
            return Math.abs(m[3]) < 1e-6f && Math.abs(m[7]) < 1e-6f && Math.abs(m[11]) < 1e-6f && Math.abs(m[15] - 1) < 1e-6f;
        }

        boolean covers(int sx, int sy, int sz) {
            return check(sx, sy, sz) == 0;
        }

        /**
         * 0 when the region holds section (sx, sy, sz); otherwise why not: 1 the terrain over it is unknown or too high,
         * 2 Vitrail's box, 3 Sodium's reach, 4 the depth window, 5 an unbuilt section under its rays.
         */
        int check(int sx, int sy, int sz) {
            if (!any) return 1;
            int bottom = sy * SECTION, top = bottom + SECTION;
            // The rays from the section rise to h (world y), shifting by (kx, kz) per block. Where they are at h, the
            // section's top shifted by up to (h - top) and its bottom by up to (h - bottom), every column's top must be
            // at or below h. Raise h to the highest one until none is higher: column tops are section edges, so a few
            // steps at most.
            int h = top;
            while (true) {
                float nearX = kx * (h - top), farX = kx * (h - bottom), nearZ = kz * (h - top), farZ = kz * (h - bottom);
                int highest = tops.max(sx + (int) Math.floor(Math.min(nearX, farX) / SECTION),
                        sx + (int) Math.ceil(Math.max(nearX, farX) / SECTION),
                        sz + (int) Math.floor(Math.min(nearZ, farZ) / SECTION),
                        sz + (int) Math.ceil(Math.max(nearZ, farZ) / SECTION));
                if (highest <= h) break;
                if (highest == ColumnTops.UNKNOWN || highest - camY > Math.min(cube, vertical)) return 1;
                h = highest;
            }
            float x0 = (float) (sx * 16.0 - camX), y0 = (float) (bottom - camY), z0 = (float) (sz * 16.0 - camZ);
            float x1 = x0 + SECTION, y1 = (float) (h - camY), z1 = z0 + SECTION;
            // The rays up to h fill the box from the section to its shift at h.
            float rise = h - bottom;
            float ox = kx * rise, oz = kz * rise;
            float xMin = x0 + Math.min(0, ox), xMax = x1 + Math.max(0, ox);
            float zMin = z0 + Math.min(0, oz), zMax = z1 + Math.max(0, oz);
            if (xMin < -cube || xMax > cube || zMin < -cube || zMax > cube || y0 < -cube || y1 > cube) return 2;
            if (y0 < -vertical || y1 > vertical) return 3;
            float ax = Math.max(-xMin, xMax), az = Math.max(-zMin, zMax);
            if (ax * ax + az * az > radius * radius) return 3;
            float lo = zd + Math.min(za * xMin, za * xMax) + Math.min(zb * y0, zb * y1) + Math.min(zc * zMin, zc * zMax);
            float hi = zd + Math.max(za * xMin, za * xMax) + Math.max(zb * y0, zb * y1) + Math.max(zc * zMin, zc * zMax);
            if (!(lo >= -1 + zMargin && hi <= 1 - zMargin)) return 4;
            // Every section the rays cross is drawn: in the box's columns, every unbuilt one lies below the rays.
            return unbuilt.max(sx + (int) Math.floor(Math.min(0, ox) / SECTION), sx + (int) Math.ceil(Math.max(0, ox) / SECTION),
                    sz + (int) Math.floor(Math.min(0, oz) / SECTION), sz + (int) Math.ceil(Math.max(0, oz) / SECTION)) <= bottom ? 0 : 5;
        }
    }

    /**
     * A section's top per column around the camera, in world y: the column's highest non-empty section, or its highest
     * unbuilt one. A sparse table gives the highest over a rectangle of columns in four reads. Reused from one measure
     * to the next.
     */
    static final class ColumnTops {
        /** No such section in the column. */
        static final int EMPTY = Integer.MIN_VALUE;
        /** A column not drawn, not looked at, or outside the grid: nothing is known about it. */
        static final int UNKNOWN = Integer.MAX_VALUE;

        private int x0, z0, size, levels, built;
        /** Level (lx, lz) holds, per column, the highest top over the 2^lx by 2^lz columns from it. */
        private int[] table = new int[0];

        /** Starts over with columns x0 to x0 + size - 1 by z0 to z0 + size - 1, all unknown. */
        void reset(int x0, int z0, int size) {
            this.x0 = x0;
            this.z0 = z0;
            this.size = size;
            levels = 32 - Integer.numberOfLeadingZeros(size);
            int cells = levels * levels * size * size;
            if (table.length < cells) table = new int[cells];
            Arrays.fill(table, 0, size * size, UNKNOWN);
        }

        void set(int x, int z, int top) {
            table[(z - z0) * size + x - x0] = top;
        }

        /** Fills the levels after the last {@link #set}. */
        void build() {
            build(size);
        }

        /** Fills the levels for rectangles up to {@code widest} columns a side; wider ones read as unknown. */
        void build(int widest) {
            built = Math.min(levels, 32 - Integer.numberOfLeadingZeros(Math.max(1, widest)));
            int area = size * size;
            for (int lx = 0; lx < built; lx++) {
                for (int lz = lx == 0 ? 1 : 0; lz < built; lz++) {
                    // From the level half as deep on z, or, on the first row, half as wide on x.
                    int to = (lx * levels + lz) * area;
                    int from = lz > 0 ? to - area : (lx - 1) * levels * area;
                    int step = lz > 0 ? (1 << (lz - 1)) * size : 1 << (lx - 1);
                    int width = size - (1 << lx) + 1, depth = size - (1 << lz) + 1;
                    for (int j = 0; j < depth; j++) {
                        for (int i = 0, a = from + j * size; i < width; i++, a++) {
                            table[to + j * size + i] = Math.max(table[a], table[a + step]);
                        }
                    }
                }
            }
        }

        /** The highest top over columns xa to xb by za to zb, or {@link #UNKNOWN} past the grid. */
        int max(int xa, int xb, int za, int zb) {
            int i0 = xa - x0, i1 = xb - x0, j0 = za - z0, j1 = zb - z0;
            if (i0 < 0 || j0 < 0 || i1 >= size || j1 >= size || i0 > i1 || j0 > j1) return UNKNOWN;
            int lx = 31 - Integer.numberOfLeadingZeros(i1 - i0 + 1), lz = 31 - Integer.numberOfLeadingZeros(j1 - j0 + 1);
            if (lx >= built || lz >= built) return UNKNOWN;
            int level = (lx * levels + lz) * size * size;
            int i2 = i1 - (1 << lx) + 1, j2 = j1 - (1 << lz) + 1;
            return Math.max(Math.max(table[level + j0 * size + i0], table[level + j0 * size + i2]),
                    Math.max(table[level + j2 * size + i0], table[level + j2 * size + i2]));
        }
    }
}
