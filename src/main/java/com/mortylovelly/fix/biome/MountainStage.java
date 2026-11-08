package com.mortylovelly.fix.biome;

import net.minecraft.util.Identifier;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.gen.noise.NoiseConfig;

/**
 * A deterministic virtual mountain stage assigned to a selected 2x2 cluster
 * of existing mountain macro-regions. No biome IDs or registry tags are added.
 *
 * Sharing a stage and terrain seed across the 2x2 cluster prevents sharp
 * height/seed seams between adjacent cells of one continuous range.
 */
public enum MountainStage {
    SMALL("small", 500),
    MEDIUM("medium", 1200),
    LARGE("large", 2000);

    private static final int STAGE_CLUSTER_SIZE = 2;
    private static final Identifier STAGE_RANDOM_SALT =
            new Identifier("fix", "mountain_stage");
    private static final Identifier TERRAIN_RANDOM_SALT =
            new Identifier("fix", "mountain_terrain");

    private final String id;
    private final int targetPeakY;

    MountainStage(String id, int targetPeakY) {
        this.id = id;
        this.targetPeakY = targetPeakY;
    }

    public String getId() {
        return id;
    }

    public int getTargetPeakY() {
        return targetPeakY;
    }

    public static MountainStage forRegion(
            NoiseConfig noiseConfig,
            int regionCellX,
            int regionCellZ
    ) {
        int clusterX = Math.floorDiv(regionCellX, STAGE_CLUSTER_SIZE);
        int clusterZ = Math.floorDiv(regionCellZ, STAGE_CLUSTER_SIZE);
        Random random = noiseConfig.getOrCreateRandomDeriver(STAGE_RANDOM_SALT)
                .split(clusterX, 0, clusterZ);
        return values()[random.nextInt(values().length)];
    }

    /**
     * Stable terrain seed shared by all four region cells in a 2x2 cluster.
     * A separate salt keeps terrain shape independent from stage selection.
     */
    public static long terrainSeedForRegion(
            NoiseConfig noiseConfig,
            int regionCellX,
            int regionCellZ
    ) {
        int clusterX = Math.floorDiv(regionCellX, STAGE_CLUSTER_SIZE);
        int clusterZ = Math.floorDiv(regionCellZ, STAGE_CLUSTER_SIZE);
        Random random = noiseConfig.getOrCreateRandomDeriver(TERRAIN_RANDOM_SALT)
                .split(clusterX, 0, clusterZ);
        return ((long) random.nextInt() << 32) ^ (random.nextInt() & 0xFFFFFFFFL);
    }

    public static MountainStage byId(String id) {
        for (MountainStage stage : values()) {
            if (stage.id.equalsIgnoreCase(id)) {
                return stage;
            }
        }
        return null;
    }
}
