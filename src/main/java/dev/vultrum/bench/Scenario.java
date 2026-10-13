package dev.vultrum.bench;

import com.google.gson.Gson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A benchmark scenario, loaded from JSON (see bench/scenarios/default.json). Viewpoint x/z are offsets
 * from the world spawn, or from the result of {@code locate} ("biome minecraft:jungle" or
 * "structure #minecraft:village"), which the server searches for from spawn. Height is absolute
 * ({@code y}) or relative to the top block ({@code aboveGround}); {@code ignoreLeaves} puts the camera
 * under the canopy instead of on top of it.
 *
 * <p>With {@code motion}, the camera sweeps during warm-up and recording between pose A (yaw minus
 * {@code turn} degrees, {@code step} blocks behind) and the viewpoint's pose B, there and back once per
 * {@code period} seconds. The screenshot is then taken right after a jump from A to B, with the GPU
 * cull's visibility history pinned to A, which is the worst case for popping.
 *
 * <p>With {@code fly}, the camera flies straight ahead along the viewpoint's yaw at a fixed height,
 * {@code speed} blocks a second, from the start of warm-up until a second after recording ends, so
 * terrain streams in as it would in play. The screenshot is taken at the end of the route once Sodium
 * has built it.
 *
 * <p>{@code setup} commands run on the server once the camera is placed, before the terrain wait, and
 * {@code cleanup} commands when the case is done. They see {@code {x} {y} {z}} (the viewpoint's column centre, at
 * ground level with {@code aboveGround} and at the camera with {@code y}) and {@code {yaw}}. A setup with
 * {@code u} and {@code v} ranges ({@code [from, to, step]}, inclusive) runs once per grid point, with
 * {@code {u} {v}} set; use them as local coordinates ({@code ^{u} ^ ^{v}}) under
 * {@code execute ... rotated {yaw} 0}.
 *
 * <p>{@code time} is the time of day for the viewpoint, in ticks ({@code /time set}: 1000 is 07:00, 6000 noon, 18000
 * midnight). Without it the case is at noon. Time doesn't advance during a run.
 *
 * <p>With {@code visualCheck}, each static case (no motion or fly) takes three more screenshots after its own, about
 * two seconds apart: with Vultrum's culls off ({@code -off1}), on again ({@code -on2}) and off again ({@code -off2}).
 * Its own shot is {@code -on1}'s. The GPU occlusion cull culls every frame in such a run, the gate left out, so its
 * shots show it. {@code tools/visual_check.py} compares them: what the culls change beyond what changes between two
 * shots of the same setting is a fault.
 *
 * <p>With {@code settingsCheck}, once the cases are done each of Vultrum's pages in Sodium's video settings is opened
 * for a second and screenshotted ({@code settings} in results.json), to check that another mod hasn't broken them.
 *
 * <p>With {@code dimension} ("minecraft:the_nether"), the viewpoint is in that dimension, and its x and z are absolute:
 * the overworld's spawn means nothing there. A viewpoint without one is in the overworld.
 *
 * <p>{@code before} lists what the player does before the case, in order, the way they would in game (see
 * {@link #ACTIONS}): {@code reload} (F3+A), {@code fullscreen}, {@code windowed}, {@code resize} (a 1280×720 window),
 * {@code pack-off} and {@code pack-on} (Vitrail's shader pack, as its settings screen switches it; skipped without
 * Vitrail) and {@code rejoin} (save and quit to the title screen, then open the world again).
 *
 * <p>With {@code server} ({@code address}, {@code rcon} and {@code rconPassword}), the bench joins that multiplayer
 * server instead of opening {@code world}, and runs its commands as the server's console over RCON, as it runs them as
 * the integrated server's in singleplayer. A locate needs the integrated server, so it takes none.
 *
 * <p>With {@code quietBuildsSeconds}, each case waits after the terrain wait, with ticking frozen, until Sodium has
 * submitted no section build for that long, at most {@code Bench.BUILDS_TIMEOUT_S}, before its warm-up. The terrain
 * wait sees only Sodium's build queue, not the builds its walk has found and holds back under a per-frame budget.
 */
record Scenario(String world, long seed, boolean fullscreen, double warmupSeconds, double recordSeconds,
                List<Integer> renderDistances, List<Viewpoint> viewpoints, boolean settingsCheck, boolean visualCheck, Server server,
                double quietBuildsSeconds) {

    record Server(String address, String rcon, String rconPassword) { }

    static final List<String> ACTIONS = List.of("reload", "fullscreen", "windowed", "resize", "pack-off", "pack-on", "rejoin");

    record Viewpoint(String name, String locate, String dimension, int x, int z, Double y, Double aboveGround, boolean ignoreLeaves,
                     float yaw, float pitch, Integer time, Motion motion, Fly fly, List<Setup> setup, List<String> cleanup,
                     List<String> before) { }

    record Motion(float turn, double step, double period) { }

    record Fly(double speed) { }

    record Setup(String command, int[] u, int[] v) {
        /** The command for each grid point, or once without a grid, with every placeholder replaced. */
        List<String> commands(Map<String, String> vars) {
            var commands = new ArrayList<String>();
            if (u == null) {
                commands.add(fill(command, vars));
                return commands;
            }
            for (int i = u[0]; i <= u[1]; i += u[2])
                for (int j = v[0]; j <= v[1]; j += v[2])
                    commands.add(fill(command.replace("{u}", Integer.toString(i)).replace("{v}", Integer.toString(j)), vars));
            return commands;
        }
    }

    static String fill(String command, Map<String, String> vars) {
        for (var entry : vars.entrySet()) command = command.replace("{" + entry.getKey() + "}", entry.getValue());
        return command;
    }

    static Scenario load(Path path) {
        try {
            var scenario = new Gson().fromJson(Files.readString(path), Scenario.class);
            if ((scenario.world() == null && scenario.server() == null) || scenario.viewpoints() == null || scenario.viewpoints().isEmpty()
                    || scenario.renderDistances() == null || scenario.renderDistances().isEmpty())
                throw new IllegalArgumentException("Scenario needs a world or a server, renderDistances and viewpoints: " + path);
            var server = scenario.server();
            if (server != null && (server.address() == null || server.rcon() == null || !server.rcon().matches(".+:\\d+")
                    || server.rconPassword() == null || server.rconPassword().isEmpty()))
                throw new IllegalArgumentException("A server needs an address, an rcon host:port and an rconPassword: " + path);
            if (!(scenario.quietBuildsSeconds() >= 0))
                throw new IllegalArgumentException("quietBuildsSeconds can't be negative: " + path);
            for (var viewpoint : scenario.viewpoints()) {
                validate(viewpoint);
                if (server != null && viewpoint.locate() != null)
                    throw new IllegalArgumentException("Viewpoint " + viewpoint.name() + ": locate needs the integrated server");
            }
            return scenario;
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read scenario " + path, e);
        }
    }

    private static void validate(Viewpoint viewpoint) {
        String problem = null;
        if ((viewpoint.y() == null) == (viewpoint.aboveGround() == null))
            problem = "needs exactly one of y or aboveGround";
        else if (viewpoint.locate() != null && !viewpoint.locate().matches("biome [a-z0-9_.-]+:[a-z0-9_./-]+|structure #?[a-z0-9_.-]+:[a-z0-9_./-]+"))
            problem = "locate must be \"biome <id>\", \"structure <id>\" or \"structure #<tag>\"";
        else if (viewpoint.time() != null && (viewpoint.time() < 0 || viewpoint.time() >= 24000))
            problem = "time must be a time of day, 0 to 23999 ticks";
        else if (viewpoint.motion() != null && !(viewpoint.motion().period() > 0))
            problem = "motion needs a positive period";
        else if (viewpoint.motion() != null && viewpoint.fly() != null)
            problem = "takes motion or fly, not both";
        else if (viewpoint.fly() != null && !(viewpoint.fly().speed() > 0))
            problem = "fly needs a positive speed";
        else if (viewpoint.dimension() != null && !viewpoint.dimension().matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
            problem = "dimension must be an id like minecraft:the_nether";
        // The server searches the overworld, and setup commands run there.
        else if (viewpoint.dimension() != null && (viewpoint.locate() != null || viewpoint.setup() != null || viewpoint.cleanup() != null))
            problem = "takes no locate, setup or cleanup in another dimension";
        else if (viewpoint.before() != null && !ACTIONS.containsAll(viewpoint.before()))
            problem = "before takes only " + ACTIONS;
        else if (viewpoint.setup() != null)
            for (var setup : viewpoint.setup())
                if (setup.command() == null || (setup.u() == null) != (setup.v() == null) || !validRange(setup.u()) || !validRange(setup.v()))
                    problem = "setup needs a command, and u and v together as [from, to, step] with from <= to and a positive step";
        if (problem != null) throw new IllegalArgumentException("Viewpoint " + viewpoint.name() + ": " + problem);
    }

    private static boolean validRange(int[] range) {
        return range == null || (range.length == 3 && range[0] <= range[1] && range[2] > 0);
    }
}
