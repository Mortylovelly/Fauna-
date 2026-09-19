package com.mortylovelly.fix.biome;

import com.mortylovelly.fix.FixMod;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeSupplier;
import net.minecraft.world.biome.source.util.MultiNoiseUtil;
import net.minecraft.world.chunk.PalettedContainer;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Enlarges horizontal biome regions while preserving the original biome source.
 *
 * The pass runs after Noisium has already populated each 4x4x4 biome palette.
 * Instead of replacing the whole populateBiomes method, the mixin changes the
 * completed palette. This is important for compatibility with Noisium and C2ME.
 *
 * Horizontal coordinates here are biome-source coordinates: one coordinate
 * corresponds to four horizontal blocks.
 */
public final class BiomeDistributionFix {
    private static final boolean ENABLED = true;

    /*
     * Normal regions:
     *   1800 source units = 7200 blocks nominal cell width.
     *   Jitter can move centers by +/- 450 source units.
     *   The resulting nearest-center region width is roughly 3600-10800 blocks.
     */
    private static final int NORMAL_REGION_SIZE = 1800;
    private static final int NORMAL_JITTER = 450;

    /*
     * Mountain regions:
     *   2500 source units = 10000 blocks nominal cell width.
     *   Jitter can move centers by +/- 600 source units.
     *   Resulting width is roughly 5200-14800 blocks.
     */
    private static final int MOUNTAIN_REGION_SIZE = 2500;
    private static final int MOUNTAIN_JITTER = 600;

    /*
     * Only a fraction of large mountain candidates are allowed to remain
     * mountain biomes.
     */
    private static final double MOUNTAIN_KEEP_CHANCE = 0.35D;

    private static final long NORMAL_JITTER_SALT = 0x18D4A1C73B6E5F21L;
    private static final long MOUNTAIN_JITTER_SALT = 0x6A09E667F3BCC909L;
    private static final long MOUNTAIN_GATE_SALT = 0xBB67AE8584CAA73BL;

    private static final AtomicBoolean FIRST_HOOK_LOGGED = new AtomicBoolean();
    private static final AtomicInteger DETAILED_LOGS = new AtomicInteger();

    private static final AtomicLong POPULATE_CALLS = new AtomicLong();
    private static final AtomicLong CELL_SAMPLES = new AtomicLong();
    private static final AtomicLong PROTECTED_CELLS = new AtomicLong();
    private static final AtomicLong NORMAL_REPLACEMENTS = new AtomicLong();
    private static final AtomicLong MOUNTAIN_CANDIDATES = new AtomicLong();
    private static final AtomicLong MOUNTAIN_KEPT = new AtomicLong();
    private static final AtomicLong MOUNTAIN_REJECTED = new AtomicLong();
    private static final AtomicLong FALLBACK_FOUND = new AtomicLong();
    private static final AtomicLong FALLBACK_FAILED = new AtomicLong();

    private BiomeDistributionFix() {
    }

    public static void logSettings() {
        FixMod.LOGGER.info(
                "[Fix] Biome distribution: enabled={}, hook=post-populate(TAIL), normal_region~{}-{} blocks, mountain_region~{}-{} blocks, mountain_keep_chance={}.",
                ENABLED,
                NORMAL_REGION_SIZE * 4L - NORMAL_JITTER * 8L,
                NORMAL_REGION_SIZE * 4L + NORMAL_JITTER * 8L,
                MOUNTAIN_REGION_SIZE * 4L - MOUNTAIN_JITTER * 8L,
                MOUNTAIN_REGION_SIZE * 4L + MOUNTAIN_JITTER * 8L,
                MOUNTAIN_KEEP_CHANCE
        );
    }

