package dev.vultrum;

import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import org.junit.jupiter.api.Test;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class VultrumMixinPluginTest {
    private static boolean allows(VultrumMixinPlugin.Range range, String version) throws VersionParsingException {
        return VultrumMixinPlugin.inRange(Version.parse(version), range.range());
    }

    /** Bumping the Sodium build dependency without widening SODIUM fails here, instead of silently dropping the Sodium mixins in game. */
    @Test
    void sodiumRangeCoversTheSodiumWeBuildAgainst() throws Exception {
        var jar = Path.of(ModelQuadFacing.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (var fs = FileSystems.newFileSystem(jar)) {
            var version = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"").matcher(Files.readString(fs.getPath("fabric.mod.json")));
            assertTrue(version.find());
            assertTrue(allows(VultrumMixinPlugin.SODIUM, version.group(1)), version.group(1));
        }
    }

    @Test
    void sodiumRangeCoversThe093Line() throws VersionParsingException {
        assertTrue(allows(VultrumMixinPlugin.SODIUM, "0.9.3-alpha.1+mc26.3"), "the tested build");
        assertTrue(allows(VultrumMixinPlugin.SODIUM, "0.9.3-beta.2+mc26.3"));
        assertTrue(allows(VultrumMixinPlugin.SODIUM, "0.9.3+mc26.3"));
        assertFalse(allows(VultrumMixinPlugin.SODIUM, "0.9.2+mc26.3"));
        assertFalse(allows(VultrumMixinPlugin.SODIUM, "0.9.4-alpha.1+mc26.3"), "a 0.9.4 pre-release is the next line");
        assertFalse(allows(VultrumMixinPlugin.SODIUM, "0.9.4+mc26.3"));
    }

    @Test
    void vitrailRangeCoversThe012And013Lines() throws VersionParsingException {
        assertTrue(allows(VultrumMixinPlugin.VITRAIL, "0.12.0-beta-blockcracks3"), "a tested build");
        assertTrue(allows(VultrumMixinPlugin.VITRAIL, "0.12.0"));
        assertTrue(allows(VultrumMixinPlugin.VITRAIL, "0.12.5"));
        assertTrue(allows(VultrumMixinPlugin.VITRAIL, "0.13.0-beta"), "a tested build");
        assertTrue(allows(VultrumMixinPlugin.VITRAIL, "0.13.0-beta+mc26.3"));
        assertTrue(allows(VultrumMixinPlugin.VITRAIL, "0.13.0"));
        assertFalse(allows(VultrumMixinPlugin.VITRAIL, "0.11.9"));
        assertFalse(allows(VultrumMixinPlugin.VITRAIL, "0.14.0-alpha.1"), "a 0.14 pre-release is the next line");
        assertFalse(allows(VultrumMixinPlugin.VITRAIL, "0.14.0"));
    }

    @Test
    void timelineModesApplyTheirHooks() {
        var chunk = "dev.vultrum.mixin.ClientChunkTimelineMixin";
        var storage = "dev.vultrum.mixin.ClientChunkStorageTimelineMixin";
        var light = "dev.vultrum.mixin.ClientChunkLightTimelineMixin";
        var lightData = "dev.vultrum.mixin.ClientLightTimelineMixin";
        var dirty = "dev.vultrum.mixin.SodiumDirtyTimelineMixin";
        var other = "dev.vultrum.mixin.GameRendererMixin";
        for (var mixin : new String[] {chunk, storage, light, lightData, dirty, other})
            assertTrue(VultrumMixinPlugin.timelineApplies(mixin, true, true), "full: " + mixin);
        for (var mixin : new String[] {chunk, storage, other})
            assertTrue(VultrumMixinPlugin.timelineApplies(mixin, true, false), "chunks: " + mixin);
        for (var mixin : new String[] {light, lightData, dirty})
            assertFalse(VultrumMixinPlugin.timelineApplies(mixin, true, false), "chunks: " + mixin);
        for (var mixin : new String[] {chunk, storage, light, lightData, dirty})
            assertFalse(VultrumMixinPlugin.timelineApplies(mixin, false, false), "off: " + mixin);
        assertTrue(VultrumMixinPlugin.timelineApplies(other, false, false), "off: " + other);
    }
}
