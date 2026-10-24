package dev.vultrum;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Launch-time hook selection shared by the mixin, settings and runtime adapters.
 * Only loader version metadata and launch properties are needed; no game or optional renderer classes load here.
 * Device capabilities, pack state and live failures remain with their runtime owners.
 */
public final class LaunchHooks {
    static final String PUNCHY_HAND_LOOKUP = "dev.vultrum.mixin.PunchyHandLookupMixin";
    private record Range(String modId, String range) {
        boolean includes(Version version) {
            try {
                return version != null && VersionPredicate.parse(range).test(version);
            } catch (VersionParsingException e) {
                throw new IllegalArgumentException(range, e);
            }
        }
    }
    private static final Range SODIUM = new Range("sodium", ">=0.9.3-alpha.1 <0.9.4-alpha");
    private static final Range VITRAIL = new Range("vitrail", ">=0.12.0-alpha <0.14.0-alpha");
    private static final Set<String> PUNCHY_VERSIONS = Set.of("2.8f-hotfix", "2.8g");
    private static final Set<String> BENCH_ONLY = Set.of("dev.vultrum.mixin.ClientLevelMixin", "dev.vultrum.mixin.MinecraftMixin",
            "dev.vultrum.mixin.RenderSectionManagerMixin", "dev.vultrum.mixin.SodiumSetupPhasesMixin",
            "dev.vultrum.mixin.SodiumNativeBufferMixin", "dev.vultrum.mixin.SodiumBuildSubmitMixin");
    private static final Set<String> TIMELINE_ONLY = Set.of("dev.vultrum.mixin.ClientChunkTimelineMixin",
            "dev.vultrum.mixin.ClientChunkStorageTimelineMixin", "dev.vultrum.mixin.ClientLightTimelineMixin",
            "dev.vultrum.mixin.ClientChunkLightTimelineMixin", "dev.vultrum.mixin.SodiumDirtyTimelineMixin");
    private static final Set<String> TIMELINE_FULL_ONLY = Set.of("dev.vultrum.mixin.ClientLightTimelineMixin",
            "dev.vultrum.mixin.ClientChunkLightTimelineMixin", "dev.vultrum.mixin.SodiumDirtyTimelineMixin");

    public enum TimelineMode { OFF, CHUNKS, FULL }

    private final Map<String, Version> mods;
    private final boolean benchmark;
    private final TimelineMode timeline;
    private final BooleanSupplier punchyRequested;

    LaunchHooks(Map<String, Version> mods, String scenario, String timeline, BooleanSupplier punchyRequested) {
        this.mods = Map.copyOf(mods);
        this.benchmark = scenario != null && !scenario.isBlank();
        // Match the timeline logger's existing property semantics, including a present but blank scenario.
        String mode = scenario != null && timeline != null ? timeline.toLowerCase(Locale.ROOT) : "";
        this.timeline = switch (mode) {
            case "true" -> TimelineMode.FULL;
            case "chunks" -> TimelineMode.CHUNKS;
            default -> TimelineMode.OFF;
        };
        this.punchyRequested = Objects.requireNonNull(punchyRequested);
    }

    /** One immutable observation of launch metadata; next-launch settings never change this selection. */
    public static LaunchHooks get() { return Live.SELECTION; }

    public boolean sodium() { return SODIUM.includes(mods.get(SODIUM.modId())); }
    public boolean vitrail() { return sodium() && VITRAIL.includes(mods.get(VITRAIL.modId())); }

    /** Availability, independently of the opt-in: the settings adapter must still allow enabling it. */
    public boolean punchyHandLookupAvailable() {
        var version = mods.get("punchy");
        return version != null && PUNCHY_VERSIONS.contains(version.getFriendlyString())
                && vitrail() && !mods.containsKey("iris");
    }

    public TimelineMode timeline() { return timeline; }

    boolean applies(String target, String mixin) {
        if (PUNCHY_HAND_LOOKUP.equals(mixin))
            return punchyHandLookupAvailable() && punchyRequested.getAsBoolean();
        if ((BENCH_ONLY.contains(mixin) || TIMELINE_ONLY.contains(mixin)) && !benchmark) return false;
        if (TIMELINE_FULL_ONLY.contains(mixin) && timeline != TimelineMode.FULL) return false;
        if (TIMELINE_ONLY.contains(mixin) && timeline == TimelineMode.OFF) return false;
        if (target.startsWith("net.caffeinemc.mods.sodium.")) return sodium();
        if (target.startsWith("dev.vitrail.")) return vitrail();
        return true;
    }

    List<String> warnings() {
        var result = new ArrayList<String>();
        for (var range : List.of(SODIUM, VITRAIL)) {
            Version version = mods.get(range.modId());
            if (version != null && !range.includes(version))
                result.add("Vultrum supports " + range.modId() + " " + range.range() + ", found "
                        + version.getFriendlyString() + ": features that hook into it are off");
        }
        return List.copyOf(result);
    }

    /** Loader adapter; kept lazy so isolated selection tests and early consumers need no initialized game. */
    private static final class Live {
        static final LaunchHooks SELECTION = observe();
        private static LaunchHooks observe() {
            var loader = FabricLoader.getInstance();
            var mods = new HashMap<String, Version>();
            for (var id : List.of("sodium", "vitrail", "punchy", "iris"))
                loader.getModContainer(id).ifPresent(mod -> mods.put(id, mod.getMetadata().getVersion()));
            return new LaunchHooks(mods, System.getProperty("vultrum.bench"), System.getProperty("vultrum.bench.timeline"),
                    () -> VultrumConfig.get().punchyHandCache());
        }
    }
}
