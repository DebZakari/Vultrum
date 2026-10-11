package dev.vultrum.mixin;

import net.caffeinemc.mods.sodium.client.gpu.device.batch.VKIndirectDrawBatch;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(VKIndirectDrawBatch.class)
public interface VKIndirectDrawBatchAccessor {
    /** Byte offset of the batch's commands in the indirect ring, set by {@code prepare}. */
    @Accessor("offset") long vultrum$offset();
}
