package dev.vultrum.bench;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.vultrum.VultrumClient;
import dev.vultrum.cull.GpuOcclusionCull;
import dev.vultrum.cull.ShadowPass;
import dev.vultrum.cull.ShadowRegion;
import dev.vultrum.mixin.HudAccessor;
import dev.vultrum.net.PacketBudget;
import dev.vultrum.upload.ReclaimThread;
import dev.vultrum.upload.UnloadBudget;
import net.caffeinemc.mods.sodium.client.config.ConfigManager;
import net.caffeinemc.mods.sodium.client.config.structure.OptionPage;
import net.caffeinemc.mods.sodium.client.gpu.device.backend.DrawBackend;
import net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.lwjgl.sdl.SDLHints;
import org.lwjgl.sdl.SDLVideo;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Scripted benchmark, driven from the client tick. Enabled by -Dvultrum.bench=<scenario.json>.
 * It opens (or creates) the scenario world, locates the biomes and structures the viewpoints ask for,
 * then for each render distance and viewpoint it teleports,
 * waits for terrain to finish, freezes ticking, waits for Sodium's builds to go quiet if the scenario asks,
 * warms up, records frames and takes a screenshot.
 * Viewpoints with motion sweep the camera while warming up and recording, then hold pose A, jump to
 * pose B and take the screenshot there. Flying viewpoints fly straight ahead and take it at the end of
 * the route. Setup commands run once the camera is placed, cleanup commands once the case is done, and a viewpoint's
 * before actions (F3+A, a resize, a rejoin...) before its case (see {@link Scenario}). A visual check shoots each
 * static case again with Vultrum's culls off, on and off.
 * Each case also times its load, from its teleport until Sodium has built the terrain around the new spot, and the
 * run times the world join the same way, from opening the world. The join loads at the first case's render distance.
 * Finally it writes results.json and quits the game.
 */
public final class Bench {
    private enum Phase { WAIT_TITLE, WAIT_WORLD, PREPARE, LOCATE, PLACE, REJOIN, WAIT_COLUMN, WAIT_TERRAIN, WAIT_BUILDS, WARMUP, RECORD, DRAIN, HOLD_A, HOLD_B, VISUAL, SETTINGS, QUIT }
    private enum Sweep { OFF, MOVING, AT_A, AT_B, FLYING }

    private static final double WORLD_TIMEOUT_S = 600, LOCATE_TIMEOUT_S = 300, COLUMN_TIMEOUT_S = 120, TERRAIN_TIMEOUT_S = 300;
    private static final double DRAIN_TIMEOUT_S = 2, HOLD_MIN_S = 1, HOLD_TIMEOUT_S = 10, FLY_END_TIMEOUT_S = 60, QUIT_TIMEOUT_S = 5;
    private static final double TERRAIN_MIN_S = 5;
    // The longest a case waits for Sodium's builds to go quiet (Scenario.quietBuildsSeconds), after the terrain wait.
    static final double BUILDS_TIMEOUT_S = 60;
    // A flight goes on this long after recording should end, so recording never sees it stop.
    private static final double FLY_OVERRUN_S = 1;
    private static final int STABLE_TICKS = 60, MAX_ATTEMPTS = 3;
    // The window a resize action leaves, in screen pixels.
    private static final int RESIZE_WIDTH = 1280, RESIZE_HEIGHT = 720;
    // A load ends once terrain has stayed complete this long, at the first of these ticks.
    private static final int LOAD_TICKS = 20;
    // The visual check's shots after the case's own (on1), each this long after the culls were switched, with terrain built.
    private static final List<String> VISUAL_SHOTS = List.of("off1", "on2", "off2");
    private static final double VISUAL_SETTLE_S = 2;
    private static final int NOON = 6000;
    private static Bench instance;

    private final Scenario scenario;
    private final Path out;
    private final String label;
    private final List<Case> cases = new ArrayList<>();
    private final List<Map<String, Object>> results = new ArrayList<>();
    private final Map<String, CompletableFuture<BlockPos>> located = new LinkedHashMap<>();
    private Phase phase = Phase.WAIT_TITLE;
    private long phaseStart = System.nanoTime();
    // actionsDone: the current case's before actions run so far. A rejoin leaves the world and comes back to the next.
    private int caseIndex, attempt, stableTicks, lastLoaded = -1, actionsDone;
    // unfocused: the window hadn't focus when recording started, which a re-recording can't change (see focused).
    private boolean focusLost, unfocused, moved, terrainTimedOut, buildsTimedOut;
    private double terrainWaitS, buildsWaitS;
    // Sodium's build submissions (Builds) when warm-up and recording started, and during recording.
    private long buildsAtWarmup, buildsAtRecord, buildsRecorded;
    private double[] pose;
    private BlockPos origin;
    private Map<String, Object> cullStats, packetStats;
    // The reclaim thread's batches since the current load started, and the recording window.
    private final List<ReclaimThread.Batch> reclaimBatches = new ArrayList<>();
    private long recordStart, recordEnd;
    // Load timing (the join, or a case's teleport): its start, and the streak of ticks with terrain complete.
    private boolean loadRunning;
    private long loadStart, loadStreakStart;
    private int loadTicks, loadStreakFrames, loadStreakPending, targetChunkX, targetChunkZ;
    private Map<String, Object> loadStreakBudget;
    private double joinLevelS = -1;
    private Screen worldWaitScreen;
    // The current case's load (kept from its first attempt) and the join's. Null until measured.
    private Map<String, Object> caseLoad, join;
    // The current case's setup placeholders, kept for its cleanup: the setup changes the ground height. Null if none ran.
    private Map<String, String> setupVars;
    // The ground the current case was first placed on. A re-record reuses it: the client may not have seen the cleanup yet.
    private Double caseGround;
    // Completes once the server has run every command queued before the quit (the last cleanup).
    private CompletableFuture<Void> serverCaughtUp;
    // A multiplayer scenario's server console. Null in singleplayer.
    private Rcon rcon;
    // The settings check: Vultrum's pages in Sodium's video settings, the one shown, and what each showed.
    private final List<OptionPage> settingsPages = new ArrayList<>();
    private final List<Map<String, Object>> settings = new ArrayList<>();
    private int settingsPage;
    // The visual check: the next of VISUAL_SHOTS.
    private int visualShot;
    // Camera motion: pose B (the viewpoint's) or a flight's start, and when the sweep started.
    private Sweep sweep = Sweep.OFF;
    private double[] anchor;
    private long sweepStart;

    private record Case(int renderDistance, Scenario.Viewpoint viewpoint) {
        String id() { return viewpoint.name() + "-rd" + renderDistance; }
    }

    private Bench(Scenario scenario, Path out, String label) {
        this.scenario = scenario;
        this.out = out;
        this.label = label;
        for (int rd : scenario.renderDistances())
            for (var viewpoint : scenario.viewpoints()) cases.add(new Case(rd, viewpoint));
    }

    public static void init() {
        String scenarioPath = System.getProperty("vultrum.bench");
        if (scenarioPath == null || scenarioPath.isBlank()) return;
        var scenario = Scenario.load(Path.of(scenarioPath));
        String label = System.getProperty("vultrum.bench.label", "run");
        Path out = Path.of(System.getProperty("vultrum.bench.out",
                FabricLoader.getInstance().getGameDir().resolve("vultrum-bench").resolve(label).toString()));
        instance = new Bench(scenario, out.toAbsolutePath(), label);
        Timeline.open(instance.out);
        // The visual check shows the occlusion cull wherever it could run, not only where its gate has it on.
        if (scenario.visualCheck()) GpuOcclusionCull.override(true);
        FrameTimer.enable();
        ReclaimThread.client().trackBatches();
        VultrumClient.LOGGER.info("[bench] scenario={} cases={} out={}", scenarioPath, instance.cases.size(), instance.out);
    }

