package com.mortylovelly.fix.biome;

import com.mortylovelly.fix.FixMod;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeSupplier;
import net.minecraft.world.biome.source.util.MultiNoiseUtil;

import java.util.HashMap;
import java.util.Map;

/**
 * Enlarges horizontal biome regions while preserving the original biome source.
 *
 * The important implementation detail is that this class is used by the
 * ChunkSection.populateBiomes overwrite below. A whole 4x4x4 biome palette is
 * generated in the same style as Noisium, but points that fall into the same
 * large region reuse one source-biome result instead of recalculating it.
 *
 * Normal biome-region width is about 3,600-10,800 blocks.
 * Mountain-region width is about 5,200-14,800 blocks.
 */
public final class BiomeDistributionFix {
    private static final boolean ENABLED = true;

    /**
     * One biome-source coordinate equals four horizontal blocks.
     *
     * A Voronoi-style nearest-center selection with deterministic jitter gives
     * normal regions roughly 3,600-10,800 blocks across.
     */
    private static final int NORMAL_REGION_SIZE = 1800;
    private static final int NORMAL_JITTER = 450;

    /**
     * Mountain biome zones use a still larger lattice.
     */
    private static final int MOUNTAIN_REGION_SIZE = 2500;
    private static final int MOUNTAIN_JITTER = 600;

    /**
     * Candidate mountain zones are deliberately rare.
     */
    private static final double MOUNTAIN_KEEP_CHANCE = 0.35D;

    private static final long NORMAL_JITTER_SALT = 0x18D4A1C73B6E5F21L;
    private static final long MOUNTAIN_JITTER_SALT = 0x6A09E667F3BCC909L;
    private static final long MOUNTAIN_GATE_SALT = 0xBB67AE8584CAA73BL;

    private BiomeDistributionFix() {
    }

    public static void logSettings() {
        FixMod.LOGGER.info(
                "[Fix] Biome distribution: enabled={}, normal_region~{}-{} blocks, mountain_region~{}-{} blocks, mountain_keep_chance={}.",
                ENABLED,
                NORMAL_REGION_SIZE * 4L - NORMAL_JITTER * 8L,
                NORMAL_REGION_SIZE * 4L + NORMAL_JITTER * 8L,
                MOUNTAIN_REGION_SIZE * 4L - MOUNTAIN_JITTER * 8L,
                MOUNTAIN_REGION_SIZE * 4L + MOUNTAIN_JITTER * 8L,
                MOUNTAIN_KEEP_CHANCE
        );
    }

    /**
     * Builds one biome palette with a tiny local cache.
     *
     * No global cache is used: this avoids long-lived memory growth and keeps
     * every worldgen worker independent and thread-safe.
     */
    public static RegistryEntry<Biome> sample(
            BiomeSupplier supplier,
            int x,
            int y,
            int z,
            MultiNoiseUtil.MultiNoiseSampler sampler,
            Map<Long, RegistryEntry<Biome>> localCache
    ) {
        if (!ENABLED) {
            return supplier.getBiome(x, y, z, sampler);
        }

        RegistryEntry<Biome> raw = supplier.getBiome(x, y, z, sampler);

        if (isProtected(raw)) {
            return raw;
        }

        RegionKey normalKey = findNearestRegion(
                x,
                z,
                NORMAL_REGION_SIZE,
                NORMAL_JITTER,
                NORMAL_JITTER_SALT
        );

        long cacheKey = BlockPos.asLong(normalKey.cellX(), y, normalKey.cellZ());
        RegistryEntry<Biome> normalBiome = localCache.get(cacheKey);

        if (normalBiome == null) {
            normalBiome = supplier.getBiome(
                    normalKey.sampleX(),
                    y,
                    normalKey.sampleZ(),
                    sampler
            );
            localCache.put(cacheKey, normalBiome);
        }

        if (isProtected(normalBiome)) {
            return raw;
        }

        if (!isMountainBiome(normalBiome)) {
            return normalBiome;
        }

        int gateX = Math.floorDiv(x, MOUNTAIN_REGION_SIZE);
        int gateZ = Math.floorDiv(z, MOUNTAIN_REGION_SIZE);

        if (hashToUnit(gateX, gateZ, MOUNTAIN_GATE_SALT) > MOUNTAIN_KEEP_CHANCE) {
            return raw;
        }

        RegionKey mountainKey = findNearestRegion(
                x,
                z,
                MOUNTAIN_REGION_SIZE,
                MOUNTAIN_JITTER,
                MOUNTAIN_JITTER_SALT
        );

        long mountainCacheKey = BlockPos.asLong(
                mountainKey.cellX(),
                y,
                mountainKey.cellZ()
        );

        RegistryEntry<Biome> mountainBiome = localCache.get(mountainCacheKey);

        if (mountainBiome == null) {
            mountainBiome = supplier.getBiome(
                    mountainKey.sampleX(),
                    y,
                    mountainKey.sampleZ(),
                    sampler
            );
            localCache.put(mountainCacheKey, mountainBiome);
        }

        if (isMountainBiome(mountainBiome)) {
            return mountainBiome;
        }

        return raw;
    }

