package com.mortylovelly.fix.mixin;

import com.mortylovelly.fix.biome.MountainTerrainScaler;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.Blender;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.noise.NoiseConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Runs the mountain transformation inside NoiseChunkGenerator's private
 * base-noise method. At this point the generation pipeline still owns the
 * chunk sections, and the later surface step will see the refreshed heightmap.
 */
@Mixin(NoiseChunkGenerator.class)
public abstract class NoiseChunkGeneratorMixin {
    @Inject(
            method = "populateNoise(Lnet/minecraft/world/gen/chunk/Blender;" +
                    "Lnet/minecraft/world/gen/StructureAccessor;" +
                    "Lnet/minecraft/world/gen/noise/NoiseConfig;" +
                    "Lnet/minecraft/world/chunk/Chunk;II)" +
                    "Lnet/minecraft/world/chunk/Chunk;",
            at = @At("RETURN")
    )
    private void fix$scaleMountainTerrain(
            Blender blender,
            StructureAccessor structureAccessor,
            NoiseConfig noiseConfig,
            Chunk chunk,
            int minimumCellY,
            int cellHeight,
            CallbackInfoReturnable<Chunk> cir
    ) {
        Chunk generatedChunk = cir.getReturnValue();
        if (generatedChunk != null) {
            MountainTerrainScaler.scale(generatedChunk, noiseConfig);
        }
    }
}
