package com.mortylovelly.fix;

import com.mortylovelly.fix.biome.BiomeDistributionFix;
import com.mortylovelly.fix.fauna.FaunaDeduplicator;
import com.mortylovelly.fix.fix.FaunifyAttributeFix;
import net.fabricmc.api.ModInitializer;
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
        registerCompatibilityResourcePack();

        if (FabricLoader.getInstance().isModLoaded("faunify")) {
            FaunifyAttributeFix.registerMissingAttributes();
        }

        FaunaDeduplicator.register();
        BiomeDistributionFix.logSettings();

        LOGGER.info("[Fix] Fix - Modpack Compatibility & Balance loaded for Minecraft 1.20.1.");
        logModState("naturalist", "Naturalist");
        logModState("alexsmobs", "Alex's Mobs Continued");
        logModState("wildlife", "Wildlife");
        logModState("crittersandcompanions", "Critters and Companions");
        logModState("faunify", "Faunify");
        logModState("friendsandfoes", "Friends&Foes");
        logModState("ecologics", "Ecologics");
        logModState("hybrid_birds", "Hybrid Birds");
        logModState("naturalistdelight", "Naturalist Delight");
        logModState("yungscavebiomes", "YUNG's Cave Biomes");
        logModState("cavebiomesdelight", "Cave Biomes Delight");
        logModState("distanthorizons", "Distant Horizons");
        logModState("minecartmagic", "Minecart Magic");
    }

    private static void registerCompatibilityResourcePack() {
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
                                    "[Fix] Compatibility resource pack {}.",
                                    registered ? "registered" : "was already registered"
                            );
                        },
                        () -> LOGGER.error("[Fix] Could not find our own mod container.")
                );
    }

    private static void logModState(String modId, String label) {
        boolean loaded = FabricLoader.getInstance().isModLoaded(modId);
        LOGGER.info("[Fix] {}: {}", label, loaded ? "present" : "not present");
    }
}