    public static void tick(Minecraft mc) {
        if (instance == null) return;
        try {
            instance.step(mc);
        } catch (RuntimeException e) {
            VultrumClient.LOGGER.error("[bench] aborted", e);
            instance.abort(mc, e);
        }
    }

    /** Writes what was measured, undoes the case's setup so the shared world isn't saved with it, then quits. */
    private void abort(Minecraft mc, RuntimeException e) {
        if (phase != Phase.QUIT) writeResults(mc, "error: " + e);
        if (phase == Phase.QUIT || !inWorld(mc)) {
            stop(mc);
            return;
        }
        sweep = Sweep.OFF;
        try {
            command(mc, "tick unfreeze");
            cleanUp(mc);
        } catch (RuntimeException cleanupError) {
            VultrumClient.LOGGER.error("[bench] cleanup failed", cleanupError);
        }
        quit(mc);
    }

    /** Quits once the server has run the commands queued so far, so the world is saved after the last cleanup. */
    private void quit(Minecraft mc) {
        serverCaughtUp = rcon != null ? rcon.caughtUp() : mc.getSingleplayerServer().submit(() -> { });
        enter(Phase.QUIT);
    }

    private void stop(Minecraft mc) {
        VitrailPack.restore();
        if (rcon != null) {
            try {
                rcon.close();
            } catch (IOException e) {
                VultrumClient.LOGGER.warn("[bench] closing RCON", e);
            }
        }
        instance = null;
        mc.stop();
    }

    /** In a world, with its server: the integrated one, or a multiplayer server's connection. */
    private boolean inWorld(Minecraft mc) {
        return rcon != null ? mc.getConnection() != null : mc.getSingleplayerServer() != null;
    }

