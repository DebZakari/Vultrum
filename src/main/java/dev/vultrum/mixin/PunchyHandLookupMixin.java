package dev.vultrum.mixin;

import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import org.spongepowered.asm.mixin.Mixin;

/** Runs the compatibility transform after Punchy's hand-renderer methods have been merged. */
@Mixin(value = FirstPersonHandsAndItemsRenderer.class, priority = 100)
public abstract class PunchyHandLookupMixin {}
