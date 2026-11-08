package com.mortylovelly.fix.biome;

import com.mortylovelly.fix.FixMod;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.noise.NoiseConfig;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Builds continuous mountain ranges from a deterministic 2D height field.
 *
 * The previous implementation stretched whatever small hill happened to be
 * under a mountain biome. If that source column was only Y=90, even a "large"
 * stage remained a low hill. This implementation instead generates a smooth
 * ridged height field whose local peaks approach the region's stage cap.
 *
 * It runs after base noise and before buildSurface/carvers. Thus vanilla biome
 * surface rules can still place the biome's top material on the generated
 * mountain surface, and later carvers can cut caves into the new mountain mass.
 */
public final class MountainTerrainScaler {
    private static final int SEA_LEVEL = 63;
    private static final int MIN_GROUND_ABOVE_SEA = 4;
    private static final int WORLD_TOP_SAFETY_MARGIN = 8;
    private static final int MIN_MOUNTAIN_HEIGHT_GAIN = 12;
    private static final int MAX_DETAIL_LOGS = 12;

    private static final AtomicBoolean HEIGHT_LIMIT_WARNING_LOGGED = new AtomicBoolean();
    private static final AtomicLong CHUNKS_PROCESSED = new AtomicLong();

    private MountainTerrainScaler() {
    }

