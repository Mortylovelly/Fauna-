package com.mortylovelly.fix.fix;

import com.mortylovelly.fix.FixMod;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.DefaultAttributeRegistry;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

public final class FaunifyAttributeFix {
    private static final String MOD_ID = "faunify";

    private static final String[] ENTITY_IDS = {
            "weasel",
            "fennec",
            "chinchilla",
            "hedgehog",
            "ringtailcat",
            "opossum",
            "mouse",
            "silkmoth",
            "silkworm",
            "beetle",
            "ladybug",
            "weevil",
            "beefly",
            "mantis",
            "dragonfly",
            "lacewing",
            "stickbug",
            "leafinsect",
            "grasshopper",
            "millipede_head",
            "rolypoly",
            "leafsheep"
    };

    private FaunifyAttributeFix() {
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void registerMissingAttributes() {
        if (!FabricLoader.getInstance().isModLoaded(MOD_ID)) {
            return;
        }

        int fixed = 0;

        for (String entityId : ENTITY_IDS) {
            Identifier id = new Identifier(MOD_ID, entityId);
            EntityType<?> type = Registries.ENTITY_TYPE.getOrEmpty(id).orElse(null);

            if (type == null) {
                continue;
            }

            if (!DefaultAttributeRegistry.hasDefinitionFor(type)) {
                FabricDefaultAttributeRegistry.register(
                        (EntityType) type,
                        MobEntity.createMobAttributes()
                );
                fixed++;

                FixMod.LOGGER.warn(
                        "[Fix] Added missing default attributes for {}.",
                        id
                );
            }
        }

        if (fixed > 0) {
            FixMod.LOGGER.warn(
                    "[Fix] Repaired {} Faunify entity attribute registrations.",
                    fixed
            );
        } else {
            FixMod.LOGGER.info("[Fix] Faunify entity attributes are already registered.");
        }
    }
}
