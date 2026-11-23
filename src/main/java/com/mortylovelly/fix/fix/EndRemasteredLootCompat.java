package com.mortylovelly.fix.fix;

import net.fabricmc.fabric.api.loot.v2.LootTableEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.loot.LootPool;
import net.minecraft.loot.entry.LootTableEntry;
import net.minecraft.util.Identifier;

/**
 * Bridges End Remastered's existing eye loot pools into the custom chest tables
 * used by the YUNG structure replacements in this modpack.
 *
 * The nested End Remastered loot tables are reused unchanged, so their configured
 * eye drop chances remain authoritative.
 */
public final class EndRemasteredLootCompat {
    private static final Identifier BETTER_DESERT_TEMPLE_TREASURE =
            new Identifier("betterdeserttemples", "chests/tomb_pharaoh");
    private static final Identifier BETTER_JUNGLE_TEMPLE_TREASURE =
            new Identifier("betterjungletemples", "chests/treasure");
    private static final Identifier BETTER_FORTRESS_OBSIDIAN =
            new Identifier("betterfortresses", "chests/obsidian");

    private static final Identifier ENDREM_OLD_EYE =
            new Identifier("endrem", "minecraft/chests/desert_pyramid");
    private static final Identifier ENDREM_ROGUE_EYE =
            new Identifier("endrem", "minecraft/chests/jungle_temple");
    private static final Identifier ENDREM_NETHER_EYE =
            new Identifier("endrem", "minecraft/chests/nether_bridge");

    private EndRemasteredLootCompat() {
    }

    public static void register() {
        if (!FabricLoader.getInstance().isModLoaded("endrem")) {
            return;
        }

        final boolean hasBetterDesertTemples =
                FabricLoader.getInstance().isModLoaded("betterdeserttemples");
        final boolean hasBetterJungleTemples =
                FabricLoader.getInstance().isModLoaded("betterjungletemples");
        final boolean hasBetterFortresses =
                FabricLoader.getInstance().isModLoaded("betterfortresses");

        if (!hasBetterDesertTemples && !hasBetterJungleTemples && !hasBetterFortresses) {
            return;
        }

        LootTableEvents.MODIFY.register((resourceManager, lootManager, id, table, setter) -> {
            if (hasBetterDesertTemples && BETTER_DESERT_TEMPLE_TREASURE.equals(id)) {
                addEndRemasteredPool(table, ENDREM_OLD_EYE);
            }
            if (hasBetterJungleTemples && BETTER_JUNGLE_TEMPLE_TREASURE.equals(id)) {
                addEndRemasteredPool(table, ENDREM_ROGUE_EYE);
            }
            if (hasBetterFortresses && BETTER_FORTRESS_OBSIDIAN.equals(id)) {
                addEndRemasteredPool(table, ENDREM_NETHER_EYE);
            }
        });
    }

    private static void addEndRemasteredPool(net.minecraft.loot.LootTable.Builder table, Identifier endRemasteredLootTable) {
        table.pool(LootPool.builder().with(LootTableEntry.builder(endRemasteredLootTable)));
    }
}