    /**
     * Processes the already populated Noisium biome palette.
     */
    public static void process(
            PalettedContainer<RegistryEntry<Biome>> biomeContainer,
            BiomeSupplier supplier,
            MultiNoiseUtil.MultiNoiseSampler sampler,
            int x,
            int y,
            int z
    ) {
        if (!ENABLED) {
            return;
        }

        long populateCall = POPULATE_CALLS.incrementAndGet();

        if (FIRST_HOOK_LOGGED.compareAndSet(false, true)) {
            FixMod.LOGGER.info(
                    "[Fix] Biome distribution hook is ACTIVE inside ChunkSection.populateBiomes after Noisium."
            );
        }

        Map<Long, RegistryEntry<Biome>> regionCache = new HashMap<>(16);
        Map<Long, RegionKey> regionKeys = new HashMap<>(16);

        for (int posY = 0; posY < 4; ++posY) {
            for (int posZ = 0; posZ < 4; ++posZ) {
                for (int posX = 0; posX < 4; ++posX) {
                    CELL_SAMPLES.incrementAndGet();

                    int sampleX = x + posX;
                    int sampleY = y + posY;
                    int sampleZ = z + posZ;

                    RegistryEntry<Biome> raw = biomeContainer.get(posX, posY, posZ);

                    if (isProtected(raw)) {
                        PROTECTED_CELLS.incrementAndGet();
                        continue;
                    }

                    RegionKey normalKey = findNearestRegion(
                            sampleX,
                            sampleZ,
                            NORMAL_REGION_SIZE,
                            NORMAL_JITTER,
                            NORMAL_JITTER_SALT
                    );

                    RegistryEntry<Biome> normalBiome = getRegionBiome(
                            normalKey,
                            sampleY,
                            supplier,
                            sampler,
                            regionCache
                    );

                    RegistryEntry<Biome> finalBiome = normalBiome;
                    String decision = "normal";

                    if (isProtected(normalBiome)) {
                        finalBiome = raw;
                        decision = "normal-protected";
                    } else if (isMountainBiome(normalBiome)) {
                        MOUNTAIN_CANDIDATES.incrementAndGet();

                        RegionKey mountainKey = findNearestRegion(
                                sampleX,
                                sampleZ,
                                MOUNTAIN_REGION_SIZE,
                                MOUNTAIN_JITTER,
                                MOUNTAIN_JITTER_SALT
                        );

                        RegistryEntry<Biome> mountainBiome = getRegionBiome(
                                mountainKey,
                                sampleY,
                                supplier,
                                sampler,
                                regionCache
                        );

                        double gate = hashToUnit(
                                mountainKey.cellX(),
                                mountainKey.cellZ(),
                                MOUNTAIN_GATE_SALT
                        );

                        if (gate <= MOUNTAIN_KEEP_CHANCE && isMountainBiome(mountainBiome)) {
                            finalBiome = mountainBiome;
                            MOUNTAIN_KEPT.incrementAndGet();
                            decision = "mountain-kept";
                        } else {
                            MOUNTAIN_REJECTED.incrementAndGet();

                            RegistryEntry<Biome> fallback = findNearestNonMountainBiome(
                                    sampleX,
                                    sampleY,
                                    sampleZ,
                                    normalKey,
                                    supplier,
                                    sampler,
                                    regionCache,
                                    regionKeys
                            );

                            if (fallback != null) {
                                finalBiome = fallback;
                                FALLBACK_FOUND.incrementAndGet();
                                decision = "mountain-rejected-fallback";
                            } else {
                                finalBiome = raw;
                                FALLBACK_FAILED.incrementAndGet();
                                decision = "mountain-rejected-raw-fallback";
                            }

                            logDecision(
                                    populateCall,
                                    sampleX,
                                    sampleY,
                                    sampleZ,
                                    raw,
                                    normalBiome,
                                    finalBiome,
                                    normalKey,
                                    decision
                            );
                        }
                    }

                    if (finalBiome != raw) {
                        NORMAL_REPLACEMENTS.incrementAndGet();
                        biomeContainer.swapUnsafe(posX, posY, posZ, finalBiome);

                        if (!decision.equals("mountain-rejected-fallback")) {
                            logDecision(
                                    populateCall,
                                    sampleX,
                                    sampleY,
                                    sampleZ,
                                    raw,
                                    normalBiome,
                                    finalBiome,
                                    normalKey,
                                    decision
                            );
                        }
                    }
                }
            }
        }

        if (populateCall == 1L || populateCall % 1000L == 0L) {
            FixMod.LOGGER.info(
                    "[Fix][BiomeStats] populate_calls={}, cells={}, protected={}, normal_changed={}, mountain_candidates={}, mountain_kept={}, mountain_rejected={}, fallback_found={}, fallback_failed={}.",
                    POPULATE_CALLS.get(),
                    CELL_SAMPLES.get(),
                    PROTECTED_CELLS.get(),
                    NORMAL_REPLACEMENTS.get(),
                    MOUNTAIN_CANDIDATES.get(),
                    MOUNTAIN_KEPT.get(),
                    MOUNTAIN_REJECTED.get(),
                    FALLBACK_FOUND.get(),
                    FALLBACK_FAILED.get()
            );
        }
    }

