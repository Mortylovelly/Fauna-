package com.mortylovelly.fix.mixin;

import com.mortylovelly.fix.biome.BiomeDistributionFix;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeSupplier;
import net.minecraft.world.biome.source.util.MultiNoiseUtil;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.PalettedContainer;
import net.minecraft.world.chunk.ReadableContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies the biome distribution pass after Noisium has populated the biome
 * palette. This avoids conflicting with Noisium's overwrite of populateBiomes.
 */
@Mixin(value = ChunkSection.class, priority = 900)
public abstract class ChunkSectionBiomeMixin {
    @Shadow
    private ReadableContainer<RegistryEntry<Biome>> biomeContainer;

    @Inject(
            method = "populateBiomes",
            at = @At("TAIL"),
            require = 1
    )
    private void fix$populateBiomes(
            BiomeSupplier biomeSupplier,
            MultiNoiseUtil.MultiNoiseSampler sampler,
            int x,
            int y,
            int z,
            CallbackInfo ci
    ) {
        @SuppressWarnings("unchecked")
        PalettedContainer<RegistryEntry<Biome>> palettedContainer =
                (PalettedContainer<RegistryEntry<Biome>>) this.biomeContainer;

        BiomeDistributionFix.process(
                palettedContainer,
                biomeSupplier,
                sampler,
                x,
                y,
                z
        );
    }
}