    private void step(Minecraft mc) {
        switch (phase) {
            case WAIT_TITLE -> {
                // Resource loading finished and some menu (title or first-launch onboarding) is up.
                if (mc.gui.overlay() == null && mc.gui.screen() != null && mc.level == null && seconds() > 1) {
                    applyOptions(mc);
                    openWorld(mc);
                    startLoad();
                    enter(Phase.WAIT_WORLD);
                }
            }
            case WAIT_WORLD -> {
                if (mc.level != null && mc.player != null && mc.gui.screen() == null && inWorld(mc)) {
                    if (joinLevelS < 0) {
                        joinLevelS = seconds();
                        // As in every case's load: the HUD (a minimap, in a modded game) costs frame time.
                        ((HudAccessor) mc.gui.hud).vultrum$setHidden(true);
                    }
                    // The terrain around wherever the previous run quit.
                    if (join == null && (loaded(mc, mc.options.renderDistance().get(), true) || seconds() - joinLevelS > TERRAIN_TIMEOUT_S)) {
                        join = new LinkedHashMap<>();
                        join.put("levelS", round(joinLevelS));
                        var load = endLoad("join");
                        join.put("terrainS", load.remove("seconds"));
                        join.putAll(load);
                        VultrumClient.LOGGER.info("[bench] join {}", join);
                    }
                    // The join ends on the inner disc, as every load does, while the server still sends the outer ring. The
                    // first case's teleport would race it: the client keeps counting the old disc's chunks it can no longer
                    // drop (out of range, never drawn), so how much of the ring had arrived would change loadedChunks.
                    if (join != null && (terrainSettled(mc, mc.options.renderDistance().get()) || seconds() > TERRAIN_TIMEOUT_S)) {
                        if (seconds() > TERRAIN_TIMEOUT_S) VultrumClient.LOGGER.warn("[bench] terrain not settled {} s after opening the world", TERRAIN_TIMEOUT_S);
                        enter(Phase.PREPARE);
                    }
                } else {
                    // A prompt the bench doesn't answer (a confirm or backup screen) stalls the open: name each screen.
                    var screen = mc.gui.screen();
                    if (screen != worldWaitScreen) {
                        worldWaitScreen = screen;
                        VultrumClient.LOGGER.info("[bench] waiting for the world on {}", describe(screen));
                    }
                    // A refused join (a whitelist, a version mismatch) says why on this screen and goes nowhere.
                    if (screen instanceof DisconnectedScreen)
                        throw new IllegalStateException("The server refused the join: " + describe(screen));
                    if (seconds() > WORLD_TIMEOUT_S)
                        throw new IllegalStateException("World did not load in " + WORLD_TIMEOUT_S + " s, on " + describe(screen));
                }
            }
            case PREPARE -> {
                prepareWorld(mc);
                enter(Phase.LOCATE);
            }
            case LOCATE -> {
                if (located.values().stream().allMatch(CompletableFuture::isDone)) {
                    for (var entry : located.entrySet())
                        if (entry.getValue().join() == null) throw new IllegalStateException("Nothing found for locate " + entry.getKey());
                    enter(Phase.PLACE);
                } else if (seconds() > LOCATE_TIMEOUT_S) {
                    throw new IllegalStateException("Locating took over " + LOCATE_TIMEOUT_S + " s");
                }
            }
            case PLACE -> {
                var current = cases.get(caseIndex);
                var actions = current.viewpoint().before();
                while (attempt == 0 && actions != null && actionsDone < actions.size())
                    if (act(mc, actions.get(actionsDone++))) return;
                if (mc.options.renderDistance().get() != current.renderDistance()) {
                    mc.options.renderDistance().set(current.renderDistance());
                    // The server caps each player at the view distance the client last sent, so a raise
                    // only takes effect once the client re-sends its options.
                    mc.options.broadcastOptions();
                }
                var viewpoint = current.viewpoint();
                var base = base(viewpoint);
                // A re-record keeps the first attempt's load: its spot is loaded by now.
                if (attempt == 0) {
                    caseLoad = null;
                    targetChunkX = (base.getX() + viewpoint.x()) >> 4;
                    targetChunkZ = (base.getZ() + viewpoint.z()) >> 4;
                    startLoad();
                }
                command(mc, "time set " + (viewpoint.time() != null ? viewpoint.time() : NOON));
                teleport(mc, base.getX() + viewpoint.x() + 0.5, viewpoint.y() != null ? viewpoint.y() : 200,
                        base.getZ() + viewpoint.z() + 0.5, viewpoint);
                // Setup commands wait for the column, which the server must have loaded to change.
                boolean commands = viewpoint.setup() != null || viewpoint.cleanup() != null;
                enter(viewpoint.y() != null && !commands ? Phase.WAIT_TERRAIN : Phase.WAIT_COLUMN);
            }
            case REJOIN -> {
                // The world has closed (and in singleplayer saved) once a menu is up: the title screen, or the server list.
                // The join then goes through PREPARE and LOCATE as the first did (both keep what they found), back to
                // PLACE and the next action.
                if (mc.level == null && mc.getConnection() == null && mc.gui.overlay() == null && mc.gui.screen() != null && seconds() > 1) {
                    openWorld(mc);
                    enter(Phase.WAIT_WORLD);
                } else if (seconds() > WORLD_TIMEOUT_S) {
                    throw new IllegalStateException("Leaving the world took over " + WORLD_TIMEOUT_S + " s, on " + describe(mc.gui.screen()));
                }
            }
            case WAIT_COLUMN -> {
                trackLoad(mc, false);
                var viewpoint = cases.get(caseIndex).viewpoint();
                var base = base(viewpoint);
                int x = base.getX() + viewpoint.x(), z = base.getZ() + viewpoint.z();
                if (inDimension(mc, viewpoint) && mc.level.getChunkSource().hasChunk(x >> 4, z >> 4)) {
                    if (viewpoint.y() != null) {
                        setUp(mc, x + 0.5, viewpoint.y(), z + 0.5, viewpoint);
                        enter(Phase.WAIT_TERRAIN);
                        return;
                    }
                    var heightmap = viewpoint.ignoreLeaves() ? Heightmap.Types.MOTION_BLOCKING_NO_LEAVES : Heightmap.Types.MOTION_BLOCKING;
                    if (caseGround == null) caseGround = (double) mc.level.getHeight(heightmap, x, z);
                    double ground = caseGround;
                    teleport(mc, x + 0.5, ground + viewpoint.aboveGround(), z + 0.5, viewpoint);
                    setUp(mc, x + 0.5, ground, z + 0.5, viewpoint);
                    enter(Phase.WAIT_TERRAIN);
                } else if (seconds() > COLUMN_TIMEOUT_S) {
                    throw new IllegalStateException("Chunk at " + x + "," + z + " did not load");
                }
            }
            case WAIT_TERRAIN -> {
                boolean settled = inDimension(mc, cases.get(caseIndex).viewpoint()) && terrainSettled(mc);
                terrainTimedOut = seconds() > TERRAIN_TIMEOUT_S;
                trackLoad(mc, settled || terrainTimedOut);
                if (settled || terrainTimedOut) {
                    terrainWaitS = seconds();
                    command(mc, "tick freeze");
                    buildsWaitS = 0;
                    buildsTimedOut = false;
                    if (scenario.quietBuildsSeconds() > 0) enter(Phase.WAIT_BUILDS);
                    else startWarmup(mc);
                }
            }
            case WAIT_BUILDS -> {
                // Ticking is frozen, so no block or light change asks for a build: what's left is the walk's own. The
                // terrain must still be settled at its end: a chunk that arrives or goes meanwhile starts its count over.
                boolean quiet = terrainSettled(mc) && Builds.quietSeconds() >= scenario.quietBuildsSeconds();
                buildsTimedOut = !quiet && seconds() > BUILDS_TIMEOUT_S;
                if (quiet || buildsTimedOut) {
                    buildsWaitS = seconds();
                    if (buildsTimedOut) VultrumClient.LOGGER.warn("[bench] {} builds or terrain not quiet after {} s", cases.get(caseIndex).id(), BUILDS_TIMEOUT_S);
                    startWarmup(mc);
                }
            }
            case WARMUP -> {
                if (seconds() >= scenario.warmupSeconds()) {
                    focusLost = false;
                    unfocused = !focused(mc);
                    pose = pose(mc);
                    FrameTimer.startRecording();
                    recordStart = System.nanoTime();
                    buildsAtRecord = Builds.count();
                    GpuOcclusionCull.resetStats();
                    PacketBudget.resetStats();
                    ShadowRegion.resetStats();
                    // Markers let log-based reports (Vitrail's pass timings) be matched to the recording window.
                    VultrumClient.LOGGER.info("[bench] record start {}", cases.get(caseIndex).id());
                    enter(Phase.RECORD);
                }
            }
            case RECORD -> {
                focusLost |= (!unfocused && !focused(mc)) || mc.gui.screen() != null;
                if (seconds() >= scenario.recordSeconds()) {
                    FrameTimer.stopRecording();
                    recordEnd = System.nanoTime();
                    buildsRecorded = Builds.count() - buildsAtRecord;
                    cullStats = GpuOcclusionCull.stats();
                    packetStats = PacketBudget.stats();
                    VultrumClient.LOGGER.info("[bench] record end {}", cases.get(caseIndex).id());
                    enter(Phase.DRAIN);
                }
            }
            case DRAIN -> {
                if (!FrameTimer.drained() && seconds() < DRAIN_TIMEOUT_S) return;
                moved = sweep == Sweep.OFF && !samePose(pose, pose(mc));
                if ((focusLost || moved) && ++attempt < MAX_ATTEMPTS) {
                    VultrumClient.LOGGER.warn("[bench] {} {}; re-recording ({}/{})", cases.get(caseIndex).id(),
                            moved ? "camera moved" : "lost window focus", attempt + 1, MAX_ATTEMPTS);
                    // A flight has left its start, so it starts over from there.
                    if (moved || sweep == Sweep.FLYING) {
                        sweep = Sweep.OFF;
                        command(mc, "tick unfreeze");
                        cleanUp(mc);
                        enter(Phase.PLACE);
                    } else {
                        warmUp(mc);
                    }
                    return;
                }
                if (sweep == Sweep.FLYING) {
                    // The screenshot waits for the end of the route to be built.
                    enter(Phase.HOLD_B);
                    return;
                }
                if (sweep != Sweep.OFF) {
                    sweep = Sweep.AT_A;
                    enter(Phase.HOLD_A);
                    return;
                }
                finishCase(mc);
                if (scenario.visualCheck()) {
                    visualShot = 0;
                    cullsOn(false);
                    enter(Phase.VISUAL);
                    return;
                }
                nextCase(mc);
            }
            case HOLD_A -> {
                if (held()) {
                    // Every frame at B now draws A's visible set first, as the first frame after a jump would.
                    GpuOcclusionCull.pinHistory(true);
                    GpuOcclusionCull.resetStats();
                    sweep = Sweep.AT_B;
                    enter(Phase.HOLD_B);
                }
            }
            case HOLD_B -> {
                // A flight's end is new terrain still streaming in, so it waits as the terrain wait does.
                boolean flying = sweep == Sweep.FLYING;
                boolean settled = flying ? terrainSettled(mc) : held();
                if (flying && !settled && seconds() > FLY_END_TIMEOUT_S) {
                    VultrumClient.LOGGER.warn("[bench] {} end of flight not loaded after {} s", cases.get(caseIndex).id(), FLY_END_TIMEOUT_S);
                    settled = true;
                }
                if (settled) {
                    finishCase(mc);
                    GpuOcclusionCull.pinHistory(false);
                    sweep = Sweep.OFF;
                    nextCase(mc);
                }
            }
            case VISUAL -> {
                var renderer = SodiumWorldRenderer.instanceNullable();
                if (seconds() < VISUAL_SETTLE_S || renderer == null || !renderer.isTerrainRenderComplete()) {
                    if (seconds() < HOLD_TIMEOUT_S) return;
                    VultrumClient.LOGGER.warn("[bench] {} terrain not complete for its visual shot", cases.get(caseIndex).id());
                    // Shots of terrain still building compare nothing.
                    results.getLast().put("visualTerrainTimedOut", true);
                }
                visual(mc, VISUAL_SHOTS.get(visualShot));
                // off1, on2, off2: the culls switch after each, and are on again after the last.
                cullsOn(visualShot != 1);
                if (++visualShot < VISUAL_SHOTS.size()) {
                    enter(Phase.VISUAL);
                } else {
                    nextCase(mc);
                }
            }
            case SETTINGS -> {
                // Each page shows for a second, long enough to lay out and draw, then the next one opens.
                if (seconds() >= 1) {
                    shootSettingsPage(mc);
                    if (++settingsPage < settingsPages.size()) {
                        openSettingsPage(mc);
                    } else {
                        mc.gui.setScreen(null);
                        writeResults(mc, "complete");
                        quit(mc);
                    }
                }
            }
            case QUIT -> {
                if (serverCaughtUp.isDone() || seconds() > QUIT_TIMEOUT_S) stop(mc);
            }
        }
    }

