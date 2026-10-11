package dev.vultrum.cull;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CullCountersTest {
    @Test
    void packsAndUnpacks() {
        var counters = new CullCounters(4000, 1500, 64, 3_000_000, 900_000, 20_000);
        var buffer = ByteBuffer.allocate(CullCounters.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        counters.pack(buffer);
        assertEquals(0, buffer.position());
        assertEquals(counters, CullCounters.unpack(buffer));
        assertEquals(2_080_000, counters.hiddenTriangles());
    }

    @Test
    void readsCountersAsUnsigned() {
        var buffer = ByteBuffer.allocate(CullCounters.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(CullCounters.TRIANGLES, -1);
        assertEquals(0xFFFF_FFFFL, CullCounters.unpack(buffer).triangles());
    }

    @Test
    void addsUp() {
        var a = new CullCounters(1, 2, 3, 4, 5, 6);
        assertEquals(new CullCounters(2, 4, 6, 8, 10, 12), a.plus(a));
        assertEquals(a, CullCounters.ZERO.plus(a));
    }

    @Test
    void cullShaderMatches() throws IOException {
        var expected = Map.of("commands", CullCounters.COMMANDS, "phase1Commands", CullCounters.PHASE1_COMMANDS,
                "phase2Commands", CullCounters.PHASE2_COMMANDS, "triangles", CullCounters.TRIANGLES,
                "phase1Triangles", CullCounters.PHASE1_TRIANGLES, "phase2Triangles", CullCounters.PHASE2_TRIANGLES);
        var members = ShaderBlocks.offsets("occlusion_cull.comp", "Stats");
        assertEquals(expected, members);
        assertEquals(CullCounters.BYTES, members.size() * Integer.BYTES);
    }
}
