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
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.HashMap;
import java.util.Map;

@Mixin(value = ChunkSection.class, priority = 900)
public abstract class ChunkSectionBiomeMixin {
    private static final int SLICE_SIZE = 4;

    @Shadow
    private ReadableContainer<RegistryEntry<Biome>> biomeContainer;

    /**
     * Reimplements the small Noisium biome-population loop so the large-region
     * biome normalization can coexist with Noisium's own ChunkSection overwrite.
     *
     * This deliberately keeps the same 4x4x4 palette population structure.
     */
    @Overwrite
    public void populateBiomes(
            BiomeSupplier biomeSupplier,
            MultiNoiseUtil.MultiNoiseSampler sampler,
            int x,
            int y,
            int z
    ) {
        PalettedContainer<RegistryEntry<Biome>> palettedContainer = this.biomeContainer.slice();
        Map<Long, RegistryEntry<Biome>> localCache = new HashMap<>(8);

        for (int posY = 0; posY < SLICE_SIZE; ++posY) {
            for (int posZ = 0; posZ < SLICE_SIZE; ++posZ) {
                for (int posX = 0; posX < SLICE_SIZE; ++posX) {
                    palettedContainer.swapUnsafe(
                            posX,
                            posY,
                            posZ,
                            BiomeDistributionFix.sample(
                                    biomeSupplier,
                                    x + posX,
                                    y + posY,
                                    z + posZ,
                                    sampler,
                                    localCache
                            )
                    );
                }
            }
        }

        this.biomeContainer = palettedContainer;
    }
}
