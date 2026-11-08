package com.mortylovelly.fix.biome;

import com.mortylovelly.fix.FixMod;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.noise.NoiseConfig;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Stretches the existing vertical terrain profile of selected mountain
 * columns after base noise generation. It never writes outside the chunk's
 * actual height limit.
 */
public final class MountainTerrainScaler {
    private static final int SEA_LEVEL = 63;
    private static final int VANILLA_MOUNTAIN_REFERENCE_Y = 300;
    private static final AtomicBoolean HEIGHT_LIMIT_WARNING_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean FIRST_SCALE_LOGGED = new AtomicBoolean();

    private MountainTerrainScaler() {
    }

    public static void scale(Chunk chunk, NoiseConfig noiseConfig) {
        // Vanilla Y=-64..319 cannot contain the requested 500/1200/2000 stages.
        if (chunk.getBottomY() != -64 || chunk.getTopY() <= 320) {
            if (HEIGHT_LIMIT_WARNING_LOGGED.compareAndSet(false, true)) {
                FixMod.LOGGER.warn(
                        "[Fix][MountainHeight] Terrain scaling is inactive: expected an Overworld-like " +
                                "Y=-64 world with a top boundary above 320, found bottomY={}, topY={}.",
                        chunk.getBottomY(),
                        chunk.getTopY()
                );
            }
            return;
        }

        int maxPlaceableY = chunk.getTopY() - 1;
        if (maxPlaceableY <= SEA_LEVEL + 32) {
            return;
        }

        int chunkStartX = chunk.getPos().getStartX();
        int chunkStartZ = chunk.getPos().getStartZ();
        BlockPos.Mutable sourcePos = new BlockPos.Mutable();
        BlockPos.Mutable targetPos = new BlockPos.Mutable();
        int scaledColumns = 0;
        int highestCreatedY = SEA_LEVEL;

        for (int localX = 0; localX < 16; ++localX) {
            for (int localZ = 0; localZ < 16; ++localZ) {
                int originalTopY = chunk.sampleHeightmap(
                        Heightmap.Type.WORLD_SURFACE_WG,
                        localX,
                        localZ
                ) - 1;

                if (originalTopY <= SEA_LEVEL + 24) {
                    continue;
                }

                int worldX = chunkStartX + localX;
                int worldZ = chunkStartZ + localZ;
                int quartY = Math.floorDiv(originalTopY, 4);

                if (!BiomeDistributionFix.isMountainBiome(
                        chunk.getBiomeForNoiseGen(localX >> 2, quartY, localZ >> 2)
                )) {
                    continue;
                }

                BiomeDistributionFix.MountainRegionCell region =
                        BiomeDistributionFix.nearestMountainRegionForQuart(
                                Math.floorDiv(worldX, 4),
                                Math.floorDiv(worldZ, 4)
                        );
                if (!BiomeDistributionFix.shouldScaleMountainRegion(
                        region.cellX(),
                        region.cellZ()
                )) {
                    continue;
                }

                MountainStage stage = MountainStage.forRegion(
                        noiseConfig,
                        region.cellX(),
                        region.cellZ()
                );

                int targetPeakY = Math.min(stage.getTargetPeakY(), maxPlaceableY);
                if (targetPeakY <= VANILLA_MOUNTAIN_REFERENCE_Y) {
                    continue;
                }

                double scale = (double) (targetPeakY - SEA_LEVEL)
                        / (double) (VANILLA_MOUNTAIN_REFERENCE_Y - SEA_LEVEL);
                if (scale <= 1.0D) {
                    continue;
                }

                int stretchedTopY = SEA_LEVEL + (int) Math.round(
                        (originalTopY - SEA_LEVEL) * scale
                );
                stretchedTopY = Math.min(stretchedTopY, targetPeakY);
                stretchedTopY = Math.min(stretchedTopY, maxPlaceableY);

                if (stretchedTopY <= originalTopY) {
                    continue;
                }

                int sourceBottomY = SEA_LEVEL + 1;
                int sourceHeight = originalTopY - sourceBottomY + 1;
                if (sourceHeight <= 0) {
                    continue;
                }

                BlockState[] sourceStates = new BlockState[sourceHeight];
                for (int index = 0; index < sourceHeight; ++index) {
                    sourcePos.set(worldX, sourceBottomY + index, worldZ);
                    sourceStates[index] = chunk.getBlockState(sourcePos);
                }

                for (int targetY = sourceBottomY; targetY <= stretchedTopY; ++targetY) {
                    double sourceOffset = ((double) (targetY - SEA_LEVEL) / scale) - 1.0D;
                    int sourceIndex = (int) Math.floor(sourceOffset + 0.5D);
                    sourceIndex = Math.max(0, Math.min(sourceHeight - 1, sourceIndex));

                    BlockState state = sourceStates[sourceIndex];
                    if (state == null) {
                        continue;
                    }

                    targetPos.set(worldX, targetY, worldZ);
                    chunk.setBlockState(targetPos, state, false);
                }

                scaledColumns++;
                highestCreatedY = Math.max(highestCreatedY, stretchedTopY);
            }
        }

        if (scaledColumns > 0) {
            chunk.refreshSurfaceY();
            if (FIRST_SCALE_LOGGED.compareAndSet(false, true)) {
                FixMod.LOGGER.info(
                        "[Fix][MountainHeight] Active: first chunk {} scaled {} mountain columns; " +
                                "highest generated block Y={}, world top boundary={}.",
                        chunk.getPos(),
                        scaledColumns,
                        highestCreatedY,
                        chunk.getTopY()
                );
            }
        }
    }
}