    private static RegistryEntry<Biome> getRegionBiome(
            RegionKey key,
            int y,
            BiomeSupplier supplier,
            MultiNoiseUtil.MultiNoiseSampler sampler,
            Map<Long, RegistryEntry<Biome>> regionCache
    ) {
        long cacheKey = BlockPos.asLong(key.cellX(), y, key.cellZ());
        RegistryEntry<Biome> biome = regionCache.get(cacheKey);

        if (biome == null) {
            biome = supplier.getBiome(
                    key.sampleX(),
                    y,
                    key.sampleZ(),
                    sampler
            );
            regionCache.put(cacheKey, biome);
        }

        return biome;
    }

    private static RegistryEntry<Biome> findNearestNonMountainBiome(
            int x,
            int y,
            int z,
            RegionKey origin,
            BiomeSupplier supplier,
            MultiNoiseUtil.MultiNoiseSampler sampler,
            Map<Long, RegistryEntry<Biome>> regionCache,
            Map<Long, RegionKey> regionKeys
    ) {
        RegionKey bestKey = null;
        long bestDistance = Long.MAX_VALUE;
        RegistryEntry<Biome> bestBiome = null;

        /*
         * Search a small 5x5 neighborhood. Mountain regions that are denied
         * are therefore absorbed into the nearest normal biome region instead
         * of immediately falling back to the original tiny mountain patches.
         */
        for (int dx = -2; dx <= 2; ++dx) {
            for (int dz = -2; dz <= 2; ++dz) {
                if (dx == 0 && dz == 0) {
                    continue;
                }

                RegionKey candidate = regionKey(
                        origin.cellX() + dx,
                        origin.cellZ() + dz,
                        NORMAL_REGION_SIZE,
                        NORMAL_JITTER,
                        NORMAL_JITTER_SALT
                );

                long keyId = BlockPos.asLong(candidate.cellX(), y, candidate.cellZ());
                regionKeys.putIfAbsent(keyId, candidate);

                RegistryEntry<Biome> candidateBiome = getRegionBiome(
                        candidate,
                        y,
                        supplier,
                        sampler,
                        regionCache
                );

                if (isProtected(candidateBiome) || isMountainBiome(candidateBiome)) {
                    continue;
                }

                long distance = squaredDistance(
                        x,
                        z,
                        candidate.sampleX(),
                        candidate.sampleZ()
                );

                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestKey = candidate;
                    bestBiome = candidateBiome;
                }
            }
        }

        return bestBiome;
    }

    private static void logDecision(
            long populateCall,
            int x,
            int y,
            int z,
            RegistryEntry<Biome> raw,
            RegistryEntry<Biome> normalBiome,
            RegistryEntry<Biome> finalBiome,
            RegionKey normalKey,
            String decision
    ) {
        if (DETAILED_LOGS.getAndIncrement() >= 48) {
            return;
        }

        FixMod.LOGGER.info(
                "[Fix][BiomeProbe] call={}, pos=({}, {}, {}), raw={}, normal={}, final={}, regionCell=({}, {}), regionCenter=({}, {}), decision={}.",
                populateCall,
                x * 4,
                y * 4,
                z * 4,
                biomeId(raw),
                biomeId(normalBiome),
                biomeId(finalBiome),
                normalKey.cellX(),
                normalKey.cellZ(),
                normalKey.sampleX() * 4,
                normalKey.sampleZ() * 4,
                decision
        );
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

        RegionKey best = null;
        long bestDistance = Long.MAX_VALUE;

        for (int dx = -1; dx <= 1; ++dx) {
            for (int dz = -1; dz <= 1; ++dz) {
                RegionKey candidate = regionKey(
                        cellX + dx,
                        cellZ + dz,
                        regionSize,
                        jitterAmount,
                        jitterSalt
                );

                long distance = squaredDistance(
                        x,
                        z,
                        candidate.sampleX(),
                        candidate.sampleZ()
                );

                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = candidate;
                }
            }
        }

        return best;
    }

    private static RegionKey regionKey(
            int cellX,
            int cellZ,
            int regionSize,
            int jitterAmount,
            long jitterSalt
    ) {
        int sampleX = cellX * regionSize
                + regionSize / 2
                + jitter(cellX, cellZ, jitterSalt, jitterAmount, 0);

        int sampleZ = cellZ * regionSize
                + regionSize / 2
                + jitter(cellX, cellZ, jitterSalt, jitterAmount, 1);

        return new RegionKey(cellX, cellZ, sampleX, sampleZ);
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

    private static String biomeId(RegistryEntry<Biome> biome) {
        return biome.getKey()
                .map(key -> key.getValue().toString())
                .orElse("<unknown>");
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
