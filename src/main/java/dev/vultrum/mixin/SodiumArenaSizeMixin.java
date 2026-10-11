package dev.vultrum.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.vultrum.upload.ArenaCap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Caps the size of each new shared arena Sodium creates for geometry or indices (see {@link ArenaCap}). */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.gpu.arena.ArenaAggregator$DataType")
abstract class SodiumArenaSizeMixin {
    @Shadow @Final int stride;

    @ModifyExpressionValue(method = "ensureSharedArena", at = @At(value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/client/gpu/arena/ArenaAggregator$DataType;calculateArenaSize(IJ)J"))
    private long vultrum$capArenaSize(long bytes, @Local(argsOnly = true) long requiredCapacity) {
        return ArenaCap.size(bytes, requiredCapacity * stride);
    }
}
