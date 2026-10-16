package dev.vultrum;

import dev.vultrum.bench.Bench;
import dev.vultrum.cull.GpuOcclusionCull;
import dev.vultrum.cull.ShadowPass;
import dev.vultrum.upload.ArenaCap;
import dev.vultrum.upload.ReclaimThread;
import dev.vultrum.upload.ColumnLifecycle;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class VultrumClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("Vultrum");

    @Override
    public void onInitializeClient() {
        LOGGER.info("Vultrum loaded");
        var config = VultrumConfig.get();
        ShadowPass.configure(config.shadowFaceCull(), config.sunlessShadow(), VultrumMixinPlugin.supports("dev.vitrail."));
        GpuOcclusionCull.logState();
        LOGGER.info("Arena cap {}", ArenaCap.on() ? "on, " + (ArenaCap.CAP_BYTES >> 20) + " MiB" : "off");
        LOGGER.info("Unload budget {}", ColumnLifecycle.unloadBudgetOn() ? "on" : "off");
        ReclaimThread.start();
        LOGGER.info("Reclaim thread {}", ReclaimThread.on() ? "on" : "off");
        Bench.init();
    }
}
