package com.mortylovelly.fix.biome;

import net.minecraft.util.Identifier;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.gen.noise.NoiseConfig;

/**
 * World-seed-dependent virtual stage assigned to an existing mountain region.
 * This is deliberately not a new biome ID or a global biome tag.
 */
public enum MountainStage {
    SMALL("small", 500),
    MEDIUM("medium", 1200),
    LARGE("large", 2000);

    private static final Identifier RANDOM_SALT =
            new Identifier("fix", "mountain_stage");

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
        Random random = noiseConfig.getOrCreateRandomDeriver(RANDOM_SALT)
                .split(regionCellX, 0, regionCellZ);
        return values()[random.nextInt(values().length)];
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
