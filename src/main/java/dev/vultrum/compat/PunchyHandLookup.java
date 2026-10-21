package dev.vultrum.compat;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.List;

/** Installs the optional Punchy hand-renderer lookup cache after its mixin has been applied. */
public final class PunchyHandLookup {
    private static final String RESOLVER = "punchy$resolveIrisHandRendererReflection";
    private static final String HAND_RENDERER = "net.irisshaders.iris.pathways.HandRenderer";
    private static final String MISSING = "vultrum$irisHandRendererMissing";
    private PunchyHandLookup() {}

    public static boolean supports(String version, boolean vitrail, boolean iris) {
        return ("2.8f-hotfix".equals(version) || "2.8g".equals(version)) && vitrail && !iris;
    }

    public static boolean apply(ClassNode target) {
        if (target.fields.stream().anyMatch(field -> field.name.equals(MISSING))) return false;
        List<MethodNode> matches = target.methods.stream().filter(method -> method.name.equals(RESOLVER)
                && method.desc.equals("()Z") && (method.access & Opcodes.ACC_STATIC) != 0).toList();
        if (matches.size() != 1) return false;
        MethodNode method = matches.getFirst();
        if (method.tryCatchBlocks.size() != 2) return false;
        LabelNode handler = method.tryCatchBlocks.getFirst().handler;
        if (method.tryCatchBlocks.stream().anyMatch(block -> !"java/lang/Throwable".equals(block.type) || block.handler != handler))
            return false;
        AbstractInsnNode store = opcode(handler);
        if (!(store instanceof VarInsnNode variable) || variable.getOpcode() != Opcodes.ASTORE || variable.var != 0)
            return false;
        AbstractInsnNode zero = opcode(store.getNext());
        if (zero == null || zero.getOpcode() != Opcodes.ICONST_0) return false;
        AbstractInsnNode exit = opcode(zero.getNext());
        if (exit == null || exit.getOpcode() != Opcodes.IRETURN) return false;

        int lookups = 0;
        List<String> calls = new java.util.ArrayList<>();
        boolean expanded = false, compressed = false;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof InvokeDynamicInsnNode || instruction.getOpcode() == Opcodes.ATHROW) return false;
            if (instruction instanceof MethodInsnNode call) {
                int position = method.instructions.indexOf(call);
                if (method.tryCatchBlocks.stream().noneMatch(block -> position >= method.instructions.indexOf(block.start)
                        && position < method.instructions.indexOf(block.end))) return false;
                if (call.itf) return false;
                calls.add(call.getOpcode() + ":" + call.owner + "." + call.name + call.desc);
            }
            if (instruction instanceof MethodInsnNode call && call.owner.equals("java/lang/Class") && call.name.equals("forName")) {
                if (call.getOpcode() != Opcodes.INVOKESTATIC || !call.desc.equals("(Ljava/lang/String;)Ljava/lang/Class;")) return false;
                AbstractInsnNode argument = call.getPrevious();
                while (argument != null && argument.getOpcode() < 0) argument = argument.getPrevious();
                if (!(argument instanceof LdcInsnNode literal) || !HAND_RENDERER.equals(literal.cst)) return false;
                lookups++;
            }
            if (instruction instanceof FrameNode frame) {
                if (frame.type == Opcodes.F_NEW) expanded = true;
                else compressed = true;
            }
        }
        // Only the checked reflection calls may fail here. An added helper could throw an unrelated absence.
        if (lookups != 1 || expanded && compressed || !calls.equals(List.of(
                Opcodes.INVOKESTATIC + ":java/lang/Class.forName(Ljava/lang/String;)Ljava/lang/Class;",
                Opcodes.INVOKEVIRTUAL + ":java/lang/Class.getField(Ljava/lang/String;)Ljava/lang/reflect/Field;",
                Opcodes.INVOKEVIRTUAL + ":java/lang/reflect/Field.get(Ljava/lang/Object;)Ljava/lang/Object;",
                Opcodes.INVOKEVIRTUAL + ":java/lang/Class.getMethod(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;",
                Opcodes.INVOKEVIRTUAL + ":java/lang/Class.getMethod(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;")))
            return false;

        // Validate everything before changing the target. Unknown layouts keep Punchy's original behavior.
        int frameType = expanded ? Opcodes.F_NEW : Opcodes.F_FULL;
        var original = new LabelNode();
        var guard = new InsnList();
        guard.add(new FieldInsnNode(Opcodes.GETSTATIC, target.name, MISSING, "Z"));
        guard.add(new JumpInsnNode(Opcodes.IFEQ, original));
        guard.add(new InsnNode(Opcodes.ICONST_0));
        guard.add(new InsnNode(Opcodes.IRETURN));
        guard.add(original);
        guard.add(new FrameNode(frameType, 0, null, 0, null));

        var retryable = new LabelNode();
        var remember = new InsnList();
        remember.add(new VarInsnNode(Opcodes.ALOAD, 0));
        remember.add(new TypeInsnNode(Opcodes.INSTANCEOF, "java/lang/ClassNotFoundException"));
        remember.add(new JumpInsnNode(Opcodes.IFEQ, retryable));
        remember.add(new InsnNode(Opcodes.ICONST_1));
        remember.add(new FieldInsnNode(Opcodes.PUTSTATIC, target.name, MISSING, "Z"));
        remember.add(retryable);
        remember.add(new FrameNode(frameType, 1, new Object[]{"java/lang/Throwable"}, 0, null));
        target.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE | Opcodes.ACC_SYNTHETIC,
                MISSING, "Z", null, null));
        method.instructions.insert(guard);
        method.instructions.insert(store, remember);
        return true;
    }

    private static AbstractInsnNode opcode(AbstractInsnNode instruction) {
        while (instruction != null && instruction.getOpcode() < 0) instruction = instruction.getNext();
        return instruction;
    }
}
