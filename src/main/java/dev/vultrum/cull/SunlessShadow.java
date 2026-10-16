package dev.vultrum.cull;

import dev.vultrum.VultrumClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;

import java.lang.ref.WeakReference;

/**
 * Skips sunless sections ({@link SunlessFlags}) in Vitrail's shadow pass. Vitrail collects shadow-pass
 * sections by frustum alone (Sodium's {@code FallbackVisibleChunkCollector}), so every loaded cave section
 * in the light's view is drawn into the shadow map. A sunless section touches no air the sun reaches, short
 * of a transparent path longer than 15 blocks (sky light fades by one per block sideways), and packs scale
 * sunlight by the sky lightmap, so its faces get no sun anyway. Shadows on lit terrain stay the same, except
 * where the sun shines along such a path. That relies on the terrain above the section being drawn into the shadow
 * map, so a section is skipped only where {@link ShadowRegion} says its rays clear the terrain inside what Vitrail
 * draws: otherwise a deep cave's light shafts would see no rock between it and a low sun.
 *
 * <p>Off unless {@code sunless-shadow=true} in {@code config/vultrum.properties}. Pauses with the rest of
 * Vultrum's shadow-pass culling ({@link ShadowPass}). A flag is dropped when Sodium is told its section or
 * a neighbour changed: block edits and light updates both reach {@code scheduleRebuildForChunk}, after the
 * new light data is readable. A chunk unload drops light data without telling Sodium, so the flags next to it
 * stay. Sodium alone draws no section of a chunk unless the chunk and its eight neighbours have light data.
 * With the unload budget ({@link dev.vultrum.upload.ColumnLifecycle}) a column is still drawn while it waits to be
 * removed: a flag kept from before matches the mesh it still has, and a flag worked out then reads the missing
 * light as not dark ({@link SunlessFlags#dark}), so its section isn't skipped. The unloaded chunk's light coming
 * back marks the 3×3 columns dirty. Everything runs on the render thread.
 */
public final class SunlessShadow {
    /** About 100k sections fill a 32-chunk render distance. */
    private static final SunlessFlags flags = new SunlessFlags(1 << 18);
    /** Shadow passes between debug reports of how many sections the skip drops. */
    private static final int LOG_PASSES = 2048;

    private static boolean enabled;
    /** The next shadow passes may skip sections: enabled and not paused. */
    private static boolean planned;
    /** Inside a shadow pass that skips sections. */
    private static boolean active;
    /** The level the flags describe. */
    private static WeakReference<Object> level = new WeakReference<>(null);
    private static SunlessFlags.SkyLight sky;
    private static int passes;
    private static long seen, skipped;

    private SunlessShadow() {}

    static void configure(boolean on, boolean hooked) {
        enabled = on && hooked;
        planned = false;
        flags.clear();
        VultrumClient.LOGGER.info("Sunless shadow skip {}", ShadowPass.status(on, hooked));
    }

    static boolean enabled() {
        return enabled;
    }

    static void plan(boolean cull) {
        planned = enabled && cull;
    }

    static boolean planned() {
        return planned;
    }

    static void begin() {
        if (!planned) return;
        ClientLevel current = Minecraft.getInstance().level;
        if (current == null) return;
        var listener = current.getLightEngine().getLayerListener(LightLayer.SKY);
        begin(current, (x, y, z) -> listener.getDataLayerData(SectionPos.of(x, y, z)));
    }

    /** A planned shadow pass starts, lit by {@code light} from {@code world}. Flags of another world are dropped. */
    static void begin(Object world, SunlessFlags.SkyLight light) {
        if (world != level.get()) {
            flags.clear();
            level = new WeakReference<>(world);
        }
        sky = light;
        active = true;
    }

    static void end() {
        if (active && ++passes % LOG_PASSES == 0) {
            VultrumClient.LOGGER.debug("Sunless shadow skip: skipped {} of {} shadow-pass sections", skipped, seen);
            seen = skipped = 0;
        }
        active = false;
        sky = null;
    }

    /** Whether the shadow pass skips section (x, y, z). */
    public static boolean skip(int x, int y, int z) {
        if (!active) return false;
        seen++;
        if (!flags.sunless(sky, x, y, z) || !ShadowRegion.covers(x, y, z)) return false;
        skipped++;
        return true;
    }

    /** Sodium was told section (x, y, z) changed: its blocks, or light in or next to it. */
    public static void sectionChanged(int x, int y, int z) {
        if (enabled) flags.changed(x, y, z);
    }
}
