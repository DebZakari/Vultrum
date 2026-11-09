package dev.vultrum;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class VultrumConfigTest {
    @TempDir Path dir;

    private VultrumConfig loadWith(String content) throws IOException {
        Path file = dir.resolve("vultrum.properties");
        Files.writeString(file, content);
        return VultrumConfig.load(file);
    }

    @Test
    void missingFileIsWrittenWithTheDefaults() throws IOException {
        Path file = dir.resolve("config").resolve("vultrum.properties");
        assertEquals(VultrumConfig.DEFAULTS, VultrumConfig.load(file));
        assertTrue(Files.readString(file).contains("shadow-face-cull=true"));
        assertTrue(Files.readString(file).contains("occlusion-cull=true"));
        assertTrue(Files.readString(file).contains("sunless-shadow=false"));
        assertTrue(Files.readString(file).contains("packet-budget=true"));
        assertTrue(Files.readString(file).contains("arena-cap=true"));
        assertTrue(Files.readString(file).contains("unload-budget=true"));
        assertTrue(Files.readString(file).contains("reclaim-thread=true"));
        assertTrue(Files.readString(file).contains("reload-registration=true"));
        assertTrue(Files.readString(file).contains("punchy-hand-cache=false"));
        assertTrue(Files.readString(file).contains("emf-shadow-skip=false"));
        assertTrue(Files.readString(file).contains("display-shadow-reach=true"));
        assertEquals(VultrumConfig.DEFAULTS, VultrumConfig.load(file), "the written file reads back as the defaults");
    }

    @Test
    void readsTheOffSwitch() throws IOException {
        assertFalse(loadWith("shadow-face-cull=false\n").shadowFaceCull());
        assertFalse(loadWith("shadow-face-cull = FALSE \n").shadowFaceCull());
        assertTrue(loadWith("shadow-face-cull=true\n").shadowFaceCull());
        var cullOff = loadWith("occlusion-cull=false\n");
        assertFalse(cullOff.occlusionCull());
        assertTrue(cullOff.shadowFaceCull(), "each key is read on its own");
        var sunless = loadWith("sunless-shadow=true\n");
        assertTrue(sunless.sunlessShadow());
        assertTrue(sunless.shadowFaceCull());
        assertTrue(sunless.occlusionCull());
        var budgetOff = loadWith("packet-budget=false\n");
        assertFalse(budgetOff.packetBudget());
        assertTrue(budgetOff.occlusionCull());
        var capOff = loadWith("arena-cap=false\n");
        assertFalse(capOff.arenaCap());
        assertTrue(capOff.packetBudget());
        var unloadsOff = loadWith("unload-budget=false\n");
        assertFalse(unloadsOff.unloadBudget());
        assertTrue(unloadsOff.arenaCap());
        var threadOff = loadWith("reclaim-thread=false\n");
        assertFalse(threadOff.reclaimThread());
        assertTrue(threadOff.unloadBudget());
        var reloadOff = loadWith("reload-registration=false\n");
        assertFalse(reloadOff.reloadRegistration());
        assertTrue(reloadOff.reclaimThread());
        assertTrue(loadWith("punchy-hand-cache=true\n").punchyHandCache());
        assertFalse(loadWith("punchy-hand-cache=false\n").punchyHandCache());
        assertTrue(loadWith("emf-shadow-skip=true\n").emfShadowSkip());
        assertFalse(loadWith("emf-shadow-skip=false\n").emfShadowSkip());
        assertFalse(loadWith("display-shadow-reach=false\n").displayShadowReach());
        assertTrue(loadWith("display-shadow-reach=true\n").displayShadowReach());
    }

    @Test
    void missingOrInvalidValuesFallBackToTheDefault() throws IOException {
        assertTrue(loadWith("# nothing set\n").shadowFaceCull());
        assertTrue(loadWith("shadow-face-cull=nope\n").shadowFaceCull());
        assertTrue(loadWith("shadow-face-cull=false\n").occlusionCull(), "a file from before the key existed keeps the cull on");
        assertFalse(loadWith("shadow-face-cull=false\nocclusion-cull=false\n").sunlessShadow(), "off unless asked for");
        assertFalse(loadWith("sunless-shadow=yes\n").sunlessShadow());
        assertTrue(loadWith("sunless-shadow=true\n").packetBudget(), "a file from before the key existed gets the budget");
        assertTrue(loadWith("packet-budget=false\n").arenaCap(), "a file from before the key existed gets the cap");
        assertTrue(loadWith("arena-cap=false\n").unloadBudget(), "a file from before the key existed gets the unload budget");
        assertTrue(loadWith("unload-budget=false\n").reclaimThread(), "a file from before the key existed gets the reclaim thread");
        assertTrue(loadWith("reclaim-thread=false\n").reloadRegistration(), "an older file gets reload registration");
        assertTrue(loadWith("reload-registration=invalid\n").reloadRegistration());
        assertFalse(loadWith("reload-registration=true\n").punchyHandCache());
        assertFalse(loadWith("punchy-hand-cache=invalid\n").punchyHandCache());
        assertFalse(loadWith("punchy-hand-cache=true\n").emfShadowSkip(), "off unless asked for");
        assertFalse(loadWith("emf-shadow-skip=invalid\n").emfShadowSkip());
        assertTrue(loadWith("emf-shadow-skip=true\n").displayShadowReach(), "an older file gets the display limit");
        assertTrue(loadWith("display-shadow-reach=invalid\n").displayShadowReach());
    }

    @Test
    void anOlderFileGainsTheNewSettings() throws IOException {
        var config = loadWith("shadow-face-cull=false\n");
        Path file = dir.resolve("vultrum.properties");
        assertTrue(Files.readString(file).contains("occlusion-cull=true"));
        assertTrue(Files.readString(file).contains("sunless-shadow=false"));
        assertTrue(Files.readString(file).contains("packet-budget=true"));
        assertTrue(Files.readString(file).contains("arena-cap=true"));
        assertTrue(Files.readString(file).contains("unload-budget=true"));
        assertTrue(Files.readString(file).contains("reclaim-thread=true"));
        assertTrue(Files.readString(file).contains("reload-registration=true"));
        assertTrue(Files.readString(file).contains("punchy-hand-cache=false"));
        assertTrue(Files.readString(file).contains("emf-shadow-skip=false"));
        assertTrue(Files.readString(file).contains("display-shadow-reach=true"));
        assertEquals(config, VultrumConfig.load(file), "the rewrite keeps the values it read");
    }

    @Test
    void writtenSettingsReadBack() {
        Path file = dir.resolve("vultrum.properties");
        for (int bits = 0; bits < 2048; bits++) {
            var config = new VultrumConfig((bits & 1) != 0, (bits & 2) != 0, (bits & 4) != 0, (bits & 8) != 0, (bits & 16) != 0, (bits & 32) != 0,
                    (bits & 64) != 0, (bits & 128) != 0, (bits & 256) != 0, (bits & 512) != 0, (bits & 1024) != 0);
            VultrumConfig.write(file, config);
            assertEquals(config, VultrumConfig.load(file));
        }
    }

    @Test
    void copiesChangeOneSetting() {
        var config = new VultrumConfig(true, false, true, false, true, false, true, false);
        assertEquals(new VultrumConfig(false, false, true, false, true, false, true, false), config.withShadowFaceCull(false));
        assertEquals(new VultrumConfig(true, true, true, false, true, false, true, false), config.withOcclusionCull(true));
        assertEquals(new VultrumConfig(true, false, false, false, true, false, true, false), config.withSunlessShadow(false));
        assertEquals(new VultrumConfig(true, false, true, true, true, false, true, false), config.withPacketBudget(true));
        assertEquals(new VultrumConfig(true, false, true, false, false, false, true, false), config.withArenaCap(false));
        assertEquals(new VultrumConfig(true, false, true, false, true, true, true, false), config.withUnloadBudget(true));
        assertEquals(new VultrumConfig(true, false, true, false, true, false, false, false), config.withReclaimThread(false));
        assertEquals(new VultrumConfig(true, false, true, false, true, false, true, true), config.withReloadRegistration(true));
        assertEquals(new VultrumConfig(true, false, true, false, true, false, true, false, true, false, false), config.withPunchyHandCache(true));
        assertEquals(new VultrumConfig(true, false, true, false, true, false, true, false, false, true, false), config.withEmfShadowSkip(true));
        assertEquals(new VultrumConfig(true, false, true, false, true, false, true, false, false, false, true), config.withDisplayShadowReach(true));
        assertTrue(config.withDisplayShadowReach(true).withEmfShadowSkip(true).displayShadowReach());
        assertTrue(config.withDisplayShadowReach(true).withPunchyHandCache(true).displayShadowReach());
        assertTrue(config.withEmfShadowSkip(true).withDisplayShadowReach(true).emfShadowSkip());
        assertTrue(config.withEmfShadowSkip(true).withPunchyHandCache(true).emfShadowSkip());
        assertTrue(config.withPunchyHandCache(true).withEmfShadowSkip(true).punchyHandCache());
        var skipping = config.withEmfShadowSkip(true);
        assertTrue(skipping.withShadowFaceCull(false).emfShadowSkip());
        assertTrue(skipping.withOcclusionCull(true).emfShadowSkip());
        assertTrue(skipping.withSunlessShadow(false).emfShadowSkip());
        assertTrue(skipping.withPacketBudget(true).emfShadowSkip());
        assertTrue(skipping.withArenaCap(false).emfShadowSkip());
        assertTrue(skipping.withUnloadBudget(true).emfShadowSkip());
        assertTrue(skipping.withReclaimThread(false).emfShadowSkip());
        assertTrue(skipping.withReloadRegistration(true).emfShadowSkip());
        var cached = config.withPunchyHandCache(true);
        assertTrue(cached.withShadowFaceCull(false).punchyHandCache());
        assertTrue(cached.withOcclusionCull(true).punchyHandCache());
        assertTrue(cached.withSunlessShadow(false).punchyHandCache());
        assertTrue(cached.withPacketBudget(true).punchyHandCache());
        assertTrue(cached.withArenaCap(false).punchyHandCache());
        assertTrue(cached.withUnloadBudget(true).punchyHandCache());
        assertTrue(cached.withReclaimThread(false).punchyHandCache());
        assertTrue(cached.withReloadRegistration(true).punchyHandCache());
    }

    @Test
    void aCompleteFileIsLeftAlone() throws IOException {
        String content = "shadow-face-cull=false\nocclusion-cull=false\nsunless-shadow=true\npacket-budget=false\narena-cap=false\nunload-budget=false\n"
                + "reclaim-thread=false\nreload-registration=false\npunchy-hand-cache=false\nemf-shadow-skip=false\ndisplay-shadow-reach=false\n";
        loadWith(content);
        assertEquals(content, Files.readString(dir.resolve("vultrum.properties")));
    }
}
