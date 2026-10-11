package dev.vultrum.cull;

import dev.vultrum.VultrumClient;
import org.joml.Vector3fc;

import java.util.Objects;

/**
 * Vultrum's culling inside Vitrail's shadow pass: {@link ShadowFaceCull} and {@link SunlessShadow}. Both pause
 * while Vitrail has no shadow light, and for packs that voxelise their shadow pass: those read every block in
 * range, not only the surface the light sees, and Vitrail's own shadow culling backs off for them too.
 */
public final class ShadowPass {
    /** Why culling is paused for the current pack, or null. Only for logging changes. */
    private static String pauseReason;
    /** Culling switched off at runtime: the bench's visual check. */
    private static boolean suspended;

    private ShadowPass() {}

    /** {@code hooked}: Sodium and Vitrail are installed in the versions Vultrum's hooks support. */
    public static void configure(boolean faceCull, boolean sunless, boolean hooked) {
        pauseReason = null;
        ShadowRegion.configure((faceCull || sunless) && hooked);
        ShadowFaceCull.configure(faceCull, hooked);
        SunlessShadow.configure(sunless, hooked);
    }

    /** Draws every shadow face from the next plan on, or culls again. For the bench's visual check. */
    public static void suspend(boolean suspend) {
        suspended = suspend;
    }

    /** How a shadow-pass cull's setting is logged. */
    static String status(boolean on, boolean hooked) {
        return !on ? "off" : hooked ? "on" : "off: needs Vitrail and Sodium in supported versions";
    }

    /**
     * Vitrail planned its shadow culling. {@code light} is the world-space direction toward the shadow
     * light, or null when no pack draws terrain. {@code voxelised} says whether the pack voxelises its
     * shadow pass, or is null when Vultrum couldn't read it. {@code bound} is the half side of the box
     * around the camera Vitrail draws shadows in, negative for none, NaN when Vultrum couldn't read it.
     */
    public static void plan(Vector3fc light, Boolean voxelised, float bound) {
        if (!ShadowFaceCull.enabled() && !SunlessShadow.enabled()) return;
        boolean cull = false;
        if (light != null && !suspended) {
            String pause = voxelised == null ? "Vultrum can't read whether the shader pack voxelises its shadow pass"
                    : voxelised ? "the shader pack voxelises its shadow pass" : null;
            if (!Objects.equals(pause, pauseReason)) {
                pauseReason = pause;
                if (pause == null) VultrumClient.LOGGER.info("Shadow-pass culling resumed");
                else VultrumClient.LOGGER.info("Shadow-pass culling paused: {}", pause);
            }
            cull = pause == null;
        }
        ShadowRegion.plan(cull ? light : null, bound);
        ShadowFaceCull.light(cull ? light : null);
        SunlessShadow.plan(cull);
    }

    /** Vitrail starts drawing shadow-pass terrain, around the camera at (x, y, z). */
    public static void begin(double x, double y, double z) {
        if (!ShadowFaceCull.enabled() && !SunlessShadow.enabled()) return;
        ShadowRegion.begin(x, y, z);
        ShadowFaceCull.begin();
        SunlessShadow.begin();
    }

    public static void end() {
        SunlessShadow.end();
        ShadowFaceCull.end();
        ShadowRegion.end();
    }
}
