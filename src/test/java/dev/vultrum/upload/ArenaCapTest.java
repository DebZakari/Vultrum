package dev.vultrum.upload;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ArenaCapTest {
    private static final long MIB = 1L << 20;

    @Test
    void capsSodiumsSize() {
        assertEquals(96 * MIB, ArenaCap.size(256 * MIB, 40 * MIB, ArenaCap.CAP_BYTES));
        assertEquals(96 * MIB, ArenaCap.size(128 * MIB, 0, ArenaCap.CAP_BYTES));
    }

    @Test
    void leavesSmallerArenasAlone() {
        assertEquals(32 * MIB, ArenaCap.size(32 * MIB, 0, ArenaCap.CAP_BYTES), "Sodium's first geometry arena");
        assertEquals(96 * MIB, ArenaCap.size(96 * MIB, 20 * MIB, ArenaCap.CAP_BYTES));
    }

    @Test
    void neverLeavesTooLittleForTheData() {
        assertEquals(200 * MIB + 4, ArenaCap.size(400 * MIB + 8, 200 * MIB + 4, ArenaCap.CAP_BYTES));
        assertEquals(97 * MIB, ArenaCap.size(256 * MIB, 97 * MIB, ArenaCap.CAP_BYTES));
    }
}
