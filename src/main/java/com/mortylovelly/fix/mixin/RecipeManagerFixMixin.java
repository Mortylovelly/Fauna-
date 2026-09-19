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
        if (map.remove(BROKEN_NATURALIST_DELIGHT_RECIPE) != null) {
            FixMod.LOGGER.warn(
                    "[Fix] Removed broken recipe {} because the installed Naturalist version no longer provides naturalist:cattail.",
                    BROKEN_NATURALIST_DELIGHT_RECIPE
            );
        }
    }
}
