package dev.vultrum.mixin;

import dev.vultrum.cull.CullBatch;
import net.caffeinemc.mods.sodium.client.gpu.device.batch.MultiDrawBatch;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.Arrays;

@Mixin(MultiDrawBatch.class)
abstract class MultiDrawBatchMixin implements CullBatch {
    @Unique private int[] vultrum$sections = new int[0];
    @Unique private int vultrum$region = -1;

    @Override
    public void vultrum$section(int from, int to, int x, int y, int z, int region, int index) {
        if (to * 4 > vultrum$sections.length) vultrum$sections = Arrays.copyOf(vultrum$sections, Math.max(to * 4, vultrum$sections.length * 2));
        vultrum$region = region;
        int key = region * SECTIONS_PER_REGION + index;
        for (int i = from * 4; i < to * 4; i += 4) {
            vultrum$sections[i] = x;
            vultrum$sections[i + 1] = y;
            vultrum$sections[i + 2] = z;
            vultrum$sections[i + 3] = key;
        }
    }

    @Override
    public int[] vultrum$sections() { return vultrum$sections; }

    @Override
    public int vultrum$region() { return vultrum$region; }
}
