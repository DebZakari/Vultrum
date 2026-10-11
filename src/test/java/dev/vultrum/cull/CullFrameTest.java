package dev.vultrum.cull;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CullFrameTest {
    private static final Map<String, Integer> OFFSETS = Map.ofEntries(
            Map.entry("viewProj", CullFrame.VIEW_PROJ), Map.entry("cameraInt", CullFrame.CAMERA_INT),
            Map.entry("pinned", CullFrame.PINNED), Map.entry("cameraFrac", CullFrame.CAMERA_FRAC),
            Map.entry("frame", CullFrame.FRAME), Map.entry("commandBase", CullFrame.COMMAND_BASE),
            Map.entry("commandCount", CullFrame.COMMAND_COUNT), Map.entry("levels", CullFrame.LEVELS),
            Map.entry("depthSize", CullFrame.DEPTH_SIZE), Map.entry("size0", CullFrame.SIZE0),
            Map.entry("sections", CullFrame.BYTES));

    @Test
    void packsAndUnpacks() {
        var frame = new CullFrame(new Matrix4f().perspective(1.2f, 16 / 9f, 0.05f, 1000).translate(1, 2, 3),
                -1234567, -64, 7654321, true, 0.25f, 0.5f, 0.75f, 42, 1000, 2000, 9, 1920, 1080, 240, 135);
        var buffer = ByteBuffer.allocateDirect(CullFrame.BYTES).order(ByteOrder.LITTLE_ENDIAN); // JOML writes matrices to the native address
        frame.pack(buffer);
        assertEquals(0, buffer.position());
        assertEquals(frame, CullFrame.unpack(buffer));
        assertEquals(42, buffer.getInt(CullFrame.FRAME));
        assertEquals(1, buffer.getInt(CullFrame.PINNED));
    }

    @Test
    void cullShaderMatches() throws IOException {
        var members = ShaderBlocks.offsets("occlusion_cull.comp", "Frame");
        assertEquals(OFFSETS.keySet(), members.keySet());
        assertTrue(ShaderBlocks.source("occlusion_cull.comp").contains("ivec4 sections[];"));
        assertEquals(ShaderBlocks.std430("ivec4")[1], CullFrame.SECTION_BYTES);
        members.forEach((name, offset) -> assertEquals(OFFSETS.get(name), offset, name));
    }

    @Test
    void hizShaderMatches() throws IOException {
        // The Hi-Z shader reads cameraInt as an ivec4 over pinned, and has no sections.
        var members = ShaderBlocks.offsets("occlusion_hiz.comp", "Frame");
        var expected = new HashSet<>(OFFSETS.keySet());
        expected.removeAll(Set.of("pinned", "sections"));
        assertEquals(expected, members.keySet());
        members.forEach((name, offset) -> assertEquals(OFFSETS.get(name), offset, name));
    }
}
