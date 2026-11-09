package dev.vultrum.compat;

import com.mojang.math.Transformation;
import dev.vultrum.VultrumConfig;
import dev.vultrum.cull.ShadowPass;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Applies a shader pack's entity shadow distance to item and block displays that have no culling box. Vitrail tests
 * every other entity's box against a cube of that distance about the camera; a display without a box skips the test,
 * so it reaches the shadow pass from anywhere in the render distance, only for the pack to discard it. A display is
 * left out only when no part of its model can come within that cube, grown by the pack's fade.
 */
public final class DisplayShadowReach {
    /** How far past its entity shadow distance a pack fades entity shadows, in blocks. */
    private static final float FADE = 2.0F;
    /** Block model elements span -1 to 2 blocks on each axis from the display's origin. */
    static final float BLOCK_RADIUS = 3.47F;
    /** An item model is centred on the origin, so its elements span -1.5 to 1.5 blocks. */
    static final float ITEM_RADIUS = 2.6F;
    /** The item's own display transform can add up to 5 blocks per axis and scale it up to 4 times. */
    static final float TRANSFORMED_ITEM_RADIUS = 19.1F;

    private DisplayShadowReach() {}

    /** Whether Vitrail's shadow walk can skip this display, seen from {@code camera}. */
    public static boolean hidden(Display display, Vec3 camera, float partial) {
        if (!Live.ON || display.affectedByCulling() || ShadowPass.suspended()) return false;
        float radius = modelRadius(display);
        var state = display.renderState();
        if (radius < 0.0F || state == null) return false;
        float reach = Live.read(Live.ENTITY);
        if (!bounded(reach, Live.read(Live.WORLD))) return false;
        var at = display.getPosition(partial).subtract(camera);
        return beyond(at.x, at.y, at.z, reach, extent(state.transformation().get(display.calculateInterpolationProgress(partial)), radius));
    }

    private static float modelRadius(Display display) {
        if (display instanceof Display.BlockDisplay) return BLOCK_RADIUS;
        if (display instanceof Display.ItemDisplay item && item.itemRenderState() != null) return itemRadius(item.itemRenderState().itemTransform());
        return -1.0F;
    }

    static float itemRadius(ItemDisplayContext context) {
        return context == ItemDisplayContext.NONE ? ITEM_RADIUS : TRANSFORMED_ITEM_RADIUS;
    }

    /** How far from the display's position its model can reach, in blocks. Rotation changes neither term. */
    static float extent(Transformation transformation, float radius) {
        var scale = transformation.scale();
        return transformation.translation().length() + Math.max(Math.abs(scale.x()), Math.max(Math.abs(scale.y()), Math.abs(scale.z()))) * radius;
    }

    /** Only a pack that asks for a shorter distance for entities than for the world has said where their shadows end. */
    static boolean bounded(float entityReach, float worldReach) {
        return entityReach >= 0.0F && (worldReach < 0.0F || entityReach < worldReach);
    }

    /** Past the cube on any one axis is out, as in Vitrail's own reach test. */
    static boolean beyond(double dx, double dy, double dz, float reach, float extent) {
        return Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) > reach + FADE + extent;
    }

    /** Touches the game's settings and Vitrail only once a display is actually asked about. */
    private static final class Live {
        static final boolean ON = VultrumConfig.get().displayShadowReach();
        static final MethodHandle ENTITY = distance("entityShadowDistance"), WORLD = distance("shadowRenderDistance");

        private static MethodHandle distance(String name) {
            try {
                return MethodHandles.publicLookup().findStatic(Class.forName("dev.vitrail.render.TerrainDraw"), name, MethodType.methodType(float.class));
            } catch (ReflectiveOperationException e) {
                return MethodHandles.constant(float.class, -1.0F);
            }
        }

        static float read(MethodHandle distance) {
            try {
                return (float) distance.invokeExact();
            } catch (Throwable e) {
                return -1.0F;
            }
        }
    }
}