    /** Ticking is frozen: starts the case's warm-up, and its camera motion if it has one. */
    private void startWarmup(Minecraft mc) {
        var viewpoint = cases.get(caseIndex).viewpoint();
        if (viewpoint.motion() != null || viewpoint.fly() != null) {
            anchor = pose(mc);
            sweepStart = System.nanoTime();
            sweep = viewpoint.fly() != null ? Sweep.FLYING : Sweep.MOVING;
        }
        warmUp(mc);
    }

    /** Enters a warm-up, the case's first or one before a re-recording, with what each of them resets. */
    private void warmUp(Minecraft mc) {
        raiseIfUnfocused(mc);
        buildsAtWarmup = Builds.count();
        enter(Phase.WARMUP);
    }

    /**
     * Sodium has built every column the render distance holds and has no column left waiting to be removed, and the
     * loaded-chunk count has been stable for {@link #STABLE_TICKS}. Call it once a tick: it counts the stable ticks.
     */
    private boolean terrainSettled(Minecraft mc) { return terrainSettled(mc, cases.get(caseIndex).renderDistance()); }

    private boolean terrainSettled(Minecraft mc, int renderDistance) {
        int loaded = mc.level.getChunkSource().getLoadedChunksCount();
        stableTicks = loaded == lastLoaded ? stableTicks + 1 : 0;
        lastLoaded = loaded;
        var renderer = SodiumWorldRenderer.instanceNullable();
        return renderer != null && renderer.isTerrainRenderComplete() && UnloadBudget.pending() == 0 && columnsLoaded(mc, renderDistance)
                && stableTicks >= STABLE_TICKS && seconds() >= TERRAIN_MIN_S;
    }

    private void startLoad() {
        loadRunning = true;
        loadStart = System.nanoTime();
        loadTicks = 0;
        FrameTimer.startLoad();
        PacketBudget.resetStats();
        ReclaimThread.client().drainBatches(reclaimBatches);
        reclaimBatches.clear();
    }

    /**
     * Call once a tick while a load is timed. True once Sodium has reported terrain complete, with every column in
     * and the player arrived, for {@link #LOAD_TICKS} ticks in a row, the second those should take and as many frames.
     * The load ended where that streak started. Columns the unload budget has left waiting to be removed don't hold
     * it up.
     */
    private boolean loaded(Minecraft mc, int renderDistance, boolean arrived) {
        var renderer = SodiumWorldRenderer.instanceNullable();
        if (!arrived || renderer == null || !renderer.isTerrainRenderComplete() || !columnsLoaded(mc, renderDistance)) {
            loadTicks = 0;
            return false;
        }
        if (loadTicks++ == 0) {
            loadStreakStart = System.nanoTime();
            // Frames, packets, chunk events and the reclaim come in step, one of each a render.
            loadStreakFrames = FrameTimer.loadFrameMs.size();
            loadStreakPending = FrameTimer.loadColumnsPendingMax;
            loadStreakBudget = PacketBudget.stats();
        }
        // Sodium reports complete while it has nothing to build, which is also the case before it has taken in a
        // column: it takes them in at the next frame's setup and schedules their builds a frame or two later. So the
        // streak must span frames. After a long frame up to 10 ticks run in one, and after a frame of over a second
        // (a join's first) the ticks and the second can both pass within two.
        return loadTicks >= LOAD_TICKS && System.nanoTime() - loadStreakStart >= LOAD_TICKS * 50_000_000L
                && FrameTimer.loadFrameMs.size() - loadStreakFrames >= LOAD_TICKS;
    }

    /** Ends the current case's load once it has loaded, or when the terrain wait ends ({@code force}). */
    private void trackLoad(Minecraft mc, boolean force) {
        if (!loadRunning) return;
        var chunk = mc.player.chunkPosition();
        boolean arrived = chunk.x() == targetChunkX && chunk.z() == targetChunkZ && inDimension(mc, cases.get(caseIndex).viewpoint());
        if (loaded(mc, cases.get(caseIndex).renderDistance(), arrived) || force) caseLoad = endLoad(cases.get(caseIndex).id());
    }

    /** Whether the client is in the viewpoint's dimension yet: a teleport into another takes a few ticks to arrive. */
    private static boolean inDimension(Minecraft mc, Scenario.Viewpoint viewpoint) {
        String dimension = viewpoint.dimension() != null ? viewpoint.dimension() : "minecraft:overworld";
        return mc.level != null && mc.level.dimension().identifier().toString().equals(dimension);
    }

    /**
     * The load's record, up to the start of the current complete streak, or up to now if terrain isn't complete
     * ({@code complete} false: a timeout, or the player not where the case put it).
     */
    private Map<String, Object> endLoad(String name) {
        loadRunning = false;
        FrameTimer.stopLoad();
        boolean complete = loadTicks > 0;
        if (!complete) VultrumClient.LOGGER.warn("[bench] {} load ended before its terrain was complete", name);
        long end = complete ? loadStreakStart : System.nanoTime();
        double[] frames = FrameTimer.loadFrameMs.toDoubleArray(), packets = FrameTimer.loadPacketsMs.toDoubleArray(),
                chunkEvents = FrameTimer.loadChunkEventsMs.toDoubleArray(), reclaim = FrameTimer.loadReclaimMs.toDoubleArray();
        if (complete) {
            frames = Arrays.copyOf(frames, loadStreakFrames);
            packets = Arrays.copyOf(packets, loadStreakFrames);
            chunkEvents = Arrays.copyOf(chunkEvents, loadStreakFrames);
            reclaim = Arrays.copyOf(reclaim, loadStreakFrames);
        }
        var load = new LinkedHashMap<String, Object>();
        load.put("seconds", round((end - loadStart) / 1e9));
        load.put("complete", complete);
        var frameStats = Stats.of(frames);
        load.put("frameMs", frameStats.toMap());
        load.put("fps1Low", Stats.round(Stats.lowFps(frames, 0.01)));
        load.put("fps01Low", Stats.round(Stats.lowFps(frames, 0.001)));
        load.put("hitches", Stats.hitchFrames(frames, frameStats.p50()).length);
        load.put("packetsMs", Stats.of(packets).toMap());
        load.put("chunkEventsMs", Stats.of(chunkEvents).toMap());
        load.put("reclaimMs", Stats.of(reclaim).toMap());
        load.put("reclaimThread", reclaimThread(loadStart, end, null, null));
        load.put("columnsPendingMax", complete ? loadStreakPending : FrameTimer.loadColumnsPendingMax);
        load.put("packetBudget", complete ? loadStreakBudget : PacketBudget.stats());
        return load;
    }

    /** The held pose has been shown for a while and Sodium has built what it shows. */
    private boolean held() {
        var renderer = SodiumWorldRenderer.instanceNullable();
        boolean complete = renderer != null && renderer.isTerrainRenderComplete();
        return (complete && seconds() >= HOLD_MIN_S) || seconds() >= HOLD_TIMEOUT_S;
    }

    /** The visual check's switch: Vultrum's shadow-pass culls and its occlusion cull, from the next frame. */
    private static void cullsOn(boolean on) {
        ShadowPass.suspend(!on);
        GpuOcclusionCull.override(on);
    }

