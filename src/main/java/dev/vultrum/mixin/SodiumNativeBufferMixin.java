package dev.vultrum.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.vultrum.bench.FrameTimer;
import dev.vultrum.upload.ReclaimThread;
import net.caffeinemc.mods.sodium.client.util.NativeBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.lang.ref.Reference;

/**
 * Bench only (see VultrumMixinPlugin): counts the buffer references Sodium's reclaim polls, which is what its time
 * follows. Sodium also calls it from a failed allocation, on any thread: an emergency the count isn't exact for.
 */
@Mixin(NativeBuffer.class)
abstract class SodiumNativeBufferMixin {
    // One static call for each poll, inside the loop the reclaim phase times: not a wrapped operation, which
    // would add a call through an Operation and an argument array to each.
    @ModifyExpressionValue(method = "reclaim", at = @At(value = "INVOKE", target = "Ljava/lang/ref/ReferenceQueue;poll()Ljava/lang/ref/Reference;"))
    private static Reference<?> vultrum$countReclaimed(Reference<?> ref) {
        if (ref != null) {
            // The reclaim thread counts its own, for its batches.
            if (ReclaimThread.isCurrent()) ReclaimThread.client().countRef();
            else FrameTimer.reclaimedRef();
        }
        return ref;
    }
}
