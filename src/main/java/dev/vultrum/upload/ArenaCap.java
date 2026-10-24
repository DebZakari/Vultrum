package dev.vultrum.upload;

import dev.vultrum.VultrumConfig;
import dev.vultrum.LaunchHooks;

/**
 * Caps the size of each new arena, the buffers Sodium stores terrain geometry and indices in. A new buffer of tens
 * of MiB gets its own video memory, and the GPU stalls before the frame that creates it: on the RTX 3090 under
 * Windows about 0.2 ms at 96 MiB, 1.4 ms at 128 MiB and 5.3 ms at 256 MiB, the size Sodium picks once it holds two
 * arenas. With the cap the stall was gone, and fast flights at RD 32 used up to 557 MiB more video
 * memory, since smaller arenas pack terrain less tightly.
 */
public final class ArenaCap {
    /** The largest new arena. A 112 MiB one stalled the GPU for 0.9 ms. */
    public static final long CAP_BYTES = 96L << 20;

    private ArenaCap() { }

    /** Whether new arenas are capped: the setting is on and Sodium is a version the cap's mixin supports. */
    public static boolean on() {
        return Client.ON;
    }

    /** The size of a new arena: Sodium's choice, capped while the setting is on. */
    public static long size(long sodiumBytes, long requiredBytes) {
        return Client.ON ? size(sodiumBytes, requiredBytes, CAP_BYTES) : sodiumBytes;
    }

    /**
     * Sodium's size capped at {@code capBytes}, but never less than the bytes the arena must hold. An arena that
     * must hold more gets no room to spare, which keeps its stall as short as it can be: in the measured runs, none
     * had to hold more than 84 MiB.
     */
    static long size(long sodiumBytes, long requiredBytes, long capBytes) {
        return Math.max(Math.min(sodiumBytes, capBytes), requiredBytes);
    }

    /** The setting, loaded at its first use so that the rule above can be tested without a game. */
    private static final class Client {
        static final boolean ON = VultrumConfig.get().arenaCap() && LaunchHooks.get().sodium();
    }
}
