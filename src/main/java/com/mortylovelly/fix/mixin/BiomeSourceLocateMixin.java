package com.mortylovelly.fix.mixin;

import com.mortylovelly.fix.biome.BiomeDistributionFix;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.biome.source.util.MultiNoiseUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Makes vanilla biome-source searches apply the same macro-region lookup as
 * the chunk palette. Optional redirects cover the BiomeSource locateBiome
 * overloads used by vanilla commands; overloads that delegate to another
 * overload simply leave their redirect unused.
 */
@Mixin(BiomeSource.class)
public abstract class BiomeSourceLocateMixin {
    private static final String GET_BIOME =
            "Lnet/minecraft/world/biome/source/BiomeSource;getBiome(IIIL" +
            "Lnet/minecraft/world/biome/source/util/MultiNoiseUtil$MultiNoiseSampler;)" +
            "Lnet/minecraft/registry/entry/RegistryEntry;";

    @Redirect(
            method = "locateBiome(IIIIILjava/util/function/Predicate;" +
                    "Lnet/minecraft/util/math/random/Random;Z" +
                    "Lnet/minecraft/world/biome/source/util/MultiNoiseUtil$MultiNoiseSampler;)" +
                    "Lcom/mojang/datafixers/util/Pair;",
            at = @At(value = "INVOKE", target = GET_BIOME),
            require = 0
    )
    private RegistryEntry<Biome> fix$locateBiomeWithIntervals(
            BiomeSource source,
            int quartX,
            int quartY,
            int quartZ,
            MultiNoiseUtil.MultiNoiseSampler sampler
    ) {
        return BiomeDistributionFix.getDistributedBiomeAtQuart(
                quartX, quartY, quartZ, source::getBiome, sampler
        );
    }

    @Redirect(
            method = "locateBiome(IIIILjava/util/function/Predicate;" +
                    "Lnet/minecraft/util/math/random/Random;" +
                    "Lnet/minecraft/world/biome/source/util/MultiNoiseUtil$MultiNoiseSampler;)" +
                    "Lcom/mojang/datafixers/util/Pair;",
            at = @At(value = "INVOKE", target = GET_BIOME),
            require = 0
    )
    private RegistryEntry<Biome> fix$locateBiome(
            BiomeSource source,
            int quartX,
            int quartY,
            int quartZ,
            MultiNoiseUtil.MultiNoiseSampler sampler
    ) {
        return BiomeDistributionFix.getDistributedBiomeAtQuart(
                quartX, quartY, quartZ, source::getBiome, sampler
        );
    }

    @Redirect(
            method = "locateBiome(Lnet/minecraft/util/math/BlockPos;IIILjava/util/function/Predicate;" +
                    "Lnet/minecraft/world/biome/source/util/MultiNoiseUtil$MultiNoiseSampler;" +
                    "Lnet/minecraft/world/WorldView;)Lcom/mojang/datafixers/util/Pair;",
            at = @At(value = "INVOKE", target = GET_BIOME),
            require = 0
    )
    private RegistryEntry<Biome> fix$locateBiomeAtPosition(
            BiomeSource source,
            int quartX,
            int quartY,
            int quartZ,
            MultiNoiseUtil.MultiNoiseSampler sampler
    ) {
        return BiomeDistributionFix.getDistributedBiomeAtQuart(
                quartX, quartY, quartZ, source::getBiome, sampler
        );
    }
}