    /** Takes one of the visual check's shots, and lists it on the case's result next to the case's own. */
    @SuppressWarnings("unchecked")
    private void visual(Minecraft mc, String shot) {
        var current = cases.get(caseIndex);
        String file = current.id() + "-" + shot + ".png";
        Screenshot.grab(out.toFile(), file, mc.gameRenderer.mainRenderTarget(), 1, message -> { });
        var result = results.getLast();
        var shots = (Map<String, Object>) result.computeIfAbsent("visual", key -> {
            var map = new LinkedHashMap<String, Object>();
            map.put("on1", result.get("screenshot"));
            return map;
        });
        shots.put(shot, "screenshots/" + file);
        // A shot taken from elsewhere compares nothing.
        if (!samePose(pose, pose(mc))) result.put("visualMoved", true);
        VultrumClient.LOGGER.info("[bench] visual {} {}", current.id(), shot);
    }

    private void nextCase(Minecraft mc) {
        command(mc, "tick unfreeze");
        cleanUp(mc);
        attempt = 0;
        actionsDone = 0;
        caseGround = null;
        if (++caseIndex < cases.size()) {
            enter(Phase.PLACE);
        } else if (scenario.settingsCheck() && startSettings()) {
            openSettingsPage(mc);
        } else {
            // The settings check fails the run when there was no page to open.
            writeResults(mc, scenario.settingsCheck() ? "error: Sodium's video settings hold no Vultrum page" : "complete");
            quit(mc);
        }
    }

    /** Lists Vultrum's pages in Sodium's video settings. False when there are none (Sodium out of range). */
    private boolean startSettings() {
        for (var mod : ConfigManager.CONFIG.getModOptions())
            if (mod.configId().equals("vultrum"))
                for (var page : mod.pages())
                    if (page instanceof OptionPage optionPage) settingsPages.add(optionPage);
        settingsPage = 0;
        return !settingsPages.isEmpty();
    }

    private void openSettingsPage(Minecraft mc) {
        mc.gui.setScreen(VideoSettingsScreen.createScreen(null, settingsPages.get(settingsPage)));
        enter(Phase.SETTINGS);
    }

    /** Screenshots the page shown and records which screen shows it: another mod may replace Sodium's. */
    private void shootSettingsPage(Minecraft mc) {
        String shot = "settings-" + (settingsPage + 1) + ".png";
        Screenshot.grab(out.toFile(), shot, mc.gameRenderer.mainRenderTarget(), 1, message -> { });
        var screen = mc.gui.screen();
        var entry = new LinkedHashMap<String, Object>();
        entry.put("page", settingsPages.get(settingsPage).name().getString());
        entry.put("screen", screen == null ? null : screen.getClass().getName());
        entry.put("screenshot", "screenshots/" + shot);
        settings.add(entry);
        VultrumClient.LOGGER.info("[bench] settings {}", entry);
    }

    /** Before each frame's extract: applies the camera motion of the current case, if any. */
    public static void frame(Minecraft mc) {
        if (instance == null || instance.sweep == Sweep.OFF || mc.player == null) return;
        var viewpoint = instance.cases.get(instance.caseIndex).viewpoint();
        double[] b = instance.anchor;
        if (instance.sweep == Sweep.FLYING) {
            // Straight ahead along the start's view direction. The flight time is capped, so it ends at the
            // same place every run, ready for the screenshot.
            var scenario = instance.scenario;
            double t = Math.min((System.nanoTime() - instance.sweepStart) / 1e9,
                    scenario.warmupSeconds() + scenario.recordSeconds() + FLY_OVERRUN_S);
            double distance = viewpoint.fly().speed() * t, yaw = Math.toRadians(b[3]);
            mc.player.snapTo(b[0] - Math.sin(yaw) * distance, b[1], b[2] + Math.cos(yaw) * distance, (float) b[3], (float) b[4]);
            mc.player.setOldPosAndRot();
            return;
        }
        var motion = viewpoint.motion();
        double s = switch (instance.sweep) {
            case AT_A -> 0;
            case AT_B -> 1;
            case MOVING -> {
                double cycle = (System.nanoTime() - instance.sweepStart) / 1e9 / motion.period() % 1;
                yield cycle < 0.5 ? 2 * cycle : 2 - 2 * cycle;
            }
            // Both returned above: OFF has no camera to move, and a flight has no motion.
            case OFF, FLYING -> throw new IllegalStateException("not a motion sweep: " + instance.sweep);
        };
        // s = 0 is pose A: turned left by `turn` and `step` blocks behind B along B's view direction.
        double back = motion.step() * (1 - s), yaw = Math.toRadians(b[3]);
        mc.player.snapTo(b[0] + Math.sin(yaw) * back, b[1], b[2] - Math.cos(yaw) * back,
                (float) (b[3] - motion.turn() * (1 - s)), (float) b[4]);
        mc.player.setOldPosAndRot();
    }

    private void applyOptions(Minecraft mc) {
        var options = mc.options;
        options.enableVsync().set(false);
        options.framerateLimit().set(260); // 260 is "Unlimited"
        options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
        options.fullscreen().set(scenario.fullscreen());
        options.pauseOnLostFocus = false;
        options.onboardAccessibility = false;
        // The join loads at the first case's render distance, so it's the same every run of a scenario.
        options.renderDistance().set(cases.getFirst().renderDistance());
    }

    private void openWorld(Minecraft mc) {
        var server = scenario.server();
        if (server != null) {
            if (rcon == null) {
                try {
                    rcon = new Rcon(server.rcon(), server.rconPassword());
                } catch (IOException e) {
                    throw new IllegalStateException("Can't reach the server's RCON at " + server.rcon(), e);
                }
            }
            VultrumClient.LOGGER.info("[bench] joining server {}", server.address());
            ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(server.address()),
                    new ServerData("Vultrum bench", server.address(), ServerData.Type.OTHER), false, null);
            return;
        }
        var flows = mc.createWorldOpenFlows();
        if (mc.getLevelSource().levelExists(scenario.world())) {
            VultrumClient.LOGGER.info("[bench] opening world {}", scenario.world());
            flows.openWorld(scenario.world(), () -> { throw new IllegalStateException("Opening the world was cancelled"); });
        } else {
            VultrumClient.LOGGER.info("[bench] creating world {} with seed {}", scenario.world(), scenario.seed());
            var settings = new LevelSettings(scenario.world(), GameType.SPECTATOR,
                    new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
            flows.createFreshLevel(scenario.world(), settings, new WorldOptions(scenario.seed(), true, false),
                    WorldPresets::createNormalWorldDimensions, new TitleScreen());
        }
    }

    /** The join's setup, and a rejoin's: it keeps the spawn and what was located, and sets the rest again. */
    private void prepareWorld(Minecraft mc) {
        // The world's spawn, which the server sends to the client level of every dimension. A rejoin keeps the first's.
        if (origin == null) origin = mc.level.getRespawnData().pos();
        ((HudAccessor) mc.gui.hud).vultrum$setHidden(true);
        // A dedicated server keeps a killed run's freeze.
        command(mc, "tick unfreeze");
        command(mc, "gamerule " + GameRules.ADVANCE_TIME.id() + " false");
        command(mc, "gamerule " + GameRules.ADVANCE_WEATHER.id() + " false");
        command(mc, "gamerule " + GameRules.RANDOM_TICK_SPEED.id() + " 0");
        command(mc, "gamerule " + GameRules.SPAWN_MOBS.id() + " false");
        command(mc, "time set " + NOON);
        command(mc, "weather clear");
        command(mc, "gamemode spectator @a");
        VultrumClient.LOGGER.info("[bench] world ready, spawn={}", origin);
        var server = mc.getSingleplayerServer();
        for (var viewpoint : scenario.viewpoints()) {
            String query = viewpoint.locate();
            if (query == null || located.containsKey(query)) continue;
            located.put(query, server.submit(() -> {
                var pos = locate(server.overworld(), query, origin);
                VultrumClient.LOGGER.info("[bench] locate {} -> {}", query, pos);
                return pos;
            }));
        }
    }

