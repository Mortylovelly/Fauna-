package com.mortylovelly.fix.mixin;

import com.mortylovelly.fix.FixMod;
import com.google.gson.JsonElement;
import net.minecraft.recipe.RecipeManager;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.profiler.Profiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

@Mixin(RecipeManager.class)
public abstract class RecipeManagerFixMixin {
    private static final Identifier BROKEN_NATURALIST_DELIGHT_RECIPE =
            new Identifier("naturalistdelight", "piece_of_cattail");

    @Inject(method = "apply", at = @At("HEAD"))
    private void fix$removeKnownBrokenNaturalistDelightRecipe(
            Map<Identifier, JsonElement> map,
            ResourceManager resourceManager,
            Profiler profiler,
            CallbackInfo callbackInfo
    ) {
        JsonElement recipe = map.get(BROKEN_NATURALIST_DELIGHT_RECIPE);

        if (recipe != null && recipe.toString().contains("naturalist:cattail")) {
            map.remove(BROKEN_NATURALIST_DELIGHT_RECIPE);

            FixMod.LOGGER.warn(
                    "[Fix] Removed broken recipe {} because it references the removed naturalist:cattail item.",
                    BROKEN_NATURALIST_DELIGHT_RECIPE
            );
        }
    }
}
