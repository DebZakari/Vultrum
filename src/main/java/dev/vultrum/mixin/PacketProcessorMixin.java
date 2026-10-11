package dev.vultrum.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.vultrum.net.PacketBudget;
import net.minecraft.network.PacketProcessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Queue;

/** Ends the client's packet drain once the frame's budget is spent; the rest stay queued, in order. */
@Mixin(PacketProcessor.class)
abstract class PacketProcessorMixin {
    @Unique private boolean vultrum$budgeted;

    @Inject(method = "processQueuedPackets", at = @At("HEAD"))
    private void vultrum$beginBudget(CallbackInfo info) {
        vultrum$budgeted = PacketBudget.beginClient();
    }

    // The queue is asked first: the budget is only asked while packets are left, so its last answer says whether
    // this drain was cut short.
    @WrapOperation(method = "processQueuedPackets", at = @At(value = "INVOKE", target = "Ljava/util/Queue;isEmpty()Z"))
    private boolean vultrum$stopWhenSpent(Queue<?> queue, Operation<Boolean> original) {
        return original.call(queue) || vultrum$budgeted && PacketBudget.stopClient();
    }

    @Inject(method = "processQueuedPackets", at = @At("RETURN"))
    private void vultrum$endBudget(CallbackInfo info) {
        if (vultrum$budgeted) PacketBudget.endClient();
    }
}