    /** Runs on the server thread, with the search radii vanilla's /locate uses. Null when nothing is in range. */
    private static BlockPos locate(ServerLevel level, String query, BlockPos from) {
        String[] parts = query.split(" ", 2);
        if (parts[0].equals("biome")) {
            var biome = ResourceKey.create(Registries.BIOME, Identifier.parse(parts[1]));
            var found = level.findClosestBiome3d(holder -> holder.is(biome), from, 6400, 32, 64);
            return found == null ? null : found.getFirst();
        }
        if (parts[1].startsWith("#")) {
            var structures = TagKey.create(Registries.STRUCTURE, Identifier.parse(parts[1].substring(1)));
            return level.findNearestMapStructure(structures, from, 100, false);
        }
        var structure = level.registryAccess().lookupOrThrow(Registries.STRUCTURE)
                .get(ResourceKey.create(Registries.STRUCTURE, Identifier.parse(parts[1])))
                .orElseThrow(() -> new IllegalStateException("No structure " + parts[1]));
        return level.findNearestMapStructure(HolderSet.direct(structure), from, 100, false);
    }

    private BlockPos base(Scenario.Viewpoint viewpoint) {
        if (viewpoint.dimension() != null) return BlockPos.ZERO;
        return viewpoint.locate() == null ? origin : located.get(viewpoint.locate()).join();
    }

    /** Runs the viewpoint's setup commands at its column (x, z centred, y the ground), and keeps the placeholders for its cleanup. */
    private void setUp(Minecraft mc, double x, double y, double z, Scenario.Viewpoint viewpoint) {
        if (viewpoint.setup() == null && viewpoint.cleanup() == null) return;
        setupVars = Map.of("x", coordinate(x), "y", coordinate(y), "z", coordinate(z), "yaw", coordinate(viewpoint.yaw()));
        if (viewpoint.setup() == null) return;
        int count = 0;
        for (var setup : viewpoint.setup())
            for (String command : setup.commands(setupVars)) {
                command(mc, command);
                count++;
            }
        VultrumClient.LOGGER.info("[bench] setup {}: {} commands", cases.get(caseIndex).id(), count);
    }

    /** Undoes a case's setup, while ticking runs so that killed mobs finish dying. */
    private void cleanUp(Minecraft mc) {
        if (setupVars == null) return;
        var cleanup = cases.get(caseIndex).viewpoint().cleanup();
        if (cleanup != null) for (String command : cleanup) command(mc, Scenario.fill(command, setupVars));
        setupVars = null;
    }

    private static String describe(Screen screen) {
        return screen == null ? "no screen" : screen.getClass().getName() + " \"" + screen.getNarrationMessage().getString() + "\"";
    }

    private static String coordinate(double value) { return String.format(java.util.Locale.ROOT, "%.2f", value); }

    private void teleport(Minecraft mc, double x, double y, double z, Scenario.Viewpoint viewpoint) {
        // The server's command source is in the overworld, so a plain tp also brings the player back from another dimension.
        String in = viewpoint.dimension() == null ? "" : "execute in " + viewpoint.dimension() + " run ";
        command(mc, String.format(java.util.Locale.ROOT, "%stp @a %.2f %.2f %.2f %.1f %.1f", in, x, y, z, viewpoint.yaw(), viewpoint.pitch()));
    }

    /**
     * Runs one of a viewpoint's before actions, as the player would in game. True when it left the world (a rejoin),
     * which comes back to PLACE once the world is open again.
     */
    private boolean act(Minecraft mc, String action) {
        VultrumClient.LOGGER.info("[bench] {} before {}", action, cases.get(caseIndex).id());
        switch (action) {
            // F3+A
            case "reload" -> mc.levelExtractor.allChanged();
            // F11, and the video settings' button
            case "fullscreen", "windowed" -> mc.options.fullscreen().set(action.equals("fullscreen"));
            // Dragging the window's edge: the swapchain and every render target change size.
            case "resize" -> {
                mc.options.fullscreen().set(false);
                mc.getWindow().setWindowed(RESIZE_WIDTH, RESIZE_HEIGHT);
            }
            case "pack-off", "pack-on" -> VitrailPack.set(action.equals("pack-on"));
            // The pause screen's Save and Quit to Title.
            case "rejoin" -> {
                mc.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
                enter(Phase.REJOIN);
                return true;
            }
            default -> throw new IllegalStateException("Unknown action " + action);
        }
        return false;
    }

