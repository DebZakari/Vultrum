package dev.vultrum.cull;

import java.nio.ByteBuffer;

/**
 * One culled frame's GPU counters: the std430 {@code Stats} block at binding 4 of {@code occlusion_cull.comp}.
 * Offsets are in bytes; {@code CullCountersTest} checks them against the shader.
 *
 * @param commands  live commands phase 2 tested
 * @param triangles triangles those commands list
 */
record CullCounters(long commands, long phase1Commands, long phase2Commands,
                    long triangles, long phase1Triangles, long phase2Triangles) {
    static final int COMMANDS = 0, PHASE1_COMMANDS = 4, PHASE2_COMMANDS = 8,
            TRIANGLES = 12, PHASE1_TRIANGLES = 16, PHASE2_TRIANGLES = 20, BYTES = 24;
    static final CullCounters ZERO = new CullCounters(0, 0, 0, 0, 0, 0);

    /** Reads the block at the start of {@code from}, which must be little-endian. The shader's counters are unsigned. */
    static CullCounters unpack(ByteBuffer from) {
        return new CullCounters(word(from, COMMANDS), word(from, PHASE1_COMMANDS), word(from, PHASE2_COMMANDS),
                word(from, TRIANGLES), word(from, PHASE1_TRIANGLES), word(from, PHASE2_TRIANGLES));
    }

    /** Writes the block at the start of {@code to}, which must be little-endian; leaves its position alone. */
    void pack(ByteBuffer to) {
        to.putInt(COMMANDS, (int) commands).putInt(PHASE1_COMMANDS, (int) phase1Commands).putInt(PHASE2_COMMANDS, (int) phase2Commands);
        to.putInt(TRIANGLES, (int) triangles).putInt(PHASE1_TRIANGLES, (int) phase1Triangles).putInt(PHASE2_TRIANGLES, (int) phase2Triangles);
    }

    CullCounters plus(CullCounters other) {
        return new CullCounters(commands + other.commands, phase1Commands + other.phase1Commands, phase2Commands + other.phase2Commands,
                triangles + other.triangles, phase1Triangles + other.phase1Triangles, phase2Triangles + other.phase2Triangles);
    }

    /** Triangles neither phase drew. */
    long hiddenTriangles() {
        return triangles - phase1Triangles - phase2Triangles;
    }

    private static long word(ByteBuffer from, int offset) {
        return Integer.toUnsignedLong(from.getInt(offset));
    }
}
