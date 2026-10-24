package dev.vultrum;

import com.google.gson.JsonParser;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.fabricmc.loader.api.Version;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class LaunchHooksTest {
    private static final String SODIUM_TARGET = "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer";
    private static final String VITRAIL_TARGET = "dev.vitrail.render.TerrainDraw";
    private static final String VANILLA_TARGET = "net.minecraft.client.renderer.GameRenderer";
    private static final String PREFIX = "dev.vultrum.mixin.";
    private static final Set<String> BENCH = Set.of("ClientLevelMixin", "MinecraftMixin", "RenderSectionManagerMixin",
            "SodiumSetupPhasesMixin", "SodiumNativeBufferMixin", "SodiumBuildSubmitMixin");
    private static final Set<String> CHUNKS = Set.of("ClientChunkTimelineMixin", "ClientChunkStorageTimelineMixin");
    private static final Set<String> FULL = Set.of("ClientLightTimelineMixin", "ClientChunkLightTimelineMixin", "SodiumDirtyTimelineMixin");

    private static LaunchHooks selection(String sodium, String vitrail, String punchy, boolean iris,
                                         String bench, String timeline, BooleanSupplier requested) throws Exception {
        return new LaunchHooks(mods(sodium, vitrail, punchy, iris), bench, timeline, requested);
    }

    private static Map<String, Version> mods(String sodium, String vitrail, String punchy, boolean iris) throws Exception {
        var mods = new HashMap<String, Version>();
        if (sodium != null) mods.put("sodium", Version.parse(sodium));
        if (vitrail != null) mods.put("vitrail", Version.parse(vitrail));
        if (punchy != null) mods.put("punchy", Version.parse(punchy));
        if (iris) mods.put("iris", Version.parse("1.0.0"));
        return mods;
    }

    private static boolean noConfig() { throw new AssertionError("Unrelated selection must not read config"); }

    @Test
    void actualBuildDependencyIsSupportedThroughThePluginInterface() throws Exception {
        var jar = Path.of(ModelQuadFacing.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (var fs = FileSystems.newFileSystem(jar)) {
            String version = JsonParser.parseString(Files.readString(fs.getPath("fabric.mod.json")))
                    .getAsJsonObject().get("version").getAsString();
            var hooks = selection(version, null, null, false, null, null, LaunchHooksTest::noConfig);
            assertTrue(hooks.sodium());
            assertTrue(new VultrumMixinPlugin(hooks).shouldApplyMixin(SODIUM_TARGET, PREFIX + "SodiumWorldRendererMixin"));
        }
    }

    @Test
    void dependencyRangesAndMissingModsAgreeAcrossRuntimeAndHookDecisions() throws Exception {
        String[] sodium = {null, "0.9.2+mc26.3", "0.9.3-alpha.1+mc26.3", "0.9.3-beta.2+mc26.3",
                "0.9.3+mc26.3", "0.9.4-alpha.1+mc26.3", "0.9.4+mc26.3"};
        boolean[] sodiumAllowed = {false, false, true, true, true, false, false};
        String[] vitrail = {null, "0.11.9", "0.12.0-beta-blockcracks3", "0.12.0", "0.12.5",
                "0.13.0-beta+mc26.3", "0.13.0", "0.14.0-alpha.1", "0.14.0"};
        boolean[] vitrailAllowed = {false, false, true, true, true, true, true, false, false};
        for (int i = 0; i < sodium.length; i++) for (int j = 0; j < vitrail.length; j++) {
            var hooks = selection(sodium[i], vitrail[j], null, false, null, null, LaunchHooksTest::noConfig);
            var plugin = new VultrumMixinPlugin(hooks);
            assertEquals(sodiumAllowed[i], hooks.sodium(), String.valueOf(sodium[i]));
            assertEquals(sodiumAllowed[i], plugin.shouldApplyMixin(SODIUM_TARGET, PREFIX + "SodiumWorldRendererMixin"));
            assertEquals(sodiumAllowed[i] && vitrailAllowed[j], hooks.vitrail());
            assertEquals(hooks.vitrail(), plugin.shouldApplyMixin(VITRAIL_TARGET, PREFIX + "VitrailTerrainDrawMixin"));
            assertTrue(plugin.shouldApplyMixin(VANILLA_TARGET, PREFIX + "PlayerSkinRenderInfoMixin"));
        }
    }

    /** Read actual annotation targets as bytecode, without initializing game/optional classes. */
    private static Map<String, List<String>> shippedHooks() throws Exception {
        var result = new LinkedHashMap<String, List<String>>();
        try (var input = Objects.requireNonNull(LaunchHooksTest.class.getResourceAsStream("/vultrum.mixins.json"))) {
            var manifest = JsonParser.parseReader(new InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            for (var name : manifest.getAsJsonArray("client")) {
                String simple = name.getAsString();
                String qualified = manifest.get("package").getAsString() + "." + simple;
                try (var bytes = Objects.requireNonNull(LaunchHooksTest.class.getResourceAsStream("/" + qualified.replace('.', '/') + ".class"))) {
                    var node = new ClassNode();
                    new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                    var targets = new ArrayList<String>();
                    var annotations = new ArrayList<AnnotationNode>();
                    if (node.visibleAnnotations != null) annotations.addAll(node.visibleAnnotations);
                    if (node.invisibleAnnotations != null) annotations.addAll(node.invisibleAnnotations);
                    for (var annotation : annotations) if (annotation.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")) {
                        for (int k = 0; k < annotation.values.size(); k += 2) {
                            Object value = annotation.values.get(k + 1);
                            if (annotation.values.get(k).equals("value"))
                                for (var type : (List<?>) value) targets.add(((Type) type).getClassName());
                            if (annotation.values.get(k).equals("targets"))
                                for (var target : (List<?>) value) targets.add((String) target);
                        }
                    }
                    assertFalse(targets.isEmpty(), qualified);
                    result.put(simple, List.copyOf(targets));
                }
            }
        }
        return result;
    }

    @Test
    void completeLaunchMatrixSelectsEveryShippedHookAgainstItsRealTargets() throws Exception {
        var shipped = shippedHooks();
        assertTrue(shipped.keySet().containsAll(BENCH));
        assertTrue(shipped.keySet().containsAll(CHUNKS));
        assertTrue(shipped.keySet().containsAll(FULL));
        for (String sodium : new String[]{null, "0.9.2", "0.9.3", "0.9.4-alpha.1"})
            for (String vitrail : new String[]{null, "0.11.9", "0.13.0", "0.14.0-alpha.1"})
                for (String bench : new String[]{null, "", "  ", "fixture.json"})
                    for (String mode : new String[]{null, "false", "TRUE", "Chunks", "true ", "yes"}) {
                        var hooks = selection(sodium, vitrail, null, false, bench, mode, LaunchHooksTest::noConfig);
                        var plugin = new VultrumMixinPlugin(hooks);
                        boolean benchmark = "fixture.json".equals(bench);
                        var expectedTimeline = bench == null ? LaunchHooks.TimelineMode.OFF
                                : "TRUE".equals(mode) ? LaunchHooks.TimelineMode.FULL
                                : "Chunks".equals(mode) ? LaunchHooks.TimelineMode.CHUNKS : LaunchHooks.TimelineMode.OFF;
                        assertEquals(expectedTimeline, hooks.timeline());
                        for (var entry : shipped.entrySet()) for (String target : entry.getValue()) {
                            String name = entry.getKey();
                            boolean allowed = !name.equals("PunchyHandLookupMixin");
                            if (BENCH.contains(name)) allowed &= benchmark;
                            if (CHUNKS.contains(name)) allowed &= benchmark && expectedTimeline != LaunchHooks.TimelineMode.OFF;
                            if (FULL.contains(name)) allowed &= benchmark && expectedTimeline == LaunchHooks.TimelineMode.FULL;
                            if (target.startsWith("net.caffeinemc.mods.sodium.")) allowed &= "0.9.3".equals(sodium);
                            if (target.startsWith("dev.vitrail.")) allowed &= "0.9.3".equals(sodium) && "0.13.0".equals(vitrail);
                            assertEquals(allowed, plugin.shouldApplyMixin(target, PREFIX + name),
                                    () -> name + " / " + target + " / " + bench + " / " + mode + " / " + sodium + " / " + vitrail);
                        }
                    }
    }

    @Test
    void punchyAvailabilityAndOptInSelectionStaySeparateAndConfigReadsAreLazy() throws Exception {
        for (String sodium : new String[]{null, "0.9.2", "0.9.3"})
            for (String vitrail : new String[]{null, "0.13.0", "0.14.0-alpha.1"})
                for (String punchy : new String[]{null, "2.8f-hotfix", "2.8g", "2.8h", "2.8g-hotfix"})
                    for (boolean iris : new boolean[]{false, true}) for (boolean optIn : new boolean[]{false, true}) {
                        var reads = new AtomicInteger();
                        var hooks = selection(sodium, vitrail, punchy, iris, null, null, () -> { reads.incrementAndGet(); return optIn; });
                        boolean available = "0.9.3".equals(sodium) && "0.13.0".equals(vitrail)
                                && ("2.8f-hotfix".equals(punchy) || "2.8g".equals(punchy)) && !iris;
                        assertEquals(available, hooks.punchyHandLookupAvailable());
                        assertEquals(0, reads.get(), "settings availability must not read the opt-in");
                        var plugin = new VultrumMixinPlugin(hooks);
                        assertEquals(available && optIn, plugin.shouldApplyMixin(VANILLA_TARGET, LaunchHooks.PUNCHY_HAND_LOOKUP));
                        assertEquals(available ? 1 : 0, reads.get());
                    }
    }

    @Test
    void warningDecisionsPreserveAbsentAndUnsupportedDistinction() throws Exception {
        var missing = selection(null, null, null, false, null, null, LaunchHooksTest::noConfig);
        assertEquals(List.of(), missing.warnings());
        var supported = selection("0.9.3", "0.13.0", null, false, null, null, LaunchHooksTest::noConfig);
        assertEquals(List.of(), supported.warnings());
        var unsupported = selection("0.9.4-alpha.1", "0.14.0", null, false, null, null, LaunchHooksTest::noConfig);
        assertEquals(List.of(
                "Vultrum supports sodium >=0.9.3-alpha.1 <0.9.4-alpha, found 0.9.4-alpha.1: features that hook into it are off",
                "Vultrum supports vitrail >=0.12.0-alpha <0.14.0-alpha, found 0.14.0: features that hook into it are off"), unsupported.warnings());
        assertThrows(UnsupportedOperationException.class, () -> unsupported.warnings().clear());
    }

    @Test
    void callerCannotMutateTheObservedInventory() throws Exception {
        var inventory = mods("0.9.3", "0.13.0", null, false);
        var hooks = new LaunchHooks(inventory, null, null, LaunchHooksTest::noConfig);
        inventory.clear();
        assertTrue(hooks.vitrail());
        assertTrue(new VultrumMixinPlugin(hooks).shouldApplyMixin(VITRAIL_TARGET, PREFIX + "VitrailTerrainDrawMixin"));
    }

    @Test
    void selectionAndPluginDoNotLoadGameOptionalRendererOrConfigClasses() throws Exception {
        URL classes = LaunchHooks.class.getProtectionDomain().getCodeSource().getLocation();
        var denied = new ArrayList<String>();
        try (var loader = new URLClassLoader(new URL[]{classes}, getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    if (name.startsWith("net.minecraft.") || name.startsWith("net.caffeinemc.") || name.startsWith("dev.vitrail.")
                            || (name.startsWith("dev.vultrum.") && !name.startsWith("dev.vultrum.LaunchHooks")
                            && !name.equals("dev.vultrum.VultrumMixinPlugin"))) {
                        denied.add(name);
                        throw new ClassNotFoundException("Early-loading dependency: " + name);
                    }
                    if (name.startsWith("dev.vultrum.")) {
                        var loaded = findLoadedClass(name);
                        if (loaded == null) loaded = findClass(name);
                        if (resolve) resolveClass(loaded);
                        return loaded;
                    }
                    return super.loadClass(name, resolve);
                }
            }
        }) {
            var type = loader.loadClass("dev.vultrum.LaunchHooks");
            var constructor = type.getDeclaredConstructor(Map.class, String.class, String.class, BooleanSupplier.class);
            constructor.setAccessible(true);
            Object hooks = constructor.newInstance(mods("0.9.3", "0.13.0", "2.8g", false), "fixture", "TRUE", (BooleanSupplier) () -> true);
            var pluginType = loader.loadClass("dev.vultrum.VultrumMixinPlugin");
            var pluginConstructor = pluginType.getDeclaredConstructor(type);
            pluginConstructor.setAccessible(true);
            Object plugin = pluginConstructor.newInstance(hooks);
            var applies = pluginType.getMethod("shouldApplyMixin", String.class, String.class);
            assertEquals(true, applies.invoke(plugin, VITRAIL_TARGET, PREFIX + "VitrailTerrainDrawMixin"));
            assertEquals(true, applies.invoke(plugin, VANILLA_TARGET, LaunchHooks.PUNCHY_HAND_LOOKUP));
            assertEquals(true, applies.invoke(plugin, VANILLA_TARGET, PREFIX + "ClientLightTimelineMixin"));
            pluginType.getMethod("onLoad", String.class).invoke(plugin, PREFIX);
            assertEquals(List.of(), denied);
        }
    }
}
