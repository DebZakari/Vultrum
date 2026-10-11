package dev.vultrum.cull;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ComputeShaderTest {
    // SPIR-V core opcodes and capability IDs, from Khronos SPIRV-Headers.
    private static final int EXECUTION_MODE = 16, CAPABILITY = 17, LOCAL_SIZE = 17;

    @Test void portableBinaryHasNoSubgroupRequirement() throws IOException {
        var portable = instructions("occlusion_cull_portable");
        assertEquals(List.of(1), portable.stream().filter(i -> i[0] == CAPABILITY).map(i -> i[1]).toList());
        var fast = instructions("occlusion_cull");
        assertTrue(fast.stream().anyMatch(i -> i[0] == CAPABILITY && i[1] == 63)); // GroupNonUniformArithmetic
    }

    @Test void packagedBinariesFitTheirSelectedLimits() throws IOException {
        for (var path : List.of(new ComputePath(false, 128), new ComputePath(false, 256),
                new ComputePath(true, 128), new ComputePath(true, 256))) {
            assertLocalSize(path.cullShader(), 64);
            assertLocalSize(path.hizShader(), path.hizGroup());
        }
    }

    private static void assertLocalSize(String name, int x) throws IOException {
        int[] mode = instructions(name).stream().filter(i -> i[0] == EXECUTION_MODE && i[2] == LOCAL_SIZE).findFirst().orElseThrow();
        assertArrayEquals(new int[] {EXECUTION_MODE, mode[1], LOCAL_SIZE, x, 1, 1}, mode);
    }

    private static List<int[]> instructions(String name) throws IOException {
        try (var resource = ComputeShaderTest.class.getResourceAsStream("/assets/vultrum/shaders/" + name + ".spv")) {
            assertNotNull(resource, name);
            var words = ByteBuffer.wrap(resource.readAllBytes()).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
            assertEquals(0x07230203, words.get(0));
            var result = new ArrayList<int[]>();
            for (int at = 5; at < words.limit();) {
                int header = words.get(at), count = header >>> 16;
                assertTrue(count > 0 && at + count <= words.limit(), "invalid SPIR-V instruction");
                int[] instruction = new int[count];
                instruction[0] = header & 0xffff;
                for (int i = 1; i < count; i++) instruction[i] = words.get(at+i);
                result.add(instruction);
                at += count;
            }
            return result;
        }
    }
}
