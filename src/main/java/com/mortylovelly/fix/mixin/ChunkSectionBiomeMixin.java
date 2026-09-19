package com.mortylovelly.fix.mixin;

import com.mortylovelly.fix.biome.BiomeDistributionFix;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeSupplier;
import net.minecraft.world.biome.source.util.MultiNoiseUtil;
import net.minecraft.world.chunk.ChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ChunkSection.class)
public abstract class ChunkSectionBiomeMixin {
    @Redirect(
            method = "populateBiomes",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/biome/source/BiomeSupplier;getBiome(IIILnet/minecraft/world/biome/source/util/MultiNoiseUtil$MultiNoiseSampler;)Lnet/minecraft/registry/entry/RegistryEntry;"
            )
    )
    private RegistryEntry<Biome> fix$normalizeBiome(
            BiomeSupplier supplier,
            int x,
            int y,
            int z,
            MultiNoiseUtil.MultiNoiseSampler sampler
    ) {
        return BiomeDistributionFix.sample(supplier, x, y, z, sampler);
    }
}