    public static void scale(Chunk chunk, NoiseConfig noiseConfig) {
        if (chunk.getBottomY() != -64 || chunk.getTopY() <= 320) {
            if (HEIGHT_LIMIT_WARNING_LOGGED.compareAndSet(false, true)) {
                FixMod.LOGGER.warn(
                        "[Fix][MountainHeight] Inactive: expected an Overworld-like Y=-64 world " +
                                "with a top boundary above 320; actual bottomY={}, topY={}.",
                        chunk.getBottomY(),
                        chunk.getTopY()
                );
            }
            return;
        }

        final int maxPlaceableY = getTerrainTopLimit(chunk.getTopY());
        if (maxPlaceableY <= SEA_LEVEL + 32) {
            return;
        }

        final long startedAt = System.nanoTime();
        final int chunkStartX = chunk.getPos().getStartX();
        final int chunkStartZ = chunk.getPos().getStartZ();
        final BlockPos.Mutable readPos = new BlockPos.Mutable();
        final BlockPos.Mutable writePos = new BlockPos.Mutable();

        // Cache stage and terrain seed once per macro-region touched by this chunk.
        Map<Long, RegionTerrain> regionCache = new HashMap<>(4);

        int mountainBiomeColumns = 0;
        int selectedRegionColumns = 0;
        int raisedColumns = 0;
        int cappedColumns = 0;
        int smallColumns = 0;
        int mediumColumns = 0;
        int largeColumns = 0;
        int highestSourceY = chunk.getBottomY();
        int highestTargetY = chunk.getBottomY();

        for (int localX = 0; localX < 16; ++localX) {
            for (int localZ = 0; localZ < 16; ++localZ) {
                int worldX = chunkStartX + localX;
                int worldZ = chunkStartZ + localZ;

                int originalSurfaceY = chunk.sampleHeightmap(
                        Heightmap.Type.WORLD_SURFACE_WG,
                        localX,
                        localZ
                ) - 1;

                if (originalSurfaceY <= SEA_LEVEL - 8) {
                    continue;
                }

                int quartX = Math.floorDiv(worldX, 4);
                int quartZ = Math.floorDiv(worldZ, 4);
                int quartY = Math.floorDiv(originalSurfaceY, 4);

                var biome = chunk.getBiomeForNoiseGen(localX >> 2, quartY, localZ >> 2);
                if (!BiomeDistributionFix.isMountainBiome(biome)) {
                    continue;
                }
                mountainBiomeColumns++;

                BiomeDistributionFix.MountainRegionCell region =
                        BiomeDistributionFix.nearestMountainRegionForQuart(quartX, quartZ);

                if (!BiomeDistributionFix.isMountainRegionSelectedCell(
                        region.cellX(),
                        region.cellZ()
                )) {
                    continue;
                }
                selectedRegionColumns++;

                long regionKey = packRegion(region.cellX(), region.cellZ());
                RegionTerrain terrain = regionCache.get(regionKey);
                if (terrain == null) {
                    MountainStage stage = MountainStage.forRegion(
                            noiseConfig,
                            region.cellX(),
                            region.cellZ()
                    );
                    long seed = MountainStage.terrainSeedForRegion(
                            noiseConfig,
                            region.cellX(),
                            region.cellZ()
                    );
                    terrain = new RegionTerrain(stage, seed);
                    regionCache.put(regionKey, terrain);
                }

                if (terrain.stage == MountainStage.SMALL) {
                    smallColumns++;
                } else if (terrain.stage == MountainStage.MEDIUM) {
                    mediumColumns++;
                } else {
                    largeColumns++;
                }

                int groundY = findGroundY(chunk, readPos, worldX, worldZ, originalSurfaceY);
                if (groundY < SEA_LEVEL + MIN_GROUND_ABOVE_SEA) {
                    continue;
                }

                highestSourceY = Math.max(highestSourceY, groundY);

                int targetY = calculateTargetSurfaceY(
                        terrain.seed,
                        terrain.stage.getTargetPeakY(),
                        worldX,
                        worldZ,
                        groundY,
                        maxPlaceableY
                );

                if (maxPlaceableY < terrain.stage.getTargetPeakY()) {
                    cappedColumns++;
                }
                targetY = Math.min(targetY, maxPlaceableY);

                if (targetY - groundY < MIN_MOUNTAIN_HEIGHT_GAIN) {
                    continue;
                }

                // A stone core creates real topography instead of copying a
                // low source hill's short vertical profile. buildSurface runs
                // afterwards and applies the selected mountain biome's top layer.
                BlockState stone = Blocks.STONE.getDefaultState();
                for (int targetBlockY = groundY + 1; targetBlockY <= targetY; ++targetBlockY) {
                    writePos.set(worldX, targetBlockY, worldZ);
                    chunk.setBlockState(writePos, stone, false);
                }

                raisedColumns++;
                highestTargetY = Math.max(highestTargetY, targetY);
            }
        }

        if (raisedColumns > 0) {
            // Recalculate WORLD_SURFACE_WG and OCEAN_FLOOR_WG before the later
            // surface stage consumes the modified proto chunk.
            chunk.refreshSurfaceY();
        }

        long processedChunk = CHUNKS_PROCESSED.incrementAndGet();
        if (processedChunk <= MAX_DETAIL_LOGS || processedChunk % 256L == 0L) {
            long elapsedMicros = (System.nanoTime() - startedAt) / 1_000L;
            int actualHighestY = raisedColumns > 0
                    ? findHighestGeneratedSurface(chunk)
                    : highestSourceY;

            FixMod.LOGGER.info(
                    "[Fix][MountainHeight][Chunk] chunk={}, mountain_biome_columns={}, " +
                            "selected_region_columns={}, raised_columns={}, stage_columns=" +
                            "small:{},medium:{},large:{}, capped_columns={}, highest_sourceY={}, " +
                            "highest_targetY={}, highest_chunk_surfaceY={}, worldTopY={}, " +
                            "safeTerrainTopY={}, elapsed_us={}.",
                    chunk.getPos(),
                    mountainBiomeColumns,
                    selectedRegionColumns,
                    raisedColumns,
                    smallColumns,
                    mediumColumns,
                    largeColumns,
                    cappedColumns,
                    highestSourceY,
                    highestTargetY,
                    actualHighestY,
                    chunk.getTopY(),
                    maxPlaceableY,
                    elapsedMicros
            );
        }
    }

    /**
     * Same target-height function is used by /locate so the returned Y value
     * matches the terrain that the generator will attempt to create.
     */
    /**
     * Reserve space above a peak so a player can stand there below the
     * dimension ceiling. The mountain locator uses the same limit.
     */
    public static int getTerrainTopLimit(int worldTopExclusive) {
        return worldTopExclusive - WORLD_TOP_SAFETY_MARGIN;
    }

