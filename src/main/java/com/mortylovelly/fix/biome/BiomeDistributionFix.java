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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Enlarges horizontal biome regions while preserving the original biome source.
 *
 * The pass runs after Noisium has populated each 4x4x4 biome palette.
 * The horizontal biome layout is normalized to large deterministic regions,
 * while the original biome source is still used to choose the actual biome.
 *
 * Horizontal coordinates here are biome-source coordinates: one coordinate
 * corresponds to four horizontal blocks.
 */
public final class BiomeDistributionFix {
    private static final boolean ENABLED = true;

    /*
     * Normal regions:
     *   1800 source units = 7200 blocks nominal width.
     *   Jitter can move centers by +/- 450 source units.
     *   Resulting nearest-center region width is roughly 3600-10800 blocks.
     */
    private static final int NORMAL_REGION_SIZE = 1800;
    private static final int NORMAL_JITTER = 450;

    /*
     * Mountain regions:
     *   2500 source units = 10000 blocks nominal width.
     *   Jitter can move centers by +/- 600 source units.
     *   Resulting nearest-center region width is roughly 5200-14800 blocks.
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
     *
     * The expensive horizontal region lookup is calculated once per 4x4
     * horizontal palette column and reused for all four Y samples.
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
        RegionKey[][] normalKeys = new RegionKey[4][4];

        for (int posZ = 0; posZ < 4; ++posZ) {
            for (int posX = 0; posX < 4; ++posX) {
                normalKeys[posX][posZ] = findNearestRegion(
                        x + posX,
                        z + posZ,
                        NORMAL_REGION_SIZE,
                        NORMAL_JITTER,
                        NORMAL_JITTER_SALT
                );
            }
        }

        for (int posY = 0; posY < 4; ++posY) {
            int sampleY = y + posY;
            Map<Long, Selection> decisionCache = new HashMap<>(4);

            for (int posZ = 0; posZ < 4; ++posZ) {
                for (int posX = 0; posX < 4; ++posX) {
                    CELL_SAMPLES.incrementAndGet();

                    RegistryEntry<Biome> raw = biomeContainer.get(posX, posY, posZ);

                    if (isProtected(raw)) {
                        PROTECTED_CELLS.incrementAndGet();
                        continue;
                    }

                    RegionKey normalKey = normalKeys[posX][posZ];
                    long decisionKey = BlockPos.asLong(
                            normalKey.cellX(),
                            sampleY,
                            normalKey.cellZ()
                    );

                    Selection selection = decisionCache.get(decisionKey);

                    if (selection == null) {
                        selection = selectBiome(
                                normalKey,
                                sampleY,
                                supplier,
                                sampler,
                                regionCache
                        );
                        decisionCache.put(decisionKey, selection);

                        logDecision(
                                populateCall,
                                x + posX,
                                sampleY,
                                z + posZ,
                                raw,
                                selection
                        );
                    }

                    if (selection.keepRaw()) {
                        continue;
                    }

                    RegistryEntry<Biome> finalBiome = selection.biome();

                    if (finalBiome != raw) {
                        NORMAL_REPLACEMENTS.incrementAndGet();
                        biomeContainer.swapUnsafe(
                                posX,
                                posY,
                                posZ,
                                finalBiome
                        );
                    }
                }
            }
        }

        if (populateCall == 1L || populateCall % 1000L == 0L) {
            FixMod.LOGGER.info(
                    "[Fix][BiomeStats] populate_calls={}, cells={}, protected={}, changed={}, mountain_candidates={}, mountain_kept={}, mountain_rejected={}, fallback_found={}, fallback_failed={}.",
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

    private static Selection selectBiome(
            RegionKey normalKey,
            int y,
            BiomeSupplier supplier,
            MultiNoiseUtil.MultiNoiseSampler sampler,
            Map<Long, RegistryEntry<Biome>> regionCache
    ) {
        RegistryEntry<Biome> normalBiome = getRegionBiome(
                normalKey,
                y,
                supplier,
                sampler,
                regionCache
        );

        if (isProtected(normalBiome)) {
            return Selection.keepRaw("normal-protected", normalBiome);
        }

        if (!isMountainBiome(normalBiome)) {
            return Selection.use(normalBiome, "normal", normalBiome, null, -1.0D);
        }

        MOUNTAIN_CANDIDATES.incrementAndGet();

        /*
         * Mountains are selected on their own, larger lattice. This is what
         * makes a retained mountain zone substantially larger than normal
         * biome zones.
         */
        RegionKey mountainKey = findNearestRegion(
                normalKey.sampleX(),
                normalKey.sampleZ(),
                MOUNTAIN_REGION_SIZE,
                MOUNTAIN_JITTER,
                MOUNTAIN_JITTER_SALT
        );

