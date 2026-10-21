package dev.vultrum.compat;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class PunchyHandLookupTest {
    private static final String HAND = "net.irisshaders.iris.pathways.HandRenderer";
    private static final String RESOLVE = "punchy$resolveIrisHandRendererReflection";

    /** A resolver with the same call-site behavior: cached success, a retryable null instance, and a caught failure. */
    public static class Renderer {
        private static Object instance;
        private static Method active, end;

        private static boolean punchy$resolveIrisHandRendererReflection() {
            if (instance != null && active != null && end != null) return true;
            try {
                Class<?> type = Class.forName("net.irisshaders.iris.pathways.HandRenderer");
                Object found = type.getField("INSTANCE").get(null);
                Method isActive = type.getMethod("isActive");
                Method endRender = type.getMethod("endRender");
                if (found == null) return false;
                instance = found;
                active = isActive;
                end = endRender;
                return true;
            } catch (Throwable failure) {
                return false;
            }
        }
    }

    private static ClassNode fixture(boolean expanded) throws Exception {
        var node = new ClassNode();
        try (var in = Renderer.class.getResourceAsStream("PunchyHandLookupTest$Renderer.class")) {
            assertNotNull(in);
            new ClassReader(in).accept(node, expanded ? ClassReader.EXPAND_FRAMES : 0);
        }
        node.nestHostClass = null;
        node.nestMembers = null;
        node.innerClasses.clear();
        node.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        return node;
    }

    private static byte[] bytes(ClassNode node) {
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static final class Loader extends ClassLoader {
        private final byte[] renderer;
        private final String mode;
        private int lookups;

        Loader(byte[] renderer, String mode) {
            super(ClassLoader.getPlatformClassLoader());
            this.renderer = renderer;
            this.mode = mode;
        }

        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> type = findLoadedClass(name);
                if (type == null && name.equals(Renderer.class.getName())) {
                    type = defineClass(name, renderer, 0, renderer.length);
                } else if (type == null && name.equals(HAND)) {
                    lookups++;
                    if (mode.equals("missing")) throw new ClassNotFoundException(name);
                    if (mode.equals("transient") && lookups == 1) throw new IllegalStateException("not ready");
                    byte[] stub = handRenderer();
                    type = defineClass(name, stub, 0, stub.length);
                }
                if (type == null) type = super.loadClass(name, false);
                if (resolve) resolveClass(type);
                return type;
            }
        }
    }

    private static byte[] handRenderer() {
        String name = HAND.replace('.', '/');
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "INSTANCE", "L" + name + ";", null, null).visitEnd();
        var init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode(); init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN); init.visitMaxs(0, 0); init.visitEnd();
        var active = writer.visitMethod(Opcodes.ACC_PUBLIC, "isActive", "()Z", null, null);
        active.visitCode(); active.visitInsn(Opcodes.ICONST_1); active.visitInsn(Opcodes.IRETURN); active.visitMaxs(0, 0); active.visitEnd();
        var end = writer.visitMethod(Opcodes.ACC_PUBLIC, "endRender", "()V", null, null);
        end.visitCode(); end.visitInsn(Opcodes.RETURN); end.visitMaxs(0, 0); end.visitEnd();
        var clinit = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode(); clinit.visitTypeInsn(Opcodes.NEW, name); clinit.visitInsn(Opcodes.DUP);
        clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, name, "<init>", "()V", false);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, name, "INSTANCE", "L" + name + ";");
        clinit.visitInsn(Opcodes.RETURN); clinit.visitMaxs(0, 0); clinit.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static Method resolver(Loader loader) throws Exception {
        Method method = loader.loadClass(Renderer.class.getName()).getDeclaredMethod(RESOLVE);
        method.setAccessible(true);
        return method;
    }

    @Test void absentClassIsLookedUpOnceAcrossRepeatedHandPasses() throws Exception {
        for (boolean expanded : new boolean[]{false, true}) {
            ClassNode node = fixture(expanded);
            PunchyHandLookup.apply(node);
            var loader = new Loader(bytes(node), "missing");
            Method method = resolver(loader);
            for (int frame = 0; frame < 100; frame++)
                for (int pass = 0; pass < 3; pass++) assertEquals(false, method.invoke(null));
            assertEquals(1, loader.lookups, "one absence check across frames and hand passes");
        }
    }

    @Test void availableRendererAndLateInstanceKeepWorking() throws Exception {
        for (boolean expanded : new boolean[]{false, true}) {
            ClassNode node = fixture(expanded);
            PunchyHandLookup.apply(node);
            var loader = new Loader(bytes(node), "available");
            Class<?> hand = loader.loadClass(HAND);
            var instance = hand.getField("INSTANCE");
            instance.set(null, null);
            Method method = resolver(loader);
            assertEquals(false, method.invoke(null));
            instance.set(null, hand.getConstructor().newInstance());
            for (int frame = 0; frame < 100; frame++) assertEquals(true, method.invoke(null));
        }
    }

    @Test void nonAbsenceFailuresAreRetried() throws Exception {
        ClassNode node = fixture(true);
        PunchyHandLookup.apply(node);
        var loader = new Loader(bytes(node), "transient");
        Method method = resolver(loader);
        assertEquals(false, method.invoke(null));
        assertEquals(true, method.invoke(null));
        assertEquals(2, loader.lookups);
    }

    @Test void unknownResolverIsLeftUnchanged() throws Exception {
        ClassNode node = fixture(false);
        node.methods.stream().filter(m -> m.name.equals(RESOLVE)).forEach(m ->
                m.instructions.forEach(i -> { if (i instanceof LdcInsnNode value && HAND.equals(value.cst)) value.cst = "other.Renderer"; }));
        byte[] before = bytes(node);
        assertFalse(PunchyHandLookup.apply(node));
        assertArrayEquals(before, bytes(node));
    }

    @Test void onlyTheCheckedModCombinationIsSupported() {
        for (String version : new String[]{"2.8f-hotfix", "2.8g"}) {
            assertTrue(PunchyHandLookup.supports(version, true, false));
            assertFalse(PunchyHandLookup.supports(version, false, false));
            assertFalse(PunchyHandLookup.supports(version, true, true));
        }
        assertFalse(PunchyHandLookup.supports(null, true, false));
        assertFalse(PunchyHandLookup.supports("2.8h", true, false));
        assertFalse(PunchyHandLookup.supports("2.8g-hotfix", true, false));
    }

    @Test void unexpectedCallInTheFailureRegionIsLeftUnchanged() throws Exception {
        ClassNode node = fixture(false);
        MethodNode method = node.methods.stream().filter(m -> m.name.equals(RESOLVE)).findFirst().orElseThrow();
        var helper = new MethodInsnNode(Opcodes.INVOKESTATIC, "other/Helper", "prepare", "()V", false);
        method.instructions.insert(method.tryCatchBlocks.getFirst().start, helper);
        byte[] before = bytes(node);
        assertFalse(PunchyHandLookup.apply(node));
        assertArrayEquals(before, bytes(node));
    }

    @Test void lookupOutsideTheFailureRegionIsLeftUnchanged() throws Exception {
        ClassNode node = fixture(true);
        MethodNode method = node.methods.stream().filter(m -> m.name.equals(RESOLVE)).findFirst().orElseThrow();
        var lookup = method.instructions.iterator();
        while (lookup.hasNext()) {
            AbstractInsnNode instruction = lookup.next();
            if (instruction instanceof MethodInsnNode call && call.name.equals("forName")) {
                var afterLookup = new LabelNode();
                method.instructions.insert(call, afterLookup);
                method.tryCatchBlocks.getFirst().start = afterLookup;
                break;
            }
        }
        byte[] before = bytes(node);
        assertFalse(PunchyHandLookup.apply(node));
        assertArrayEquals(before, bytes(node));
    }

    @Test void repeatedApplicationDoesNotAddAnotherGuard() throws Exception {
        ClassNode node = fixture(true);
        assertTrue(PunchyHandLookup.apply(node));
        byte[] once = bytes(node);
        assertFalse(PunchyHandLookup.apply(node));
        assertArrayEquals(once, bytes(node));
    }
}
