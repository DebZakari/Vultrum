package dev.vultrum.compat;

import com.mojang.math.Transformation;
import net.minecraft.world.item.ItemDisplayContext;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DisplayShadowReachTest {
    private static final float REACH = 24.0F;

    @Test
    void actsOnlyWhenThePackAsksForAShorterEntityDistanceThanForTheWorld() {
        assertTrue(DisplayShadowReach.bounded(REACH, 128.0F));
        assertTrue(DisplayShadowReach.bounded(REACH, -1.0F), "the world unbounded, entities not");
        assertTrue(DisplayShadowReach.bounded(0.0F, 128.0F), "a pack may ask for no entity shadows at all");
        assertFalse(DisplayShadowReach.bounded(128.0F, 128.0F), "no separate entity distance");
        assertFalse(DisplayShadowReach.bounded(256.0F, 128.0F));
        assertFalse(DisplayShadowReach.bounded(-1.0F, 128.0F), "entities unbounded");
        assertFalse(DisplayShadowReach.bounded(-1.0F, -1.0F));
    }

    @Test
    void aDisplayIsBeyondOnlyOnceItsWholeModelIsPastTheReachAndFade() {
        float limit = REACH + 2.0F + DisplayShadowReach.ITEM_RADIUS;
        assertFalse(DisplayShadowReach.beyond(limit - 0.01, 0, 0, REACH, DisplayShadowReach.ITEM_RADIUS));
        assertTrue(DisplayShadowReach.beyond(limit + 0.01, 0, 0, REACH, DisplayShadowReach.ITEM_RADIUS));
        assertFalse(DisplayShadowReach.beyond(0, 0, -limit + 0.01, REACH, DisplayShadowReach.ITEM_RADIUS));
        assertTrue(DisplayShadowReach.beyond(0, 0, -limit - 0.01, REACH, DisplayShadowReach.ITEM_RADIUS));
    }

    @Test
    void theLimitIsACubeLikeVitrailsOwnReach() {
        float limit = REACH + 2.0F + DisplayShadowReach.ITEM_RADIUS;
        assertFalse(DisplayShadowReach.beyond(limit - 0.01, 0, limit - 0.01, REACH, DisplayShadowReach.ITEM_RADIUS), "a corner of the cube stays in");
        assertTrue(DisplayShadowReach.beyond(limit + 0.01, 0, limit - 0.01, REACH, DisplayShadowReach.ITEM_RADIUS));
        assertTrue(DisplayShadowReach.beyond(0, limit + 0.01, 0, REACH, DisplayShadowReach.ITEM_RADIUS));
        assertTrue(DisplayShadowReach.beyond(0, -limit - 0.01, 0, REACH, DisplayShadowReach.ITEM_RADIUS));
        assertFalse(DisplayShadowReach.beyond(limit - 0.01, limit - 0.01, limit - 0.01, REACH, DisplayShadowReach.ITEM_RADIUS), "axes are not added together");
    }

    @Test
    void aLargerExtentKeepsADisplayThatADefaultOneWouldLose() {
        assertTrue(DisplayShadowReach.beyond(40, 0, 0, REACH, DisplayShadowReach.ITEM_RADIUS));
        assertFalse(DisplayShadowReach.beyond(40, 0, 0, REACH, DisplayShadowReach.TRANSFORMED_ITEM_RADIUS));
    }

    @Test
    void extentAddsTheTranslationToTheLargestScale() {
        var moved = new Transformation(new Vector3f(0, 3, -4), new Quaternionf(), new Vector3f(1, 1, 1), new Quaternionf());
        assertEquals(5.0F + 2.0F, DisplayShadowReach.extent(moved, 2.0F), 1.0E-4F);
        var scaled = new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(1, -6, 2), new Quaternionf());
        assertEquals(12.0F, DisplayShadowReach.extent(scaled, 2.0F), 1.0E-4F, "a mirrored or one-axis scale still counts");
        var turned = new Transformation(new Vector3f(0, 0, 5), new Quaternionf().rotateY(1.0F), new Vector3f(3, 3, 3), new Quaternionf().rotateX(0.5F));
        assertEquals(5.0F + 6.0F, DisplayShadowReach.extent(turned, 2.0F), 1.0E-3F, "rotation changes nothing");
    }

    @Test
    void onlyAnItemWithNoDisplayTransformGetsTheSmallRadius() {
        assertEquals(DisplayShadowReach.ITEM_RADIUS, DisplayShadowReach.itemRadius(ItemDisplayContext.NONE));
        for (var context : ItemDisplayContext.values())
            if (context != ItemDisplayContext.NONE) assertEquals(DisplayShadowReach.TRANSFORMED_ITEM_RADIUS, DisplayShadowReach.itemRadius(context), context::name);
    }

    @Test
    void radiiCoverTheModelLimits() {
        assertTrue(DisplayShadowReach.BLOCK_RADIUS >= Math.sqrt(3.0) * 2.0, "elements reach 2 blocks from the origin on each axis");
        assertTrue(DisplayShadowReach.ITEM_RADIUS >= Math.sqrt(3.0) * 1.5, "centred elements reach 1.5 blocks on each axis");
        assertTrue(DisplayShadowReach.TRANSFORMED_ITEM_RADIUS >= Math.sqrt(3.0) * 5.0 + 4.0 * Math.sqrt(3.0) * 1.5, "a display transform adds 5 per axis and scales by 4");
    }
}