        RegistryEntry<Biome> mountainBiome = getRegionBiome(
                mountainKey,
                y,
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
            MOUNTAIN_KEPT.incrementAndGet();
            return Selection.use(
                    mountainBiome,
                    "mountain-kept",
                    normalBiome,
                    mountainKey,
                    gate
            );
        }

        MOUNTAIN_REJECTED.incrementAndGet();

        RegistryEntry<Biome> fallback = findNearestNonMountainBiome(
                normalKey,
                y,
                supplier,
                sampler,
                regionCache
        );

        if (fallback != null) {
            FALLBACK_FOUND.incrementAndGet();
            return Selection.use(
                    fallback,
                    "mountain-rejected-fallback",
                    normalBiome,
                    mountainKey,
                    gate
            );
        }

        FALLBACK_FAILED.incrementAndGet();
        return Selection.keepRaw(
                "mountain-rejected-raw-fallback",
                normalBiome,
                mountainKey,
                gate
        );
    }

    private static RegistryEntry<Biome> getRegionBiome(
            RegionKey key,
            int y,
            BiomeSupplier supplier,
            MultiNoiseUtil.MultiNoiseSampler sampler,
            Map<Long, RegistryEntry<Biome>> regionCache
    ) {
        long cacheKey = BlockPos.asLong(
                key.cellX(),
                y,
                key.cellZ()
        );

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
            RegionKey origin,
            int y,
            BiomeSupplier supplier,
            MultiNoiseUtil.MultiNoiseSampler sampler,
            Map<Long, RegistryEntry<Biome>> regionCache
    ) {
        RegionKey bestKey = null;
        RegistryEntry<Biome> bestBiome = null;
        long bestDistance = Long.MAX_VALUE;

        /*
         * Search a 5x5 neighborhood. Denied mountain candidates therefore
         * merge into a nearby large normal region instead of restoring the
         * original tiny mountain patches.
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
                        origin.sampleX(),
                        origin.sampleZ(),
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
            Selection selection
    ) {
        if (DETAILED_LOGS.getAndIncrement() >= 48) {
            return;
        }

        RegionKey mountainKey = selection.mountainKey();

        FixMod.LOGGER.info(
                "[Fix][BiomeProbe] call={}, pos=({}, {}, {}), raw={}, normalOrSelected={}, final={}, decision={}, normalCenter=({}, {}), mountainCenter={}, mountainGate={}.",
                populateCall,
                x * 4,
                y * 4,
                z * 4,
                biomeId(raw),
                biomeId(selection.biome()),
                selection.keepRaw() ? biomeId(raw) : biomeId(selection.biome()),
                selection.decision(),
                selection.normalSource().getKey().map(key -> key.getValue().toString()).orElse("<unknown>"),
                mountainKey == null
                        ? "-"
                        : "(" + mountainKey.sampleX() * 4 + ", " + mountainKey.sampleZ() * 4 + ")",
                mountainKey == null
                        ? "-"
                        : String.format(java.util.Locale.ROOT, "%.3f/%.2f", selection.mountainGate(), MOUNTAIN_KEEP_CHANCE)
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
                || path.contains("slopes");
    }

    private static boolean isProtected(RegistryEntry<Biome> biome) {
        String path = biomePath(biome);

        if (path.isEmpty()) {
            return true;
        }

        if (path.contains("river")
                || path.contains("ocean")
                || path.contains("cave")
                || path.contains("deep_dark")) {
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

    private record RegionKey(
            int cellX,
            int cellZ,
            int sampleX,
            int sampleZ
    ) {
    }

    private record Selection(
            RegistryEntry<Biome> biome,
            boolean keepRaw,
            String decision,
            RegistryEntry<Biome> normalSource,
            RegionKey mountainKey,
            double mountainGate
    ) {
        private static Selection use(
                RegistryEntry<Biome> biome,
                String decision,
                RegistryEntry<Biome> normalSource,
                RegionKey mountainKey,
                double mountainGate
        ) {
            return new Selection(
                    biome,
                    false,
                    decision,
                    normalSource,
                    mountainKey,
                    mountainGate
            );
        }

        private static Selection keepRaw(
                String decision,
                RegistryEntry<Biome> normalSource
        ) {
            return keepRaw(decision, normalSource, null, -1.0D);
        }

        private static Selection keepRaw(
                String decision,
                RegistryEntry<Biome> normalSource,
                RegionKey mountainKey,
                double mountainGate
        ) {
            return new Selection(
                    normalSource,
                    true,
                    decision,
                    normalSource,
                    mountainKey,
                    mountainGate
            );
        }
    }
}
