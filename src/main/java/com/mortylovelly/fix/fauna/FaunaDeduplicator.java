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
                            int removed = 0;

                            removed += removeIfPresent(context, "alexsmobs", "naturalist",
                                    "bear", "catfish", "elephant", "rhinoceros", "rattlesnake");

                            removed += removeIfPresent(context, "crittersandcompanions", "faunify",
                                    "dragonfly", "ladybug", "beetle", "leafinsect",
                                    "rolypoly", "stickbug", "weevil");

                            removed += removeIfPresent(context, "naturalist", "hybrid_birds",
                                    "duck");

                            removed += removeIfPresent(context, "friendsandfoes", "ecologics",
                                    "crab");

                            if (removed > 0) {
                                FixMod.LOGGER.debug(
                                        "[Fix] Removed {} duplicate natural spawn entries from a biome.",
                                        removed
                                );
                            }
                        }
                );

        FixMod.LOGGER.info("[Fix] Fauna duplicate-spawn rules registered.");
    }

    private static int removeIfPresent(
            BiomeModificationContext context,
            String preferredMod,
            String duplicateMod,
            String... entityNames
    ) {
        if (!FabricLoader.getInstance().isModLoaded(preferredMod)
                || !FabricLoader.getInstance().isModLoaded(duplicateMod)) {
            return 0;
        }

        int removed = 0;

        for (String entityName : entityNames) {
            Identifier id = new Identifier(duplicateMod, entityName);

            EntityType<?> type = Registries.ENTITY_TYPE.getOrEmpty(id).orElse(null);
            if (type != null && context.getSpawnSettings().removeSpawnsOfEntityType(type)) {
                removed++;
            }
        }

        return removed;
    }
}
