package dev.vultrum.upload;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** External ordering contracts required by the column hooks; inspect the actual pinned Sodium dependency. */
class SodiumColumnOrderTest {
    private static final String RENDERER = "net/caffeinemc/mods/sodium/client/render/SodiumWorldRenderer";
    private static final String TRACKER = "net/caffeinemc/mods/sodium/client/render/chunk/map/ChunkTracker";

    private static MethodNode method(String name) throws IOException {
        try (var input = SodiumColumnOrderTest.class.getClassLoader().getResourceAsStream(RENDERER + ".class")) {
            assertNotNull(input);
            var node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
        }
    }

    private static List<AbstractInsnNode> code(MethodNode method) {
        var result = new ArrayList<AbstractInsnNode>();
        for (var instruction : method.instructions) if (instruction.getOpcode() >= 0) result.add(instruction);
        return result;
    }

    private static int call(List<AbstractInsnNode> code, String owner, String name) {
        var matches = new ArrayList<Integer>();
        for (int i = 0; i < code.size(); i++)
            if (code.get(i) instanceof MethodInsnNode m && m.owner.equals(owner) && m.name.equals(name)) matches.add(i);
        assertEquals(1, matches.size(), owner + "." + name + " must have exactly one injection site");
        return matches.getFirst();
    }

    @Test
    void initializationInvalidatesOldManagerBeforeSnapshotAndSnapshotIsLastWork() throws IOException {
        var code = code(method("initRenderer"));
        int destroy = call(code, RENDERER, "deleteRendererState");
        int snapshot = call(code, TRACKER, "forEachChunk");
        assertTrue(destroy < snapshot);
        assertEquals(code.size() - 2, snapshot, "stale-unload cleanup must still follow all original initialization work");
        assertEquals(Opcodes.RETURN, code.getLast().getOpcode());
    }

    @Test
    void sectionUpdatesPrecedeDispatchAndDispatchIsLastWork() throws IOException {
        var code = code(method("processChunkEvents"));
        int before = call(code, "net/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager", "beforeSectionUpdates");
        int events = call(code, TRACKER, "forEachEvent");
        assertTrue(before < events);
        assertEquals(code.size() - 2, events, "registration drain must retain its position after all original events");
        assertEquals(Opcodes.RETURN, code.getLast().getOpcode());
    }

    @Test
    void worldUnloadDiscardsGenerationBeforeDroppingLevelReference() throws IOException {
        var code = code(method("unloadLevel"));
        int destroy = call(code, RENDERER, "deleteRendererState");
        for (int i = 0; i < code.size(); i++)
            if (code.get(i) instanceof org.objectweb.asm.tree.FieldInsnNode f && f.name.equals("level")
                    && f.getOpcode() == Opcodes.PUTFIELD) assertTrue(destroy < i);
    }
}