    public static int calculateTargetSurfaceY(
            long regionSeed,
            int stagePeakY,
            int worldX,
            int worldZ,
            int baseSurfaceY,
            int maxPlaceableY
    ) {
        int cappedPeak = Math.min(stagePeakY, maxPlaceableY);
        if (cappedPeak <= SEA_LEVEL) {
            return Math.max(baseSurfaceY, cappedPeak);
        }

        // Large-scale continental mask forms broad mountain belts.
        double continental = (valueNoise(worldX, worldZ, regionSeed ^ 0x4F1BBCDCBFA54001L, 7200) + 1.0D) * 0.5D;
        double rangeMask = smoothstep(0.10D, 0.88D, continental);

        // Domain warping prevents the ridges from looking like a regular grid.
        double warpX = worldX
                + valueNoise(worldX, worldZ, regionSeed ^ 0x9E3779B97F4A7C15L, 3400) * 1450.0D;
        double warpZ = worldZ
                + valueNoise(worldX, worldZ, regionSeed ^ 0xC2B2AE3D27D4EB4FL, 3400) * 1450.0D;

        double primaryRidge = 1.0D - Math.abs(
                valueNoise(warpX, warpZ, regionSeed ^ 0x165667B19E3779F9L, 1700)
        );
        double secondaryRidge = 1.0D - Math.abs(
                valueNoise(warpX, warpZ, regionSeed ^ 0x85EBCA77C2B2AE63L, 620)
        );
        double detail = (valueNoise(warpX, warpZ, regionSeed ^ 0x27D4EB2F165667C5L, 230) + 1.0D) * 0.5D;

        double ridgeShape = 0.52D * primaryRidge
                + 0.33D * secondaryRidge
                + 0.15D * detail;

        // Low areas remain foothills/valleys; high areas converge to the
        // stage cap at coherent ridge intersections.
        double heightFactor = 0.08D
                + 0.92D * rangeMask * (0.34D + 0.66D * ridgeShape);

        int generatedSurfaceY = SEA_LEVEL + (int) Math.round(
                (cappedPeak - SEA_LEVEL) * heightFactor
        );

        generatedSurfaceY = Math.max(generatedSurfaceY, baseSurfaceY);
        return Math.min(generatedSurfaceY, maxPlaceableY);
    }

    private static int findGroundY(
            Chunk chunk,
            BlockPos.Mutable pos,
            int worldX,
            int worldZ,
            int surfaceY
    ) {
        int y = Math.min(surfaceY, chunk.getTopY() - 1);
        int bottomLimit = Math.max(chunk.getBottomY(), SEA_LEVEL - 16);

        while (y > bottomLimit) {
            pos.set(worldX, y, worldZ);
            BlockState state = chunk.getBlockState(pos);
            if (!state.isAir() && state.getFluidState().isEmpty()) {
                return y;
            }
            y--;
        }
        return y;
    }

    private static int findHighestGeneratedSurface(Chunk chunk) {
        int highest = chunk.getBottomY();
        for (int x = 0; x < 16; ++x) {
            for (int z = 0; z < 16; ++z) {
                highest = Math.max(
                        highest,
                        chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE_WG, x, z) - 1
                );
            }
        }
        return highest;
    }

    private static double valueNoise(
            double worldX,
            double worldZ,
            long seed,
            int cellSize
    ) {
        double gridX = worldX / cellSize;
        double gridZ = worldZ / cellSize;

        int x0 = (int) Math.floor(gridX);
        int z0 = (int) Math.floor(gridZ);
        int x1 = x0 + 1;
        int z1 = z0 + 1;

        double tx = fade(gridX - x0);
        double tz = fade(gridZ - z0);

        double a = latticeValue(x0, z0, seed);
        double b = latticeValue(x1, z0, seed);
        double c = latticeValue(x0, z1, seed);
        double d = latticeValue(x1, z1, seed);

        double top = lerp(a, b, tx);
        double bottom = lerp(c, d, tx);
        return lerp(top, bottom, tz);
    }

    private static double latticeValue(int x, int z, long seed) {
        long value = seed
                ^ ((long) x * 0x9E3779B97F4A7C15L)
                ^ ((long) z * 0xC2B2AE3D27D4EB4FL);
        value = mix(value);
        return ((value >>> 11) * 0x1.0p-52) - 1.0D;
    }

    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    private static double fade(double value) {
        return value * value * value * (value * (value * 6.0D - 15.0D) + 10.0D);
    }

    private static double smoothstep(double edge0, double edge1, double value) {
        double t = (value - edge0) / (edge1 - edge0);
        t = Math.max(0.0D, Math.min(1.0D, t));
        return t * t * (3.0D - 2.0D * t);
    }

    private static double lerp(double first, double second, double delta) {
        return first + (second - first) * delta;
    }

    private static long packRegion(int cellX, int cellZ) {
        return ((long) cellX << 32) ^ (cellZ & 0xFFFFFFFFL);
    }

    private record RegionTerrain(MountainStage stage, long seed) {
    }
}
