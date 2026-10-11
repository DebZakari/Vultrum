package dev.vultrum.bench;

import dev.vultrum.VultrumClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The bench's {@code pack-off} and {@code pack-on}: turns Vitrail's shader pack off or on in game the way its settings
 * screen does, by writing {@code vitrail/pack.txt} and reading it again. The run dir's file is put back as it was once
 * the run ends, or the JVM exits. Reflective, like the cull's read of the same file: Vultrum doesn't compile against
 * Vitrail.
 */
final class VitrailPack {
    // saved: original holds the file as the run found it, null if there was none (Vitrail reads that as no pack).
    private static boolean saved, hooked;
    private static byte[] original;
    private static Path file;

    private VitrailPack() { }

    /** Render thread only, outside a frame: Vitrail's reload frees the old pack's GPU resources. */
    static void set(boolean enabled) {
        if (!FabricLoader.getInstance().isModLoaded("vitrail")) {
            VultrumClient.LOGGER.info("[bench] no Vitrail: pack-{} skipped", enabled ? "on" : "off");
            return;
        }
        Path dir = FabricLoader.getInstance().getGameDir();
        try {
            synchronized (VitrailPack.class) {
                if (!saved) {
                    file = dir.resolve("vitrail").resolve("pack.txt");
                    original = Files.exists(file) ? Files.readAllBytes(file) : null;
                    saved = true;
                }
                if (!hooked) {
                    Runtime.getRuntime().addShutdownHook(new Thread(VitrailPack::restore, "vultrum-bench-pack-restore"));
                    hooked = true;
                }
            }
            var packFile = Class.forName("dev.vitrail.settings.PackFile");
            Object chosen = packFile.getMethod("read", Path.class).invoke(null, file);
            chosen = packFile.getMethod("withEnabled", boolean.class).invoke(chosen, enabled);
            packFile.getMethod("write", Path.class, packFile).invoke(null, file, chosen);
            Class.forName("dev.vitrail.render.PackChoice").getMethod("reload", Path.class).invoke(null, dir);
        } catch (IOException | ReflectiveOperationException e) {
            throw new IllegalStateException("Can't switch Vitrail's pack " + (enabled ? "on" : "off"), e);
        }
    }

    /** Puts pack.txt back as the run found it, if a pack action changed it. */
    static synchronized void restore() {
        if (!saved) return;
        try {
            if (original != null) Files.write(file, original);
            else Files.deleteIfExists(file);
            saved = false;
            original = null;
        } catch (IOException e) {
            VultrumClient.LOGGER.error("[bench] can't put {} back", file, e);
        }
    }
}
