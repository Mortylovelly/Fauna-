package com.mortylovelly.fix.command;

import com.mortylovelly.fix.biome.BiomeDistributionFix;
import com.mortylovelly.fix.biome.MountainStage;
import com.mortylovelly.fix.biome.MountainTerrainScaler;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.CommandSource;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.Heightmap;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.biome.source.BiomeSupplier;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.noise.NoiseConfig;
import net.minecraft.registry.entry.RegistryEntry;

import java.util.List;

/**
 * Finds a sampled surface position whose final distributed biome is actually
 * mountainous, and whose predicted terrain height matches the requested stage.
 * It no longer returns a jittered region centre based on biome metadata alone.
 */
public final class MountainLocateCommand {
    private static final int MAX_REGION_SEARCH_RADIUS = 16;
    private static final int SAMPLE_STEP = 2500;
    private static final int SAMPLE_RANGE = 7500;

    private MountainLocateCommand() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(
                        CommandManager.literal("locate")
                                .then(CommandManager.literal("mountain")
                                        .requires(source -> source.hasPermissionLevel(2))
                                        .then(CommandManager.argument("stage", StringArgumentType.word())
                                                .suggests((context, builder) -> CommandSource.suggestMatching(
                                                        List.of("small", "medium", "large"),
                                                        builder
                                                ))
                                                .executes(context -> locate(
                                                        context.getSource(),
                                                        StringArgumentType.getString(context, "stage")
                                                ))
                                        )
                                )
                )
        );
    }

    private static int locate(ServerCommandSource source, String requestedStageId) {
        MountainStage requestedStage = MountainStage.byId(requestedStageId);
        if (requestedStage == null) {
            source.sendError(Text.literal("Unknown mountain stage. Use: small, medium, large."));
            return 0;
        }

        ServerChunkManager chunkManager = source.getWorld().getChunkManager();
        ChunkGenerator generator = chunkManager.getChunkGenerator();
        if (!(generator instanceof NoiseChunkGenerator)) {
            source.sendError(Text.literal("Mountain locate supports NoiseChunkGenerator worlds only."));
            return 0;
        }

        BiomeSource biomeSource = generator.getBiomeSource();
        BiomeSupplier biomeSupplier = biomeSource::getBiome;
        NoiseConfig noiseConfig = chunkManager.getNoiseConfig();
        int originX = MathHelper.floor(source.getPosition().x);
        int originZ = MathHelper.floor(source.getPosition().z);
        int maxPlaceableY = MountainTerrainScaler.getTerrainTopLimit(source.getWorld().getTopY());
        int originCellX = BiomeDistributionFix.nearestMountainRegionForQuart(
                Math.floorDiv(originX, 4),
                Math.floorDiv(originZ, 4)
        ).cellX();
        int originCellZ = BiomeDistributionFix.nearestMountainRegionForQuart(
                Math.floorDiv(originX, 4),
                Math.floorDiv(originZ, 4)
        ).cellZ();

        for (int radius = 0; radius <= MAX_REGION_SEARCH_RADIUS; ++radius) {
            MountainPoint best = null;

            for (int dx = -radius; dx <= radius; ++dx) {
                for (int dz = -radius; dz <= radius; ++dz) {
                    if (radius > 0 && Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }

                    int cellX = originCellX + dx;
                    int cellZ = originCellZ + dz;

                    if (MountainStage.forRegion(noiseConfig, cellX, cellZ) != requestedStage) {
                        continue;
                    }
                    // A random selected cell is not enough: verify that the
                    // final distributed biome at this region's anchor is truly
                    // mountainous before searching its terrain.
                    if (!BiomeDistributionFix.isSelectedMountainRegion(
                            cellX, cellZ, biomeSource, noiseConfig.getMultiNoiseSampler()
                    )) {
                        continue;
                    }

                    long terrainSeed = MountainStage.terrainSeedForRegion(
                            noiseConfig,
                            cellX,
                            cellZ
                    );

                    MountainPoint candidate = findBestPointInRegion(
                            source,
                            generator,
                            biomeSupplier,
                            noiseConfig,
                            requestedStage,
                            cellX,
                            cellZ,
                            terrainSeed,
                            maxPlaceableY,
                            originX,
                            originZ
                    );

                    if (candidate != null
                            && (best == null
                            || candidate.predictedTopY() > best.predictedTopY()
                            || (candidate.predictedTopY() == best.predictedTopY()
                            && candidate.distanceSquared() < best.distanceSquared()))) {
                        best = candidate;
                    }
                }
            }

            if (best != null) {
                MountainPoint result = best;
                int distanceBlocks = (int) Math.round(Math.sqrt(result.distanceSquared()));
                String feedback = "Verified " + requestedStage.getId() + " mountain: "
                        + "X=" + result.x()
                        + ", Y=" + (result.predictedTopY() + 1)
                        + ", Z=" + result.z()
                        + "; biome=" + result.biomeId()
                        + "; base_surfaceY=" + result.baseSurfaceY()
                        + "; predicted_peakY=" + result.predictedTopY()
                        + "; stage_capY=" + requestedStage.getTargetPeakY()
                        + "; world_topY=" + source.getWorld().getTopY()
                        + "; safe_terrain_topY=" + maxPlaceableY
                        + "; distance=" + distanceBlocks + " blocks. "
                        + "Teleport: /tp @s " + result.x() + " "
                        + (result.predictedTopY() + 1) + " " + result.z();

                source.sendFeedback(() -> Text.literal(feedback), false);
                return 1;
            }
        }

        source.sendError(Text.literal(
                "No verified " + requestedStage.getId()
                        + " mountain surface found within "
                        + (MAX_REGION_SEARCH_RADIUS * 20_000)
                        + " blocks. Try another stage or a larger search radius."
        ));
        return 0;
    }

    private static MountainPoint findBestPointInRegion(
            ServerCommandSource source,
            ChunkGenerator generator,
            BiomeSupplier biomeSupplier,
            NoiseConfig noiseConfig,
            MountainStage stage,
            int cellX,
            int cellZ,
            long terrainSeed,
            int maxPlaceableY,
            int originX,
            int originZ
    ) {
        BiomeDistributionFix.MountainRegionCell region =
                BiomeDistributionFix.mountainRegionCellAt(cellX, cellZ);

        MountainPoint best = null;
        int centreX = region.centerBlockX();
        int centreZ = region.centerBlockZ();

        for (int offsetX = -SAMPLE_RANGE; offsetX <= SAMPLE_RANGE; offsetX += SAMPLE_STEP) {
            for (int offsetZ = -SAMPLE_RANGE; offsetZ <= SAMPLE_RANGE; offsetZ += SAMPLE_STEP) {
                int x = centreX + offsetX;
                int z = centreZ + offsetZ;

                BiomeDistributionFix.MountainRegionCell actualRegion =
                        BiomeDistributionFix.nearestMountainRegionForQuart(
                                Math.floorDiv(x, 4),
                                Math.floorDiv(z, 4)
                        );
                if (actualRegion.cellX() != cellX || actualRegion.cellZ() != cellZ) {
                    continue;
                }

                int baseSurfaceY = generator.getHeight(
                        x,
                        z,
                        Heightmap.Type.WORLD_SURFACE_WG,
                        source.getWorld(),
                        noiseConfig
                ) - 1;

                if (baseSurfaceY <= 63) {
                    continue;
                }

                RegistryEntry<Biome> finalBiome = BiomeDistributionFix.getDistributedBiomeAtQuart(
                        Math.floorDiv(x, 4),
                        Math.floorDiv(baseSurfaceY, 4),
                        Math.floorDiv(z, 4),
                        biomeSupplier,
                        noiseConfig.getMultiNoiseSampler()
                );
                if (!BiomeDistributionFix.isMountainBiome(finalBiome)) {
                    continue;
                }

                int predictedTopY = MountainTerrainScaler.calculateTargetSurfaceY(
                        terrainSeed,
                        stage.getTargetPeakY(),
                        x,
                        z,
                        baseSurfaceY,
                        maxPlaceableY
                );
                if (predictedTopY - baseSurfaceY < 12) {
                    continue;
                }

                long dx = (long) x - originX;
                long dz = (long) z - originZ;
                long distanceSquared = dx * dx + dz * dz;

                String biomeId = finalBiome.getKey()
                        .map(key -> key.getValue().toString())
                        .orElse("<unknown>");

                MountainPoint candidate = new MountainPoint(
                        x,
                        z,
                        baseSurfaceY,
                        predictedTopY,
                        distanceSquared,
                        biomeId
                );

                if (best == null
                        || candidate.predictedTopY() > best.predictedTopY()
                        || (candidate.predictedTopY() == best.predictedTopY()
                        && candidate.distanceSquared() < best.distanceSquared())) {
                    best = candidate;
                }
            }
        }

        return best;
    }

    private record MountainPoint(
            int x,
            int z,
            int baseSurfaceY,
            int predictedTopY,
            long distanceSquared,
            String biomeId
    ) {
    }
}
