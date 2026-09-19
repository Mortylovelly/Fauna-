package com.mortylovelly.fix.biome;

import com.mortylovelly.fix.FixMod;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeSupplier;
import net.minecraft.world.biome.source.util.MultiNoiseUtil;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps the original biome source and climate logic intact, but samples it on
 * larger, irregular horizontal regions before the biome palette is written to
 * a chunk.
 *
 * Normal biome regions are deliberately measured in THOUSANDS of blocks:
 * roughly 3,600-10,800 blocks from one side of a region to the other.
 * Mountain biome regions are even larger, roughly 5,200-14,800 blocks wide,
 * and are additionally throttled so large mountain zones remain rare.
 *
 * Rivers and oceans stay at the source resolution because forcing them into
 * large two-dimensional cells would destroy their natural corridor/coastline
 * behavior. Cave/Nether/End style biomes are also left untouched.
 */
public final class BiomeDistributionFix {
    private static final boolean ENABLED = true;

    /**
     * Region lattice size in biome-source coordinates.
     * 1 source unit corresponds to 4 horizontal blocks.
     *
     * Normal regions therefore vary roughly from 3,600 to 10,800 blocks
     * across after deterministic center jitter is applied.
     */
    private static final int NORMAL_REGION_SIZE = 1800;
    private static final int NORMAL_JITTER = 450;

    /**
     * Mountain regions are intentionally larger: roughly 5,200-14,800 blocks
     * across after jitter. Their individual cells are also gated below so
     * only a minority of candidate mountain zones survive.
     */
    private static final int MOUNTAIN_REGION_SIZE = 2500;
    private static final int MOUNTAIN_JITTER = 600;

    /**
     * Fraction of large mountain cells that are retained. A lower value makes
     * mountain-biome families occur less often without changing ordinary biomes.
     */
    private static final double MOUNTAIN_KEEP_CHANCE = 0.35D;

    /** Limit cached source samples so long exploration cannot grow memory forever. */
    private static final int CACHE_LIMIT = 32768;

    private static final long NORMAL_JITTER_SALT = 0x18D4A1C73B6E5F21L;
    private static final long MOUNTAIN_JITTER_SALT = 0x6A09E667F3BCC909L;
    private static final long MOUNTAIN_GATE_SALT = 0xBB67AE8584CAA73BL;

    private static final Object CACHE_LOCK = new Object();
    private static final Map<BiomeSupplier, SupplierCache> CACHES = new WeakHashMap<>();

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

    public static RegistryEntry<Biome> sample(
            BiomeSupplier supplier,
            int x,
            int y,
            int z,
            MultiNoiseUtil.MultiNoiseSampler sampler
    ) {
        RegistryEntry<Biome> raw = supplier.getBiome(x, y, z, sampler);

        if (!ENABLED || isProtected(raw)) {
            return raw;
        }

        RegistryEntry<Biome> normalBiome = sampleRegion(
                supplier,
                x,
                y,
                z,
                sampler,
                NORMAL_REGION_SIZE,
                NORMAL_JITTER,
                NORMAL_JITTER_SALT
        );

        if (isProtected(normalBiome)) {
            return raw;
        }

        if (!isMountainBiome(normalBiome)) {
            return normalBiome;
        }

        RegistryEntry<Biome> mountainBiome = sampleRegion(
                supplier,
                x,
                y,
                z,
                sampler,
                MOUNTAIN_REGION_SIZE,
                MOUNTAIN_JITTER,
                MOUNTAIN_JITTER_SALT
        );

        if (!isMountainBiome(mountainBiome)) {
            mountainBiome = normalBiome;
        }

        if (!isMountainBiome(mountainBiome)) {
            return normalBiome;
        }

        int mountainGateX = Math.floorDiv(x, MOUNTAIN_REGION_SIZE);
        int mountainGateZ = Math.floorDiv(z, MOUNTAIN_REGION_SIZE);

        if (hashToUnit(mountainGateX, mountainGateZ, MOUNTAIN_GATE_SALT) <= MOUNTAIN_KEEP_CHANCE) {
            return mountainBiome;
        }

        RegistryEntry<Biome> fallback = findNearestNonMountain(
                supplier,
                x,
                y,
                z,
                sampler,
                normalBiome
        );

        return fallback != null ? fallback : normalBiome;
    }

