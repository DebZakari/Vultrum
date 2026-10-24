package dev.vultrum;

import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
import net.caffeinemc.mods.sodium.api.config.structure.BooleanOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * Vultrum's page in Sodium's video settings ({@code sodium:config_api_user} entry point). Every setting is read
 * at launch, so a change asks for a restart, and the page shows the values the next launch will read.
 */
public final class VultrumOptions implements ConfigEntryPoint {
    /** Changes Sodium has applied since the last save, or null. */
    private VultrumConfig pending;

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        // Sodium crashes the game if registration throws, so an API it may have changed is left alone.
        if (!LaunchHooks.get().sodium()) return;
        boolean hooked = LaunchHooks.get().vitrail();
        StorageEventHandler storage = () -> {
            if (pending != null) VultrumConfig.saveNext(pending);
            pending = null;
        };
        var culling = builder.createOptionGroup()
                .addOption(option(builder, VultrumConfig.OCCLUSION_CULL, hooked, storage, VultrumConfig::occlusionCull, VultrumConfig::withOcclusionCull));
        var shadow = builder.createOptionGroup()
                .setName(Component.translatable("vultrum.options.group.shadow"))
                .addOption(option(builder, VultrumConfig.SHADOW_FACE_CULL, hooked, storage, VultrumConfig::shadowFaceCull, VultrumConfig::withShadowFaceCull))
                .addOption(option(builder, VultrumConfig.SUNLESS_SHADOW, hooked, storage, VultrumConfig::sunlessShadow, VultrumConfig::withSunlessShadow));
        var compatibility = builder.createOptionGroup()
                .setName(Component.translatable("vultrum.options.group.compatibility"))
                .addOption(option(builder, VultrumConfig.PUNCHY_HAND_CACHE, LaunchHooks.get().punchyHandLookupAvailable(), storage,
                        VultrumConfig::punchyHandCache, VultrumConfig::withPunchyHandCache));
        // Need neither Vitrail nor a shader pack: they hold back the client's packets, size Sodium's buffers, spread its
        // chunk removals and reload registration, and move its buffer reclaim off the render thread.
        var loading = builder.createOptionGroup()
                .addOption(option(builder, VultrumConfig.PACKET_BUDGET, true, storage, VultrumConfig::packetBudget, VultrumConfig::withPacketBudget))
                .addOption(option(builder, VultrumConfig.ARENA_CAP, true, storage, VultrumConfig::arenaCap, VultrumConfig::withArenaCap))
                .addOption(option(builder, VultrumConfig.UNLOAD_BUDGET, true, storage, VultrumConfig::unloadBudget, VultrumConfig::withUnloadBudget))
                .addOption(option(builder, VultrumConfig.RECLAIM_THREAD, true, storage, VultrumConfig::reclaimThread, VultrumConfig::withReclaimThread))
                .addOption(option(builder, VultrumConfig.RELOAD_REGISTRATION, true, storage, VultrumConfig::reloadRegistration, VultrumConfig::withReloadRegistration));
        // The logo's symbol, tinted with the logo's ember (Sodium lifts a base colour to at least 0.2 saturation and 0.55 value).
        builder.registerOwnModOptions()
                .setIcon(Identifier.fromNamespaceAndPath("vultrum", "textures/gui/config-icon.png"))
                .setColorTheme(builder.createColorTheme().setBaseThemeRGB(0xFFF5A07A))
                .addPage(builder.createOptionPage()
                        .setName(Component.translatable("vultrum.options.page"))
                        .addOptionGroup(culling)
                        .addOptionGroup(shadow)
                        .addOptionGroup(compatibility))
                .addPage(builder.createOptionPage()
                        .setName(Component.translatable("vultrum.options.page.loading"))
                        .addOptionGroup(loading));
    }

    /** The language key of a setting, from its key in {@code vultrum.properties}. */
    static String langKey(String property) {
        return "vultrum.options." + property.replace('-', '_');
    }

    private BooleanOptionBuilder option(ConfigBuilder builder, String property, boolean hooked, StorageEventHandler storage,
                                        Predicate<VultrumConfig> value, BiFunction<VultrumConfig, Boolean, VultrumConfig> with) {
        String key = property.replace('-', '_');
        return builder.createBooleanOption(Identifier.fromNamespaceAndPath("vultrum", key))
                .setName(Component.translatable(langKey(property)))
                .setTooltip(Component.translatable(langKey(property) + ".tooltip"))
                .setEnabled(hooked)
                .setDefaultValue(value.test(VultrumConfig.DEFAULTS))
                .setFlags(OptionFlag.REQUIRES_GAME_RESTART)
                .setStorageHandler(storage)
                .setBinding(on -> pending = with.apply(pending != null ? pending : VultrumConfig.next(), on),
                        () -> value.test(pending != null ? pending : VultrumConfig.next()));
    }
}
