package com.mortylovelly.fix;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.ResourcePackActivationType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FixMod implements ModInitializer {
    public static final String MOD_ID = "fix";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final Identifier COMPAT_PACK_ID = new Identifier(MOD_ID, "compat_fixes");

    @Override
    public void onInitialize() {
        FabricLoader.getInstance()
                .getModContainer(MOD_ID)
                .ifPresentOrElse(
                        container -> {
                            boolean registered = ResourceManagerHelper.registerBuiltinResourcePack(
                                    COMPAT_PACK_ID,
                                    container,
                                    ResourcePackActivationType.ALWAYS_ENABLED
                            );

                            LOGGER.info(
                                    "[Fix] Compatibility resource pack {}",
                                    registered ? "registered" : "was already registered"
                            );
                        },
                        () -> LOGGER.error("[Fix] Could not find our own mod container")
                );

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            LOGGER.info("[Fix] Fix - Modpack Compatibility Fixes loaded for Minecraft 1.20.1.");

            logModState("naturalist", "Naturalist compatibility target");
            logModState("naturalistdelight", "Naturalist Delight compatibility target");
            logModState("yungscavebiomes", "YUNG's Cave Biomes compatibility target");
            logModState("cavebiomesdelight", "Cave Biomes Delight compatibility target");
            logModState("distanthorizons", "Distant Horizons diagnostics target");
            logModState("minecartmagic", "Minecart Magic diagnostics target");
        });

        LOGGER.info("[Fix] Initial compatibility fixes registered.");
    }

    private static void logModState(String modId, String label) {
        boolean loaded = FabricLoader.getInstance().isModLoaded(modId);
        LOGGER.info("[Fix] {}: {}", label, loaded ? "present" : "not present");
    }
}