    private static RegistryEntry<Biome> sampleRegion(
            BiomeSupplier supplier,
            int x,
            int y,
            int z,
            MultiNoiseUtil.MultiNoiseSampler sampler,
            int regionSize,
            int jitterAmount,
            long jitterSalt
    ) {
        int cellX = Math.floorDiv(x, regionSize);
        int cellZ = Math.floorDiv(z, regionSize);

        int bestCellX = cellX;
        int bestCellZ = cellZ;
        long bestDistance = Long.MAX_VALUE;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
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

        return sampleCell(
                supplier,
                bestCellX,
                y,
                bestCellZ,
                regionSize,
                jitterAmount,
                jitterSalt,
                sampler
        );
    }

    private static RegistryEntry<Biome> sampleCell(
            BiomeSupplier supplier,
            int cellX,
            int y,
            int cellZ,
            int regionSize,
            int jitterAmount,
            long jitterSalt,
            MultiNoiseUtil.MultiNoiseSampler sampler
    ) {
        int centerX = cellX * regionSize
                + regionSize / 2
                + jitter(cellX, cellZ, jitterSalt, jitterAmount, 0);

        int centerZ = cellZ * regionSize
                + regionSize / 2
                + jitter(cellX, cellZ, jitterSalt, jitterAmount, 1);

        SupplierCache cache = getCache(supplier);
        long key = BlockPos.asLong(cellX, y, cellZ);

        RegistryEntry<Biome> cached = cache.samples.get(key);
        if (cached != null) {
            return cached;
        }

        RegistryEntry<Biome> sampled = supplier.getBiome(centerX, y, centerZ, sampler);

        if (cache.samples.size() >= CACHE_LIMIT) {
            cache.samples.clear();
        }

        RegistryEntry<Biome> previous = cache.samples.putIfAbsent(key, sampled);
        return previous != null ? previous : sampled;
    }

    private static RegistryEntry<Biome> findNearestNonMountain(
            BiomeSupplier supplier,
            int x,
            int y,
            int z,
            MultiNoiseUtil.MultiNoiseSampler sampler,
            RegistryEntry<Biome> current
    ) {
        if (!isMountainBiome(current)) {
            return current;
        }

        int step = NORMAL_REGION_SIZE / 2;

        int[][] offsets = {
                {-step, 0},
                {step, 0},
                {0, -step},
                {0, step},
                {-step, -step},
                {step, -step},
                {-step, step},
                {step, step},
                {-step * 2, 0},
                {step * 2, 0},
                {0, -step * 2},
                {0, step * 2}
        };

        RegistryEntry<Biome> best = null;
        long bestDistance = Long.MAX_VALUE;

        for (int[] offset : offsets) {
            int sampleX = x + offset[0];
            int sampleZ = z + offset[1];

            RegistryEntry<Biome> candidate = sampleRegion(
                    supplier,
                    sampleX,
                    y,
                    sampleZ,
                    sampler,
                    NORMAL_REGION_SIZE,
                    NORMAL_JITTER,
                    NORMAL_JITTER_SALT
            );

            if (isProtected(candidate) || isMountainBiome(candidate)) {
                continue;
            }

            long distance = (long) offset[0] * offset[0]
                    + (long) offset[1] * offset[1];

            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }

        return best;
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

    private static SupplierCache getCache(BiomeSupplier supplier) {
        synchronized (CACHE_LOCK) {
            return CACHES.computeIfAbsent(supplier, ignored -> new SupplierCache());
        }
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

    private static final class SupplierCache {
        private final ConcurrentHashMap<Long, RegistryEntry<Biome>> samples =
                new ConcurrentHashMap<>();
    }
}
