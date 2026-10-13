package dev.vultrum.mixin;

import dev.vultrum.bench.Timeline;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Bench only (see VultrumMixinPlugin): the light data the client applies from the server, for {@link Timeline}. */
@Mixin(ClientPacketListener.class)
abstract class ClientLightTimelineMixin {
    @Inject(method = "applyLightData", at = @At("HEAD"))
    private void vultrum$lightData(int x, int z, ClientboundLightUpdatePacketData data, boolean update, CallbackInfo ci) {
        Timeline.event("lightData", x, 0, z, String.valueOf(update));
    }
}
