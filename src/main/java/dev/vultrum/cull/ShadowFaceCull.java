package dev.vultrum.cull;

import dev.vultrum.VultrumClient;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.joml.Vector3fc;

/**
 * Sun-facing face culling for Vitrail's shadow pass. Vitrail turns Sodium's block-face culling off
 * while drawing shadows, because Sodium culls against the player's camera. The shadow view is
 * orthographic, so whether an axis-aligned face points at the light depends only on its facing:
 * one mask serves every section. A ray toward the light leaves a closed solid through a face pointing
 * at the light, so where that face is drawn, the faces behind it add nothing to the shadow map and
 * dropping them saves their vertex work. It is drawn only inside the region Vitrail draws, so a
 * section's faces are dropped only where {@link ShadowRegion} says its rays clear the terrain inside it:
 * otherwise a deep cave's ceiling is the only thing between it and a low sun.
 * Only Sodium's solid pass is narrowed: Vitrail draws shadows double-sided, and through the holes of
 * cutout blocks (leaves) the far faces add shadow. Faces without an axis-aligned facing (plants,
 * rotated models) are always drawn. The one assumption left is closed solid geometry: an open, single-sided
 * solid quad facing away from the light (not in vanilla) would stop casting a shadow.
 *
 * <p>On unless {@code shadow-face-cull=false} in {@code config/vultrum.properties}. Pauses with the rest of
 * Vultrum's shadow-pass culling ({@link ShadowPass}).
 */
public final class ShadowFaceCull {
    /** Mask value for "draw every facing": outside the shadow pass, or no usable light. */
    static final int NO_CULL = -1;

    private static boolean enabled;
    private static int mask = NO_CULL;
    /** Facings to draw for the current light. */
    private static int lightMask = NO_CULL;
    /** Shadow passes between debug reports of how many sections the cull narrows. */
    private static final int LOG_PASSES = 2048;
    private static int passes;
    private static long seen, culled;

    private ShadowFaceCull() {}

    static void configure(boolean on, boolean hooked) {
        enabled = on && hooked;
        lightMask = NO_CULL;
        VultrumClient.LOGGER.info("Shadow face cull {}", ShadowPass.status(on, hooked));
    }

    static boolean enabled() {
        return enabled;
    }

    /** The world-space direction toward the shadow light for the next shadow passes, or null to cull nothing. */
    static void light(Vector3fc light) {
        if (!enabled) return;
        int next = light == null ? NO_CULL : towardLight(light.x(), light.y(), light.z());
        if (next != lightMask) {
            lightMask = next;
            VultrumClient.LOGGER.debug("Shadow face cull: light {}, facings {}", light, Integer.toBinaryString(next));
        }
    }

    static void begin() {
        mask = lightMask;
    }

    static void end() {
        if (mask != NO_CULL && ++passes % LOG_PASSES == 0) {
            VultrumClient.LOGGER.debug("Shadow face cull: culled {} of {} solid shadow-pass sections", culled, seen);
            seen = culled = 0;
        }
        mask = NO_CULL;
    }

    /** Sodium's facing mask for section (x, y, z) when face culling is off ({@code ModelQuadFacing.ALL}). */
    public static int filter(int all, TerrainRenderPass pass, int x, int y, int z) {
        if (mask == NO_CULL || pass.supportsFragmentDiscard() || pass.isTranslucent()) return all;
        seen++;
        if (!ShadowRegion.covers(x, y, z)) return all;
        culled++;
        return all & mask;
    }

    /**
     * Facings whose outward normal has a positive dot product with the direction toward the light, plus
     * unassigned. NO_CULL when the direction is zero or not finite.
     */
    static int towardLight(float x, float y, float z) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) return NO_CULL;
        int axes = axis(x, ModelQuadFacing.POS_X, ModelQuadFacing.NEG_X)
                | axis(y, ModelQuadFacing.POS_Y, ModelQuadFacing.NEG_Y)
                | axis(z, ModelQuadFacing.POS_Z, ModelQuadFacing.NEG_Z);
        return axes == 0 ? NO_CULL : axes | ModelQuadFacing.UNASSIGNED_MASK;
    }

    private static int axis(float component, ModelQuadFacing positive, ModelQuadFacing negative) {
        // A face parallel to the light (component 0) is edge-on in an orthographic view and covers no texels.
        if (component > 0) return 1 << positive.ordinal();
        if (component < 0) return 1 << negative.ordinal();
        return 0;
    }
}
