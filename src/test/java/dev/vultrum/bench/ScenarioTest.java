package dev.vultrum.bench;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ScenarioTest {
    @Test
    void setupGridCoversEveryPointInclusive() {
        var setup = new Scenario.Setup("summon cow ^{u} ^ ^{v} at {x} {y}", new int[] {-2, 2, 2}, new int[] {4, 5, 1});
        var commands = setup.commands(Map.of("x", "1.50", "y", "64"));
        assertEquals(6, commands.size());
        assertEquals("summon cow ^-2 ^ ^4 at 1.50 64", commands.getFirst());
        assertEquals("summon cow ^2 ^ ^5 at 1.50 64", commands.getLast());
    }

    @Test
    void setupWithoutGridRunsOnce() {
        var setup = new Scenario.Setup("fill {x} {y} {z} {x} {y} {z} air", null, null);
        assertEquals(List.of("fill 1 2 3 1 2 3 air"), setup.commands(Map.of("x", "1", "y", "2", "z", "3")));
    }

    @Test
    void rejectsInvalidViewpoints(@TempDir Path dir) throws IOException {
        // Each viewpoint, and the rule it breaks.
        Map<String, String> invalid = Map.ofEntries(
                Map.entry("{\"name\":\"a\",\"aboveGround\":0,\"motion\":{\"turn\":1,\"step\":1,\"period\":1},\"fly\":{\"speed\":1}}", "not both"),
                Map.entry("{\"name\":\"a\",\"aboveGround\":0,\"fly\":{\"speed\":0}}", "positive speed"),
                Map.entry("{\"name\":\"a\",\"y\":100,\"time\":24000}", "time of day"),
                Map.entry("{\"name\":\"a\",\"y\":100,\"time\":-1}", "time of day"),
                Map.entry("{\"name\":\"a\",\"y\":100,\"locate\":\"structure ancient_city\"}", "locate must be"),
                Map.entry("{\"name\":\"a\",\"aboveGround\":0,\"setup\":[{\"command\":\"say {u}\",\"u\":[0,4,0],\"v\":[0,4,1]}]}", "positive step"),
                Map.entry("{\"name\":\"a\",\"aboveGround\":0,\"setup\":[{\"command\":\"say {u}\",\"u\":[4,0,1],\"v\":[0,4,1]}]}", "from <= to"),
                Map.entry("{\"name\":\"a\",\"aboveGround\":0,\"setup\":[{\"command\":\"say {u}\",\"u\":[0,4,1]}]}", "u and v together"),
                Map.entry("{\"name\":\"a\",\"aboveGround\":0,\"dimension\":\"the_nether\"}", "an id like"),
                Map.entry("{\"name\":\"a\",\"aboveGround\":0,\"dimension\":\"minecraft:the_nether\",\"locate\":\"biome minecraft:crimson_forest\"}", "in another dimension"),
                Map.entry("{\"name\":\"a\",\"aboveGround\":0,\"dimension\":\"minecraft:the_end\",\"cleanup\":[\"say hi\"]}", "in another dimension"),
                Map.entry("{\"name\":\"a\",\"aboveGround\":0,\"before\":[\"reload\",\"pack_off\"]}", "before takes only"));
        for (var entry : invalid.entrySet()) {
            Path file = write(dir, entry.getKey());
            var error = assertThrows(IllegalArgumentException.class, () -> Scenario.load(file), entry.getKey());
            assertTrue(error.getMessage().contains(entry.getValue()), error.getMessage());
        }
    }

    @Test
    void rejectsInvalidServers(@TempDir Path dir) throws IOException {
        String viewpoint = "[{\"name\":\"a\",\"aboveGround\":0}]", located = "[{\"name\":\"a\",\"aboveGround\":0,\"locate\":\"biome minecraft:jungle\"}]";
        String server = "\"server\":{\"address\":\"127.0.0.1\",\"rcon\":\"127.0.0.1:25575\",\"rconPassword\":\"p\"}";
        // Each scenario's fields, and the rule they break.
        Map<String, String> invalid = Map.of(
                "\"viewpoints\":" + viewpoint, "a world or a server",
                "\"server\":{\"address\":\"127.0.0.1\",\"rcon\":\"127.0.0.1\",\"rconPassword\":\"p\"},\"viewpoints\":" + viewpoint, "rcon host:port",
                "\"server\":{\"address\":\"127.0.0.1\",\"rcon\":\"127.0.0.1:25575\",\"rconPassword\":\"\"},\"viewpoints\":" + viewpoint, "rconPassword",
                "\"server\":{\"rcon\":\"127.0.0.1:25575\",\"rconPassword\":\"p\"},\"viewpoints\":" + viewpoint, "an address",
                server + ",\"viewpoints\":" + located, "locate needs the integrated server");
        for (var entry : invalid.entrySet()) {
            Path file = dir.resolve("s.json");
            Files.writeString(file, "{\"renderDistances\":[16]," + entry.getKey() + "}");
            var error = assertThrows(IllegalArgumentException.class, () -> Scenario.load(file), entry.getKey());
            assertTrue(error.getMessage().contains(entry.getValue()), error.getMessage());
        }
        Path file = dir.resolve("s.json");
        Files.writeString(file, "{\"renderDistances\":[16]," + server + ",\"viewpoints\":" + viewpoint + "}");
        assertEquals("127.0.0.1:25575", Scenario.load(file).server().rcon());
    }

    @Test
    void acceptsATimeAStructureIdAndSetupAtAnAbsoluteHeight(@TempDir Path dir) throws IOException {
        var viewpoint = Scenario.load(write(dir, "{\"name\":\"a\",\"y\":-40,\"time\":1000,\"locate\":\"structure minecraft:ancient_city\","
                + "\"setup\":[{\"command\":\"fill {x} {y} {z} {x} {y} {z} air\"}]}")).viewpoints().getFirst();
        assertEquals(1000, viewpoint.time());
        assertEquals("structure minecraft:ancient_city", viewpoint.locate());
    }

    @Test
    void acceptsCleanupWithoutSetup(@TempDir Path dir) throws IOException {
        var scenario = Scenario.load(write(dir, "{\"name\":\"a\",\"aboveGround\":0,\"cleanup\":[\"kill @e[tag=t]\"]}"));
        assertEquals(List.of("kill @e[tag=t]"), scenario.viewpoints().getFirst().cleanup());
    }

    @Test
    void quietBuildsIsOffByDefaultAndNeverNegative(@TempDir Path dir) throws IOException {
        assertEquals(0, Scenario.load(write(dir, "{\"name\":\"a\",\"y\":100}")).quietBuildsSeconds());
        Path file = dir.resolve("s.json");
        Files.writeString(file, "{\"world\":\"w\",\"quietBuildsSeconds\":-1,\"renderDistances\":[16],\"viewpoints\":[{\"name\":\"a\",\"y\":100}]}");
        var error = assertThrows(IllegalArgumentException.class, () -> Scenario.load(file));
        assertTrue(error.getMessage().contains("can't be negative"), error.getMessage());
    }

    @Test
    void shippedScenariosLoad() throws IOException {
        String scenarios = System.getProperty("vultrum.scenarios");
        assumeTrue(scenarios != null, "vultrum.scenarios is set by the Gradle test task");
        try (var files = Files.list(Path.of(scenarios))) {
            for (Path file : files.toList()) assertNotNull(Scenario.load(file), file.toString());
        }
    }

    private static Path write(Path dir, String viewpoint) throws IOException {
        Path file = dir.resolve("s.json");
        Files.writeString(file, "{\"world\":\"w\",\"renderDistances\":[16],\"viewpoints\":[" + viewpoint + "]}");
        return file;
    }
}
