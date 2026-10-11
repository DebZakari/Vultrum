package dev.vultrum.mixin;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The columns Sodium has queued to add to and remove from its render graph. */
@Mixin(ChunkTracker.class)
public interface SodiumChunkTrackerAccessor {
    @Accessor("unloadQueue")
    LongSet vultrum$unloadQueue();

    @Accessor("loadQueue")
    LongSet vultrum$loadQueue();
}
