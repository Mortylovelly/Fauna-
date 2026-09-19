package com.mortylovelly.fix.fauna;

import com.mortylovelly.fix.FixMod;
import net.fabricmc.fabric.api.biome.v1.BiomeModificationContext;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.fabricmc.fabric.api.biome.v1.ModificationPhase;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.EntityType;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

public final class FaunaDeduplicator {
    private FaunaDeduplicator() {
    }

    public static void register() {
        BiomeModifications.create(new Identifier(FixMod.MOD_ID, "fauna_dedup"))
                .add(
                        ModificationPhase.REMOVALS,
                        BiomeSelectors.all(),
                        context -> {
                            // Alex's Mobs Continued is preferred for these exact overlaps.
                            removeIfPresent(
                                    context,
                                    "alexsmobs",
                                    "naturalist",
                                    "bear",
                                    "catfish",
                                    "elephant",
                                    "rhinoceros",
                                    "rattlesnake"
                            );

                            // Critters and Companions has the preferred versions of these small creatures.
                            removeIfPresent(
                                    context,
                                    "crittersandcompanions",
                                    "faunify",
                                    "dragonfly",
                                    "ladybug",
                                    "beetle",
                                    "leafinsect",
                                    "rolypoly",
                                    "stickbug",
                                    "weevil"
                            );

                            // Keep Critters and Companions versions over Naturalist for these exact duplicates.
                            removeIfPresent(
                                    context,
                                    "crittersandcompanions",
                                    "naturalist",
                                    "dragonfly",
                                    "snail"
                            );

                            // Naturalist already provides a duck, while Hybrid Birds is kept for
                            // the many bird species that are not represented by Naturalist.
                            removeIfPresent(
                                    context,
                                    "naturalist",
                                    "hybrid_birds",
                                    "duck"
                            );

                            // Keep the Mob-Vote-style Friends&Foes crab over the Ecologics crab.
                            removeIfPresent(
                                    context,
                                    "friendsandfoes",
                                    "ecologics",
                                    "crab"
                            );

                            // Keep the more feature-rich/curated implementations and let Wildlife
                            // provide species that the other animal mods do not already cover.
                            removeIfPresent(
                                    context,
                                    "naturalist",
                                    "wildlife",
                                    "bluebird",
                                    "robin",
                                    "sparrow",
                                    "anteater",
                                    "badger",
                                    "boar",
                                    "capybara",
                                    "deer",
                                    "alligator",
                                    "catfish",
                                    "dragonfly",
                                    "snail"
                            );

                            removeIfPresent(
                                    context,
                                    "crittersandcompanions",
                                    "wildlife",
                                    "ferret"
                            );

                            removeIfPresent(
                                    context,
                                    "faunify",
                                    "wildlife",
                                    "hedgehog",
                                    "opossum"
                            );
                        }
                );

        FixMod.LOGGER.info("[Fix] Fauna duplicate-spawn rules registered.");
    }

    private static void removeIfPresent(
            BiomeModificationContext context,
            String preferredMod,
            String duplicateMod,
            String... entityNames
    ) {
        if (!FabricLoader.getInstance().isModLoaded(preferredMod)
                || !FabricLoader.getInstance().isModLoaded(duplicateMod)) {
            return;
        }

        for (String entityName : entityNames) {
            Identifier id = new Identifier(duplicateMod, entityName);
            EntityType<?> type = Registries.ENTITY_TYPE.getOrEmpty(id).orElse(null);

            if (type != null && context.getSpawnSettings().removeSpawnsOfEntityType(type)) {
                FixMod.LOGGER.debug("[Fix] Removed duplicate natural spawn: {}.", id);
            }
        }
    }
}
