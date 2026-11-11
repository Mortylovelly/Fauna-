package com.mortylovelly.fix.mixin;

import com.mortylovelly.fix.biome.MountainTerrainScaler;
import net.minecraft.world.ChunkRegion;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.noise.NoiseConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies mountain terrain after base noise generation has produced the
 * chunk's surface heightmap, but before the biome surface blocks are placed.
 */
@Mixin(NoiseChunkGenerator.class)
public abstract class NoiseChunkGeneratorMixin {
    @Inject(
            method = "buildSurface(Lnet/minecraft/world/ChunkRegion;" +
                    "Lnet/minecraft/world/gen/StructureAccessor;" +
                    "Lnet/minecraft/world/gen/noise/NoiseConfig;" +
                    "Lnet/minecraft/world/chunk/Chunk;)V",
            at = @At("HEAD")
    )
    private void fix$scaleMountainTerrain(
            ChunkRegion region,
            StructureAccessor structures,
            NoiseConfig noiseConfig,
            Chunk chunk,
            CallbackInfo ci
    ) {
        MountainTerrainScaler.scale(chunk, noiseConfig);
    }
}
