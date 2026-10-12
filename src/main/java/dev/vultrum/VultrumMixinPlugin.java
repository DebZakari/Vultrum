package dev.vultrum;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applies mixins into Sodium and Vitrail only for the versions they were written against, so an update
 * switches the features built on them off instead of crashing the game. The benchmark's own mixins apply only
 * to benchmark runs.
 */
public final class VultrumMixinPlugin implements IMixinConfigPlugin {
    /** A mod version range Vultrum's hooks were written against. */
    record Range(String modId, String range) {}

    static final Range SODIUM = new Range("sodium", ">=0.9.3-alpha.1 <0.9.4-alpha");
    static final Range VITRAIL = new Range("vitrail", ">=0.12.0-alpha <0.13.0-alpha");
    /**
     * Mixins into each package need every listed mod installed and in range. The Vitrail hooks feed the
     * shadow face cull, which uses Sodium's API, so they need Sodium in range too.
     */
    private static final Map<String, List<Range>> NEEDS = Map.of(
            "net.caffeinemc.mods.sodium.", List.of(SODIUM),
            "dev.vitrail.", List.of(SODIUM, VITRAIL));
    /**
     * Mixins that only time the frame loop and terrain setup for the benchmark (and drive it), applied only to
     * runs started with -Dvultrum.bench, as Bench.init checks. Not Bench itself: it loads Minecraft classes.
     */
    private static final Set<String> BENCH_ONLY = Set.of("dev.vultrum.mixin.ClientLevelMixin", "dev.vultrum.mixin.MinecraftMixin",
            "dev.vultrum.mixin.RenderSectionManagerMixin", "dev.vultrum.mixin.SodiumSetupPhasesMixin",
            "dev.vultrum.mixin.SodiumNativeBufferMixin");

    // Not VultrumClient.LOGGER: mixin plugins load before mod initialisation, and that class needn't load this early.
    private static final Logger LOGGER = LoggerFactory.getLogger("Vultrum");

    /** Whether mixins into {@code className} (or a package prefix) apply: every mod its package needs is installed and in range. */
    public static boolean supports(String className) {
        for (var entry : NEEDS.entrySet())
            if (className.startsWith(entry.getKey())) return entry.getValue().stream().allMatch(VultrumMixinPlugin::installed);
        return true;
    }

    private static boolean installed(Range range) {
        var mod = FabricLoader.getInstance().getModContainer(range.modId());
        return mod.isPresent() && inRange(mod.get().getMetadata().getVersion(), range.range());
    }

    static boolean inRange(Version version, String range) {
        try {
            return VersionPredicate.parse(range).test(version);
        } catch (VersionParsingException e) {
            throw new IllegalArgumentException(range, e);
        }
    }

    @Override
    public void onLoad(String mixinPackage) {
        for (var range : List.of(SODIUM, VITRAIL)) {
            var mod = FabricLoader.getInstance().getModContainer(range.modId());
            if (mod.isEmpty()) continue;
            Version version = mod.get().getMetadata().getVersion();
            if (!inRange(version, range.range()))
                LOGGER.warn("Vultrum supports {} {}, found {}: features that hook into it are off", range.modId(), range.range(), version.getFriendlyString());
        }
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (BENCH_ONLY.contains(mixinClassName)) {
            String scenario = System.getProperty("vultrum.bench");
            if (scenario == null || scenario.isBlank()) return false;
        }
        return supports(targetClassName);
    }

    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
