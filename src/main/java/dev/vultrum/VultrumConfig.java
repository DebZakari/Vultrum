package dev.vultrum;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/**
 * User settings in {@code config/vultrum.properties}, read once at launch. A missing file is written with the
 * defaults, and a file from an older version is rewritten with the settings added since, keeping its values.
 * Changes made in game ({@link VultrumOptions}) are written at once and take effect at the next launch.
 */
public record VultrumConfig(boolean shadowFaceCull, boolean occlusionCull, boolean sunlessShadow, boolean packetBudget,
                            boolean arenaCap, boolean unloadBudget, boolean reclaimThread, boolean reloadRegistration,
                            boolean punchyHandCache) {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vultrum");
    public static final VultrumConfig DEFAULTS = new VultrumConfig(true, true, false, true, true, true, true, true, false);
    static final String SHADOW_FACE_CULL = "shadow-face-cull", OCCLUSION_CULL = "occlusion-cull", SUNLESS_SHADOW = "sunless-shadow",
            PACKET_BUDGET = "packet-budget", ARENA_CAP = "arena-cap", UNLOAD_BUDGET = "unload-budget", RECLAIM_THREAD = "reclaim-thread",
            RELOAD_REGISTRATION = "reload-registration", PUNCHY_HAND_CACHE = "punchy-hand-cache";
    static final List<String> KEYS = List.of(SHADOW_FACE_CULL, OCCLUSION_CULL, SUNLESS_SHADOW, PACKET_BUDGET, ARENA_CAP, UNLOAD_BUDGET,
            RECLAIM_THREAD, RELOAD_REGISTRATION, PUNCHY_HAND_CACHE);
    private static VultrumConfig current, next;

    /** Existing callers leave the optional compatibility cache off. */
    public VultrumConfig(boolean shadowFaceCull, boolean occlusionCull, boolean sunlessShadow, boolean packetBudget,
                         boolean arenaCap, boolean unloadBudget, boolean reclaimThread, boolean reloadRegistration) {
        this(shadowFaceCull, occlusionCull, sunlessShadow, packetBudget, arenaCap, unloadBudget, reclaimThread, reloadRegistration, false);
    }

    /** The game's settings, read on first use. Mixins may need them before the client initializer runs. */
    public static synchronized VultrumConfig get() {
        if (current == null) next = current = load(file());
        return current;
    }

    /** The settings the next launch reads: this launch's, plus changes made in game since. */
    public static synchronized VultrumConfig next() {
        get();
        return next;
    }

    /** Writes settings for the next launch. This launch keeps {@link #get()}. */
    public static synchronized void saveNext(VultrumConfig config) {
        get();
        next = config;
        write(file(), config);
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("vultrum.properties");
    }

    public VultrumConfig withShadowFaceCull(boolean on) {
        return new VultrumConfig(on, occlusionCull, sunlessShadow, packetBudget, arenaCap, unloadBudget, reclaimThread, reloadRegistration, punchyHandCache);
    }

    public VultrumConfig withOcclusionCull(boolean on) {
        return new VultrumConfig(shadowFaceCull, on, sunlessShadow, packetBudget, arenaCap, unloadBudget, reclaimThread, reloadRegistration, punchyHandCache);
    }

    public VultrumConfig withSunlessShadow(boolean on) {
        return new VultrumConfig(shadowFaceCull, occlusionCull, on, packetBudget, arenaCap, unloadBudget, reclaimThread, reloadRegistration, punchyHandCache);
    }

    public VultrumConfig withPacketBudget(boolean on) {
        return new VultrumConfig(shadowFaceCull, occlusionCull, sunlessShadow, on, arenaCap, unloadBudget, reclaimThread, reloadRegistration, punchyHandCache);
    }

    public VultrumConfig withArenaCap(boolean on) {
        return new VultrumConfig(shadowFaceCull, occlusionCull, sunlessShadow, packetBudget, on, unloadBudget, reclaimThread, reloadRegistration, punchyHandCache);
    }

    public VultrumConfig withUnloadBudget(boolean on) {
        return new VultrumConfig(shadowFaceCull, occlusionCull, sunlessShadow, packetBudget, arenaCap, on, reclaimThread, reloadRegistration, punchyHandCache);
    }

    public VultrumConfig withReclaimThread(boolean on) {
        return new VultrumConfig(shadowFaceCull, occlusionCull, sunlessShadow, packetBudget, arenaCap, unloadBudget, on, reloadRegistration, punchyHandCache);
    }

    public VultrumConfig withReloadRegistration(boolean on) {
        return new VultrumConfig(shadowFaceCull, occlusionCull, sunlessShadow, packetBudget, arenaCap, unloadBudget, reclaimThread, on, punchyHandCache);
    }

    public VultrumConfig withPunchyHandCache(boolean on) {
        return new VultrumConfig(shadowFaceCull, occlusionCull, sunlessShadow, packetBudget, arenaCap, unloadBudget, reclaimThread, reloadRegistration, on);
    }

    public static VultrumConfig load(Path file) {
        if (!Files.exists(file)) {
            write(file, DEFAULTS);
            return DEFAULTS;
        }
        var properties = new Properties();
        try (Reader in = Files.newBufferedReader(file)) {
            properties.load(in);
        } catch (IOException | IllegalArgumentException e) {
            LOGGER.warn("Can't read {}, using defaults", file, e);
            return DEFAULTS;
        }
        var config = new VultrumConfig(flag(properties, SHADOW_FACE_CULL, DEFAULTS.shadowFaceCull),
                flag(properties, OCCLUSION_CULL, DEFAULTS.occlusionCull), flag(properties, SUNLESS_SHADOW, DEFAULTS.sunlessShadow),
                flag(properties, PACKET_BUDGET, DEFAULTS.packetBudget), flag(properties, ARENA_CAP, DEFAULTS.arenaCap),
                flag(properties, UNLOAD_BUDGET, DEFAULTS.unloadBudget), flag(properties, RECLAIM_THREAD, DEFAULTS.reclaimThread),
                flag(properties, RELOAD_REGISTRATION, DEFAULTS.reloadRegistration), flag(properties, PUNCHY_HAND_CACHE, DEFAULTS.punchyHandCache));
        if (!KEYS.stream().allMatch(properties::containsKey)) write(file, config);
        return config;
    }

    private static boolean flag(Properties properties, String key, boolean fallback) {
        String value = properties.getProperty(key);
        if (value == null) return fallback;
        value = value.trim();
        if (value.equalsIgnoreCase("true")) return true;
        if (value.equalsIgnoreCase("false")) return false;
        LOGGER.warn("Vultrum config: {}={} is not true or false, using {}", key, value, fallback);
        return fallback;
    }

    static void write(Path file, VultrumConfig config) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, """
                    # Vultrum settings, read at launch.

                    # Draw only the light-facing sides of solid blocks into the shadow map (Vitrail shader packs).
                    # Shadows look the same. Paused for packs that voxelise their shadow pass.
                    %s=%b

                    # Skip terrain hidden behind other terrain, found on the GPU each frame (Vitrail shader packs).
                    # Turns itself off in open scenes, where it would save less than it costs.
                    %s=%b

                    # Leave terrain that sunlight can't reach out of the shadow map (Vitrail shader packs).
                    # About 1%% more FPS in tests, and shadows looked the same. Paused for packs that voxelise their shadow pass.
                    %s=%b

                    # Handle at most 1 ms of the server's packets a frame, so a burst of new chunks spreads over
                    # several frames and flying into new terrain stutters less. Packets held back for 50 ms are all
                    # handled at the next frame.
                    %s=%b

                    # Limit each new buffer Sodium creates for terrain to 96 MiB, unless its terrain needs more, so
                    # creating one doesn't stall the GPU for up to 5 ms. Uses more video memory: up to 0.6 GB when
                    # flying fast at 32 chunks.
                    %s=%b

                    # Remove about 0.25 ms of chunks a frame from Sodium's terrain (more while thousands wait), so the
                    # chunks that leave the view aren't all removed in one frame. Flying at 32 chunks stuttered about
                    # a third less often in tests, and the longest frame after a long teleport was less than half as
                    # long. Chunks waiting to be removed keep their video memory a little longer.
                    %s=%b

                    # Let a thread of its own tidy up after Sodium's discarded terrain buffers. Without it, the frame
                    # after a garbage collection tidies up tens of thousands of them at once: up to 33 ms in tests.
                    %s=%b

                    # Register about 2 ms of already-loaded columns a frame after a terrain reload, instead of all
                    # of them in one frame. New chunk arrivals are handled immediately as before.
                    %s=%b

                    # Reduce Punchy's first-person animation overhead when Iris is absent. Requires Punchy
                    # 2.8f-hotfix or 2.8g with Vitrail. Hand animations are preserved.
                    %s=%b
                    """.formatted(SHADOW_FACE_CULL, config.shadowFaceCull, OCCLUSION_CULL, config.occlusionCull,
                    SUNLESS_SHADOW, config.sunlessShadow, PACKET_BUDGET, config.packetBudget, ARENA_CAP, config.arenaCap,
                    UNLOAD_BUDGET, config.unloadBudget, RECLAIM_THREAD, config.reclaimThread,
                    RELOAD_REGISTRATION, config.reloadRegistration, PUNCHY_HAND_CACHE, config.punchyHandCache));
        } catch (IOException e) {
            LOGGER.warn("Can't write {}", file, e);
        }
    }
}
