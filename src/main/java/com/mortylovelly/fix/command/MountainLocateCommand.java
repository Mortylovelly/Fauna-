package com.mortylovelly.fix.command;

import com.mortylovelly.fix.biome.BiomeDistributionFix;
import com.mortylovelly.fix.biome.MountainStage;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.CommandSource;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.noise.NoiseConfig;

import java.util.List;

public final class MountainLocateCommand {
    private static final int MAX_REGION_SEARCH_RADIUS = 24;

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
        NoiseConfig noiseConfig = chunkManager.getNoiseConfig();
        int originBlockX = MathHelper.floor(source.getPosition().x);
        int originBlockZ = MathHelper.floor(source.getPosition().z);

        BiomeDistributionFix.MountainRegionCell origin =
                BiomeDistributionFix.nearestMountainRegionForQuart(
                        Math.floorDiv(originBlockX, 4),
                        Math.floorDiv(originBlockZ, 4)
                );

        for (int radius = 0; radius <= MAX_REGION_SEARCH_RADIUS; ++radius) {
            int bestCellX = 0;
            int bestCellZ = 0;
            long bestDistance = Long.MAX_VALUE;
            boolean found = false;

            for (int dx = -radius; dx <= radius; ++dx) {
                for (int dz = -radius; dz <= radius; ++dz) {
                    if (radius > 0 && Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }

                    int cellX = origin.cellX() + dx;
                    int cellZ = origin.cellZ() + dz;

                    if (MountainStage.forRegion(noiseConfig, cellX, cellZ) != requestedStage) {
                        continue;
                    }
                    if (!BiomeDistributionFix.isSelectedMountainRegion(
                            cellX,
                            cellZ,
                            biomeSource,
                            noiseConfig.getMultiNoiseSampler()
                    )) {
                        continue;
                    }

                    BiomeDistributionFix.MountainRegionCell cell =
                            BiomeDistributionFix.mountainRegionCellAt(cellX, cellZ);
                    long distance = squaredDistance(
                            originBlockX,
                            originBlockZ,
                            cell.centerBlockX(),
                            cell.centerBlockZ()
                    );

                    if (distance < bestDistance) {
                        bestDistance = distance;
                        bestCellX = cellX;
                        bestCellZ = cellZ;
                        found = true;
                    }
                }
            }

            if (found) {
                BiomeDistributionFix.MountainRegionCell result =
                        BiomeDistributionFix.mountainRegionCellAt(bestCellX, bestCellZ);
                int distanceBlocks = (int) Math.round(Math.sqrt(bestDistance));
                source.sendFeedback(
                        () -> Text.literal(
                                "Nearest " + requestedStage.getId()
                                        + " mountain region: X=" + result.centerBlockX()
                                        + ", Z=" + result.centerBlockZ()
                                        + ", stage peak target Y=" + requestedStage.getTargetPeakY()
                                        + ", distance=" + distanceBlocks + " blocks."
                        ),
                        false
                );
                return 1;
            }
        }

        source.sendError(Text.literal(
                "No " + requestedStage.getId()
                        + " mountain region found within "
                        + (MAX_REGION_SEARCH_RADIUS * 20_000)
                        + " blocks."
        ));
        return 0;
    }

    private static long squaredDistance(int x1, int z1, int x2, int z2) {
        long dx = (long) x1 - x2;
        long dz = (long) z1 - z2;
        return dx * dx + dz * dz;
    }
}
