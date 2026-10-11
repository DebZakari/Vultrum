package dev.vultrum.cull;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.DataLayer;

/**
 * Which sections are sunless: sky light 0 throughout the section and its six face neighbours. A section
 * without light data counts as lit. Flags are computed on first use and kept until {@link #changed} drops
 * them. Not thread-safe: the render thread owns it.
 */
final class SunlessFlags {
    /** A section's sky-light data, or null when there is none. */
    interface SkyLight {
        DataLayer layer(int x, int y, int z);
    }

    private static final byte SUNLESS = 1, LIT = 2, DARK = 1, BRIGHT = 2;
    /** A section and its six face neighbours: a flag depends on all seven layers, and a change to one drops all seven flags. */
    private static final int[][] SPAN = {{0, 0, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private final Long2ByteOpenHashMap flags = new Long2ByteOpenHashMap();
    /** Whether each section's own layer is dark, so a layer is scanned once rather than once per section next to it. */
    private final Long2ByteOpenHashMap layers = new Long2ByteOpenHashMap();
    /** Sections that leave the world keep their flags, so past this many everything is dropped. */
    private final int maxSections;

    SunlessFlags(int maxSections) {
        this.maxSections = maxSections;
    }

    boolean sunless(SkyLight sky, int x, int y, int z) {
        long key = SectionPos.asLong(x, y, z);
        byte flag = flags.get(key);
        if (flag == 0) {
            flag = SUNLESS;
            for (int[] d : SPAN) {
                if (!dark(sky, x + d[0], y + d[1], z + d[2])) {
                    flag = LIT;
                    break;
                }
            }
            if (flags.size() >= maxSections) flags.clear();
            flags.put(key, flag);
        }
        return flag == SUNLESS;
    }

    private boolean dark(SkyLight sky, int x, int y, int z) {
        long key = SectionPos.asLong(x, y, z);
        byte layer = layers.get(key);
        if (layer == 0) {
            layer = dark(sky.layer(x, y, z)) ? DARK : BRIGHT;
            if (layers.size() >= maxSections) layers.clear();
            layers.put(key, layer);
        }
        return layer == DARK;
    }

    /** Section (x, y, z) changed: its flag and its neighbours' depend on its light. */
    void changed(int x, int y, int z) {
        if (flags.isEmpty() && layers.isEmpty()) return;
        layers.remove(SectionPos.asLong(x, y, z));
        for (int[] d : SPAN) flags.remove(SectionPos.asLong(x + d[0], y + d[1], z + d[2]));
    }

    void clear() {
        flags.clear();
        layers.clear();
    }

    int size() {
        return flags.size();
    }

    /** Light 0 throughout, or false for null. Leaves a homogeneous layer unallocated, which {@link DataLayer#getData} wouldn't. */
    static boolean dark(DataLayer layer) {
        if (layer == null) return false;
        if (layer.isDefinitelyHomogenous()) return layer.isEmpty();
        for (byte b : layer.getData()) if (b != 0) return false;
        return true;
    }
}