    private void command(Minecraft mc, String command) {
        if (rcon != null) {
            // The integrated server logs what a command says; a dedicated server's console says it here.
            rcon.run(command).whenComplete((reply, error) -> {
                if (error != null) VultrumClient.LOGGER.error("[bench] {}", command, error);
                else if (!reply.isEmpty()) VultrumClient.LOGGER.info("[bench] {}: {}", command, reply);
            });
            return;
        }
        var server = mc.getSingleplayerServer();
        server.executeIfPossible(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command));
    }

    /**
     * The reclaim thread's work from {@code from} to {@code to} (System.nanoTime), null while it is off: the
     * reclaims that took a reference, their references and time, and each of 1,000 references or more as [wall-clock
     * start in epoch ms, ms, references], plus the longest frame it overlapped when {@code frames} is given.
     */
    private Map<String, Object> reclaimThread(long from, long to, double[] frames, double[] frameStarts) {
        if (!ReclaimThread.on()) return null;
        ReclaimThread.client().drainBatches(reclaimBatches);
        int reclaims = 0;
        long refs = 0;
        double ms = 0, maxMs = 0;
        var listed = new ArrayList<List<Object>>();
        for (var batch : reclaimBatches) {
            if (batch.startNanos() + batch.nanos() < from || batch.startNanos() > to) continue;
            double batchMs = batch.nanos() / 1e6;
            reclaims++;
            refs += batch.refs();
            ms += batchMs;
            maxMs = Math.max(maxMs, batchMs);
            if (batch.refs() < 1000 || listed.size() >= 32) continue;
            double start = FrameTimer.epochMs(batch.startNanos());
            var entry = new ArrayList<Object>(List.of(Math.round(start), round(batchMs), batch.refs()));
            if (frames != null) entry.add(worstFrame(frames, frameStarts, start, start + batchMs));
            listed.add(entry);
        }
        var stats = new LinkedHashMap<String, Object>();
        stats.put("reclaims", reclaims);
        stats.put("refs", refs);
        stats.put("ms", round(ms));
        stats.put("maxMs", round(maxMs));
        stats.put("batches", listed);
        return stats;
    }

    /** The longest recorded frame that overlaps a span of wall-clock time, or null if none does. */
    private static Double worstFrame(double[] frames, double[] frameStarts, double startEpochMs, double endEpochMs) {
        double worst = -1;
        for (int i = 0; i < frames.length && i < frameStarts.length; i++)
            if (frameStarts[i] < endEpochMs && frameStarts[i] + frames[i] > startEpochMs) worst = Math.max(worst, frames[i]);
        return worst < 0 ? null : round(worst);
    }

    private void finishCase(Minecraft mc) {
        var current = cases.get(caseIndex);
        double[] frames = FrameTimer.frameMs.toDoubleArray();
        var result = new LinkedHashMap<String, Object>();
        result.put("id", current.id());
        result.put("viewpoint", current.viewpoint().name());
        result.put("renderDistance", current.renderDistance());
        result.put("position", List.of(round(mc.player.getX()), round(mc.player.getY()), round(mc.player.getZ())));
        result.put("fpsAvg", Stats.round(Stats.averageFps(frames)));
        result.put("fps1Low", Stats.round(Stats.lowFps(frames, 0.01)));
        result.put("fps01Low", Stats.round(Stats.lowFps(frames, 0.001)));
        var frameStats = Stats.of(frames);
        result.put("frameMs", frameStats.toMap());
        var hitchBreakdown = hitchBreakdown(frames, frameStats.p50());
        result.put("frameHitches", hitchBreakdown.get("count"));
        result.put("renderCpuMs", Stats.of(FrameTimer.renderCpuMs.toDoubleArray()).toMap());
        result.put("extractCpuMs", Stats.of(FrameTimer.extractCpuMs.toDoubleArray()).toMap());
        result.put("terrainSetupCpuMs", Stats.of(FrameTimer.setupCpuMs.toDoubleArray()).toMap());
        var phases = new LinkedHashMap<String, Object>();
        var phaseMs = new LinkedHashMap<String, double[]>();
        for (var phase : FrameTimer.SetupPhase.values()) {
            double[] ms = FrameTimer.setupPhaseMs[phase.ordinal()].toDoubleArray();
            phaseMs.put(phase.key, ms);
            phases.put(phase.key, Stats.of(ms).toMap());
        }
        result.put("terrainSetupPhasesMs", phases);
        // The references Sodium's reclaim polled: all of them, the most in one frame, and the frames that polled any.
        double[] reclaimed = FrameTimer.reclaimedRefs.toDoubleArray();
        var refs = new LinkedHashMap<String, Object>();
        refs.put("total", (long) Arrays.stream(reclaimed).sum());
        refs.put("max", (long) Arrays.stream(reclaimed).max().orElse(0));
        refs.put("frames", Arrays.stream(reclaimed).filter(count -> count > 0).count());
        result.put("reclaimedRefs", refs);
        result.put("reclaimThread", reclaimThread(recordStart, recordEnd, frames, FrameTimer.frameStartEpochMs.toDoubleArray()));
        double[] setups = FrameTimer.setupCpuMs.toDoubleArray();
        result.put("longSetupCount", LongSetups.count(setups));
        result.put("longSetups", LongSetups.of(frames, FrameTimer.frameStartEpochMs.toDoubleArray(), setups, phaseMs, reclaimed, FrameTimer.gpuFrameMs.toDoubleArray()));
        var loop = new LinkedHashMap<String, Object>();
        // Null for a phase never timed (an optional wrap that didn't apply), rather than zeros.
        for (var phase : FrameTimer.LoopPhase.values())
            loop.put(phase.key, FrameTimer.timed(phase) ? Stats.of(FrameTimer.loopPhaseMs[phase.ordinal()].toDoubleArray()).toMap() : null);
        result.put("frameLoopPhasesMs", loop);
        result.put("hitchBreakdownMs", hitchBreakdown);
        var columns = new LinkedHashMap<String, Object>();
        columns.put("added", Stats.of(FrameTimer.addedColumns.toDoubleArray()).toMap());
        columns.put("removed", Stats.of(FrameTimer.removedColumns.toDoubleArray()).toMap());
        columns.put("pending", Stats.of(FrameTimer.pendingColumns.toDoubleArray()).toMap());
        result.put("chunkColumns", columns);
        result.put("terrainDrawCpuMs", Stats.of(FrameTimer.drawCpuMs.toDoubleArray()).toMap());
        result.put("lateRenderListsCpuMs", Stats.of(FrameTimer.lateListsCpuMs.toDoubleArray()).toMap());
        result.put("gpuFrameMs", Stats.of(FrameTimer.gpuFrameMs.toDoubleArray()).toMap());
        result.put("gpuTerrainMs", Stats.of(FrameTimer.gpuTerrainMs.toDoubleArray()).toMap());
        result.put("gpuDropped", FrameTimer.gpuDropped());
        result.put("gpuSpanOverflow", FrameTimer.gpuOverflowed());
        var terrainStats = FrameTimer.terrainStats();
        if (terrainStats != null) result.put("terrainStats", terrainStats);
        if (current.viewpoint().motion() != null) result.put("motion", current.viewpoint().motion());
        if (current.viewpoint().fly() != null) result.put("fly", current.viewpoint().fly());
        result.put("packetBudget", packetStats);
        result.put("shadowRegion", ShadowRegion.stats());
        if (cullStats != null) {
            result.put("occlusionCull", cullStats);
            VultrumClient.LOGGER.info("[bench] occlusion-cull {} {}", current.id(), cullStats);
        }
        // The pinned shot's own counters: phase 1 draws pose A's visible set at B, phase 2 what it missed.
        var pinnedStats = sweep == Sweep.AT_B ? GpuOcclusionCull.stats() : null;
        if (pinnedStats != null) result.put("occlusionCullPinned", pinnedStats);
        var renderer = SodiumWorldRenderer.instanceNullable();
        result.put("visibleSections", renderer == null ? null : renderer.getVisibleChunkCount());
        result.put("loadedChunks", mc.level.getChunkSource().getLoadedChunksCount());
        int entities = 0;
        for (var ignored : mc.level.entitiesForRendering()) entities++;
        result.put("entities", entities);
        result.put("terrainWaitS", round(terrainWaitS));
        // The section builds Sodium submitted while warming up and recording, after the wait for them to go quiet.
        var builds = new LinkedHashMap<String, Object>();
        builds.put("quietWaitS", round(buildsWaitS));
        builds.put("quietTimedOut", buildsTimedOut);
        builds.put("warmup", buildsAtRecord - buildsAtWarmup);
        builds.put("record", buildsRecorded);
        result.put("builds", builds);
        result.put("load", caseLoad);
        result.put("terrainTimedOut", terrainTimedOut);
        result.put("focusLost", focusLost);
        result.put("unfocused", unfocused);
        result.put("moved", moved);
        result.put("attempts", attempt + 1);
        String screenshot = current.id() + ".png";
        Screenshot.grab(out.toFile(), screenshot, mc.gameRenderer.mainRenderTarget(), 1, message -> { });
        result.put("screenshot", "screenshots/" + screenshot);
        results.add(result);
        VultrumClient.LOGGER.info("[bench] {} fps={} 1%low={} gpuFrame={} gpuTerrain={} terrainCpu={}+{}",
                current.id(), result.get("fpsAvg"), result.get("fps1Low"),
                Stats.round(Stats.of(FrameTimer.gpuFrameMs.toDoubleArray()).mean()),
                Stats.round(Stats.of(FrameTimer.gpuTerrainMs.toDoubleArray()).mean()),
                Stats.round(Stats.of(FrameTimer.setupCpuMs.toDoubleArray()).mean()),
                Stats.round(Stats.of(FrameTimer.drawCpuMs.toDoubleArray()).mean()));
    }

    /**
     * Mean CPU split of the hitch frames. Frame interval i runs from render i to render i + 1: render
     * i (with the terrain draws and Vitrail's shadow walk), then the loop phases (submit and present,
     * packets, tasks, ticks, acquire, the light queue and engine), then extract i + 1 (with the terrain setup).
     */
    private static Map<String, Object> hitchBreakdown(double[] frames, double medianMs) {
        double[] extract = FrameTimer.extractCpuMs.toDoubleArray();
        // endFrame records one more extract than there are frame intervals, so this keeps every hitch.
        int[] hitches = Arrays.stream(Stats.hitchFrames(frames, medianMs)).filter(i -> i + 1 < extract.length).toArray();
        var breakdown = new LinkedHashMap<String, Object>();
        breakdown.put("count", hitches.length);
        double[] starts = FrameTimer.frameStartEpochMs.toDoubleArray();
        breakdown.put("startsEpochMs", Arrays.stream(hitches).mapToObj(i -> Math.round(starts[i])).toList());
        breakdown.put("framesMs", Arrays.stream(hitches).mapToObj(i -> Stats.round(frames[i])).toList());
        breakdown.put("frame", Stats.round(Stats.meanAt(frames, hitches, 0)));
        breakdown.put("render", Stats.round(Stats.meanAt(FrameTimer.renderCpuMs.toDoubleArray(), hitches, 0)));
        breakdown.put("terrainDraw", Stats.round(Stats.meanAt(FrameTimer.drawCpuMs.toDoubleArray(), hitches, 0)));
        breakdown.put("lateRenderLists", Stats.round(Stats.meanAt(FrameTimer.lateListsCpuMs.toDoubleArray(), hitches, 0)));
        breakdown.put("extract", Stats.round(Stats.meanAt(extract, hitches, 1)));
        breakdown.put("terrainSetup", Stats.round(Stats.meanAt(FrameTimer.setupCpuMs.toDoubleArray(), hitches, 1)));
        for (var phase : FrameTimer.SetupPhase.values())
            breakdown.put(phase.key, Stats.round(Stats.meanAt(FrameTimer.setupPhaseMs[phase.ordinal()].toDoubleArray(), hitches, 1)));
        for (var phase : FrameTimer.LoopPhase.values())
            breakdown.put(phase.key, FrameTimer.timed(phase) ? Stats.round(Stats.meanAt(FrameTimer.loopPhaseMs[phase.ordinal()].toDoubleArray(), hitches, 1)) : null);
        breakdown.put("columnsAdded", Stats.round(Stats.meanAt(FrameTimer.addedColumns.toDoubleArray(), hitches, 1)));
        breakdown.put("columnsRemoved", Stats.round(Stats.meanAt(FrameTimer.removedColumns.toDoubleArray(), hitches, 1)));
        breakdown.put("columnsPending", Stats.round(Stats.meanAt(FrameTimer.pendingColumns.toDoubleArray(), hitches, 1)));
        return breakdown;
    }

    private void writeResults(Minecraft mc, String status) {
        var root = new LinkedHashMap<String, Object>();
        root.put("label", label);
        root.put("status", status);
        root.put("finishedAt", Instant.now().toString());
        root.put("scenario", scenario);
        var device = RenderSystem.getDevice().getDeviceInfo();
        root.put("device", Map.of("name", device.name(), "vendor", device.vendorName(), "driver", device.driverInfo(), "backend", device.backendName()));
        var target = mc.gameRenderer.mainRenderTarget();
        root.put("framebuffer", List.of(target.width, target.height));
        root.put("sodiumBackend", DrawBackend.BACKEND.getName());
        root.put("reclaimThread", ReclaimThread.on());
        root.put("java", Runtime.version().toString());
        root.put("jvmArgs", ManagementFactory.getRuntimeMXBean().getInputArguments());
        var mods = new LinkedHashMap<String, String>();
        for (var mod : FabricLoader.getInstance().getAllMods())
            if (mod.getContainingMod().isEmpty()) mods.put(mod.getMetadata().getId(), mod.getMetadata().getVersion().getFriendlyString());
        root.put("mods", mods);
        var options = mc.options;
        root.put("options", Map.of("simulationDistance", options.simulationDistance().get(), "biomeBlendRadius", options.biomeBlendRadius().get(),
                "vsync", options.enableVsync().get(), "framerateLimit", options.framerateLimit().get(), "fullscreen", options.fullscreen().get()));
        root.put("join", join);
        root.put("cases", results);
        if (scenario.settingsCheck()) root.put("settings", settings);
        try {
            Files.createDirectories(out);
            Files.writeString(out.resolve("results.json"), new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(root));
            VultrumClient.LOGGER.info("[bench] wrote {} ({})", out.resolve("results.json"), status);
        } catch (IOException e) {
            VultrumClient.LOGGER.error("[bench] cannot write results", e);
        }
        try {
            Timeline.write(out);
        } catch (IOException e) {
            VultrumClient.LOGGER.error("[bench] cannot write the timeline", e);
        }
    }

    /** True while a benchmark runs: the input mixins drop keyboard and mouse events so nobody can move the camera. */
    public static boolean locksInput() { return instance != null; }

    /** Every chunk column the client should hold for this render distance has arrived (inner disc, 2 chunks of slack). */
    private static boolean columnsLoaded(Minecraft mc, int renderDistance) {
        var center = mc.player.chunkPosition();
        var chunks = mc.level.getChunkSource();
        int r = renderDistance - 2;
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++)
                if (dx * dx + dz * dz <= r * r && !chunks.hasChunk(center.x() + dx, center.z() + dz)) return false;
        return true;
    }

    private static double[] pose(Minecraft mc) {
        var p = mc.player;
        return new double[] {p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot()};
    }

    private static boolean samePose(double[] a, double[] b) {
        for (int i = 0; i < a.length; i++) if (Math.abs(a[i] - b[i]) > 1e-3) return false;
        return true;
    }

    /**
     * Whether the window has input focus. The game's flag ({@code isWindowActive}) starts true and changes only on SDL's
     * focus events, so a window that opened behind another reads as focused until it gains and loses focus once, while
     * Dynamic FPS caps it at 60 or 1 FPS. SDL's window flags say what the OS says.
     */
    private static boolean focused(Minecraft mc) {
        return mc.isWindowActive() && (SDLVideo.SDL_GetWindowFlags(mc.getWindow().handle()) & SDLVideo.SDL_WINDOW_INPUT_FOCUS) != 0;
    }

    /**
     * Brings an unfocused window to the front. Windows refuses a plain raise from a process that isn't in front (the game
     * launched from a terminal opens behind it), so SDL's forced raise attaches to the foreground window's input first.
     * If it still fails, the recording counts as unfocused.
     */
    private static void raiseIfUnfocused(Minecraft mc) {
        if (focused(mc)) return;
        SDLHints.SDL_SetHint(SDLHints.SDL_HINT_FORCE_RAISEWINDOW, "1");
        SDLVideo.SDL_RaiseWindow(mc.getWindow().handle());
    }

    private void enter(Phase next) {
        if (Timeline.ON) Timeline.mark(next + (caseIndex < cases.size() ? " " + cases.get(caseIndex).id() : ""));
        phase = next;
        phaseStart = System.nanoTime();
        stableTicks = 0;
        lastLoaded = -1;
    }

    private double seconds() { return (System.nanoTime() - phaseStart) / 1e9; }

    private static double round(double value) { return Math.round(value * 100) / 100.0; }
}