    private static RegionKey findNearestRegion(
            int x,
            int z,
            int regionSize,
            int jitterAmount,
            long jitterSalt
    ) {
        int cellX = Math.floorDiv(x, regionSize);
        int cellZ = Math.floorDiv(z, regionSize);

        int bestCellX = cellX;
        int bestCellZ = cellZ;
        long bestDistance = Long.MAX_VALUE;

        for (int dx = -1; dx <= 1; ++dx) {
            for (int dz = -1; dz <= 1; ++dz) {
                int candidateX = cellX + dx;
                int candidateZ = cellZ + dz;

                int centerX = candidateX * regionSize
                        + regionSize / 2
                        + jitter(candidateX, candidateZ, jitterSalt, jitterAmount, 0);

                int centerZ = candidateZ * regionSize
                        + regionSize / 2
                        + jitter(candidateX, candidateZ, jitterSalt, jitterAmount, 1);

                long distance = squaredDistance(x, z, centerX, centerZ);

                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestCellX = candidateX;
                    bestCellZ = candidateZ;
                }
            }
        }

        int sampleX = bestCellX * regionSize
                + regionSize / 2
                + jitter(bestCellX, bestCellZ, jitterSalt, jitterAmount, 0);

        int sampleZ = bestCellZ * regionSize
                + regionSize / 2
                + jitter(bestCellX, bestCellZ, jitterSalt, jitterAmount, 1);

        return new RegionKey(bestCellX, bestCellZ, sampleX, sampleZ);
    }

    private static boolean isMountainBiome(RegistryEntry<Biome> biome) {
        String path = biomePath(biome);

        if (path.isEmpty()) {
            return false;
        }

        return path.contains("peak")
                || path.contains("mountain")
                || path.contains("alpine")
                || path.contains("highland")
                || path.contains("highlands")
                || path.contains("jagged")
                || path.contains("windswept")
                || path.contains("slope")
                || path.contains("slopes")
                || path.contains("meadow")
                || path.equals("grove");
    }

    private static boolean isProtected(RegistryEntry<Biome> biome) {
        String path = biomePath(biome);

        if (path.isEmpty()) {
            return true;
        }

        if (path.contains("river") || path.contains("ocean")) {
            return true;
        }

        return path.equals("nether_wastes")
                || path.equals("soul_sand_valley")
                || path.equals("crimson_forest")
                || path.equals("warped_forest")
                || path.equals("basalt_deltas")
                || path.equals("the_end")
                || path.equals("end_highlands")
                || path.equals("end_midlands")
                || path.equals("small_end_islands")
                || path.equals("end_barrens");
    }

    private static String biomePath(RegistryEntry<Biome> biome) {
        return biome.getKey()
                .map(key -> key.getValue().getPath())
                .orElse("");
    }

    private static long squaredDistance(int x1, int z1, int x2, int z2) {
        long dx = (long) x1 - x2;
        long dz = (long) z1 - z2;
        return dx * dx + dz * dz;
    }

    private static int jitter(
            int cellX,
            int cellZ,
            long salt,
            int amount,
            int axis
    ) {
        if (amount <= 0) {
            return 0;
        }

        long mixed = mix(
                salt
                        ^ ((long) cellX * 0x9E3779B97F4A7C15L)
                        ^ ((long) cellZ * 0xC2B2AE3D27D4EB4FL)
                        ^ (long) axis * 0x165667B19E3779F9L
        );

        return Math.floorMod(mixed, amount * 2 + 1) - amount;
    }

    private static double hashToUnit(int x, int z, long salt) {
        long mixed = mix(
                salt
                        ^ ((long) x * 0x9E3779B97F4A7C15L)
                        ^ ((long) z * 0xC2B2AE3D27D4EB4FL)
        );

        return (mixed >>> 11) * 0x1.0p-53;
    }

    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    private record RegionKey(int cellX, int cellZ, int sampleX, int sampleZ) {
    }
}
