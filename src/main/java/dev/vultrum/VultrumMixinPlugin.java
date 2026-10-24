package dev.vultrum;

import dev.vultrum.compat.PunchyHandLookup;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/** Mixin adapter for launch selection and the guarded post-merge compatibility transform. */
public final class VultrumMixinPlugin implements IMixinConfigPlugin {
    // Not VultrumClient.LOGGER: plugins load before mod initialization.
    private static final Logger LOGGER = LoggerFactory.getLogger("Vultrum");
    private final LaunchHooks selection;

    public VultrumMixinPlugin() { this(null); }
    VultrumMixinPlugin(LaunchHooks selection) { this.selection = selection; }

    private LaunchHooks hooks() { return selection != null ? selection : LaunchHooks.get(); }

    @Override
    public void onLoad(String mixinPackage) {
        hooks().warnings().forEach(LOGGER::warn);
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return hooks().applies(targetClassName, mixinClassName);
    }

    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        if (LaunchHooks.PUNCHY_HAND_LOOKUP.equals(mixinClassName)) {
            if (PunchyHandLookup.apply(targetClass)) LOGGER.info("Punchy hand-renderer lookup cache is enabled");
            else LOGGER.warn("Punchy hand-renderer lookup cache skipped: the resolver layout is unsupported");
        }
    }
}
