package com.mortylovelly.fix.biome;

import com.mortylovelly.fix.FixMod;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeSupplier;
import net.minecraft.world.biome.source.util.MultiNoiseUtil;
import net.minecraft.world.chunk.PalettedContainer;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Creates large deterministic biome regions by sampling the existing biome
 * source at jittered region centres. The normal-biome grid and mountain grid
 * are independent so mountain areas do not inherit the borders of the normal
 * grid.
 *
 * Coordinates passed to BiomeSupplier are biome-source (quart) coordinates:
 * one horizontal coordinate step corresponds to four blocks.
 */
public final class BiomeDistributionFix {
    private static final boolean ENABLED = true;

    /*
     * Normal biomes: 3500 quart units = 14000 blocks nominal spacing.
     * With centre jitter, estimated region widths are about 8400-19600 blocks.
     */
    private static final int NORMAL_REGION_SIZE = 3500;
    private static final int NORMAL_JITTER = 700;

    /*
     * Mountains: 6000 quart units = 24000 blocks nominal spacing.
     * With centre jitter, estimated region widths are about 14400-33600 blocks.
     */
    private static final int MOUNTAIN_REGION_SIZE = 6000;
    private static final int MOUNTAIN_JITTER = 1200;
    private static final double MOUNTAIN_KEEP_CHANCE = 0.35D;

    private static final long NORMAL_JITTER_SALT = 0x18D4A1C73B6E5F21L;
    private static final long MOUNTAIN_JITTER_SALT = 0x6A09E667F3BCC909L;
    private static final long MOUNTAIN_GATE_SALT = 0xBB67AE8584CAA73BL;
    private static final int MAX_DETAILED_PROBES = 64;

    private static final AtomicBoolean FIRST_HOOK_LOGGED = new AtomicBoolean();
    private static final AtomicInteger DETAILED_LOGS = new AtomicInteger();

    private static final AtomicLong POPULATE_CALLS = new AtomicLong();
    private static final AtomicLong CELL_SAMPLES = new AtomicLong();
    private static final AtomicLong PROTECTED_CELLS = new AtomicLong();
    private static final AtomicLong REGION_CACHE_MISSES = new AtomicLong();
    private static final AtomicLong CELLS_CHANGED = new AtomicLong();
    private static final AtomicLong MOUNTAIN_CANDIDATE_DECISIONS = new AtomicLong();
    private static final AtomicLong MOUNTAIN_NEIGHBOR_HITS = new AtomicLong();
    private static final AtomicLong MOUNTAIN_NEIGHBOR_MISSES = new AtomicLong();
    private static final AtomicLong MOUNTAIN_KEPT = new AtomicLong();
    private static final AtomicLong MOUNTAIN_REJECTED = new AtomicLong();
    private static final AtomicLong FALLBACK_FOUND = new AtomicLong();
    private static final AtomicLong FALLBACK_FAILED = new AtomicLong();

    private BiomeDistributionFix() {
    }

    public static void logSettings() {
        FixMod.LOGGER.info(
                "[Fix][BiomeDiag] algorithm=v3, enabled={}, coordinate_scale=4 blocks/quart, " +
                        "normal_regions~{}-{} blocks (nominal {}), mountain_regions~{}-{} blocks " +
                        "(nominal {}), mountain_keep_chance={}, detail_probe_limit={}.",
                ENABLED,
                regionWidthMin(NORMAL_REGION_SIZE, NORMAL_JITTER),
                regionWidthMax(NORMAL_REGION_SIZE, NORMAL_JITTER),
                NORMAL_REGION_SIZE * 4L,
                regionWidthMin(MOUNTAIN_REGION_SIZE, MOUNTAIN_JITTER),
                regionWidthMax(MOUNTAIN_REGION_SIZE, MOUNTAIN_JITTER),
                MOUNTAIN_REGION_SIZE * 4L,
                MOUNTAIN_KEEP_CHANCE,
                MAX_DETAILED_PROBES
        );
        FixMod.LOGGER.info(
                "[Fix][BiomeDiag] Waiting for ChunkSection.populateBiomes hook. " +
                        "A successful runtime hook must later print [Fix][BiomeHook] ACTIVE."
        );
    }

    /**
     * Runs after the original biome palette has been populated.
     * One region decision is reused by all matching quart cells in this
     * section, and biome-source lookups are cached per grid, cell and Y.
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
                    "[Fix][BiomeHook] ACTIVE: ChunkSection.populateBiomes post-populate callback executed; " +
                            "the biome distribution pass is running."
            );
        }

        Map<RegionSampleKey, RegistryEntry<Biome>> regionCache = new HashMap<>(48);
        Map<DecisionKey, Selection> decisionCache = new HashMap<>(32);
        long sampledCells = 0L;
        long protectedCells = 0L;
        long changedCells = 0L;
        RegionKey[][] normalKeys = new RegionKey[4][4];
        RegionKey[][] mountainKeys = new RegionKey[4][4];

        for (int posZ = 0; posZ < 4; ++posZ) {
            for (int posX = 0; posX < 4; ++posX) {
                int sampleX = x + posX;
                int sampleZ = z + posZ;

                normalKeys[posX][posZ] = findNearestRegion(
                        sampleX, sampleZ,
                        NORMAL_REGION_SIZE, NORMAL_JITTER, NORMAL_JITTER_SALT
                );
                mountainKeys[posX][posZ] = findNearestRegion(
                        sampleX, sampleZ,
                        MOUNTAIN_REGION_SIZE, MOUNTAIN_JITTER, MOUNTAIN_JITTER_SALT
                );
            }
        }

        for (int posY = 0; posY < 4; ++posY) {
            int sampleY = y + posY;

            for (int posZ = 0; posZ < 4; ++posZ) {
                for (int posX = 0; posX < 4; ++posX) {
                    sampledCells++;

                    RegistryEntry<Biome> raw = biomeContainer.get(posX, posY, posZ);
                    if (isProtected(raw)) {
                        protectedCells++;
                        continue;
                    }

                    RegionKey normalKey = normalKeys[posX][posZ];
                    RegionKey mountainKey = mountainKeys[posX][posZ];
                    DecisionKey decisionKey = new DecisionKey(normalKey, mountainKey, sampleY);

                    Selection selection = decisionCache.get(decisionKey);
                    if (selection == null) {
                        selection = selectBiome(
                                normalKey,
                                mountainKey,
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
                                normalKey,
                                mountainKey,
                                selection
                        );
                    }

                    if (selection.keepRaw()) {
                        continue;
                    }

                    RegistryEntry<Biome> finalBiome = selection.biome();
                    if (!finalBiome.equals(raw)) {
                        biomeContainer.swapUnsafe(posX, posY, posZ, finalBiome);
                        changedCells++;
                    }
                }
            }
        }

        CELL_SAMPLES.addAndGet(sampledCells);
        PROTECTED_CELLS.addAndGet(protectedCells);
        CELLS_CHANGED.addAndGet(changedCells);
        REGION_CACHE_MISSES.addAndGet(regionCache.size());

        /*
         * Biome population is called heavily by C2ME and Distant Horizons.
         * Log every 16,384 calls instead of every 256 to avoid drowning
         * latest.log and adding avoidable I/O pressure during world generation.
         */
        if (populateCall == 1L || populateCall == 64L || populateCall % 16_384L == 0L) {
            FixMod.LOGGER.info(
                    "[Fix][BiomeStats] populate_calls={}, cells_sampled={}, protected_raw_cells={}, " +
                            "region_cache_misses={}, cells_changed={}, mountain_candidate_decisions={}, " +
                            "mountain_neighbour_search_hits={}, mountain_neighbour_search_misses={}, " +
                            "mountain_regions_kept={}, mountain_regions_rejected={}, " +
                            "non_mountain_fallback_found={}, non_mountain_fallback_missing={}.",
                    POPULATE_CALLS.get(),
                    CELL_SAMPLES.get(),
                    PROTECTED_CELLS.get(),
                    REGION_CACHE_MISSES.get(),
                    CELLS_CHANGED.get(),
                    MOUNTAIN_CANDIDATE_DECISIONS.get(),
                    MOUNTAIN_NEIGHBOR_HITS.get(),
                    MOUNTAIN_NEIGHBOR_MISSES.get(),
                    MOUNTAIN_KEPT.get(),
                    MOUNTAIN_REJECTED.get(),
                    FALLBACK_FOUND.get(),
                    FALLBACK_FAILED.get()
            );
        }
    }

    private static Selection selectBiome(
            RegionKey normalKey,
            RegionKey mountainKey,
            int y,
            BiomeSupplier supplier,
            MultiNoiseUtil.MultiNoiseSampler sampler,
            Map<RegionSampleKey, RegistryEntry<Biome>> regionCache
    ) {
        RegistryEntry<Biome> normalCenterBiome = getRegionBiome(
                normalKey, y, supplier, sampler, regionCache
        );
        RegistryEntry<Biome> normalBiome = normalCenterBiome;

        /*
         * A protected biome at a grid centre must not disable scaling for the
         * whole surrounding land region. Keep protected raw cells untouched,
         * but use the nearest large non-mountain land region as the baseline.
         */
        if (isProtected(normalCenterBiome)) {
            RegistryEntry<Biome> fallback = findNearestNonMountainBiome(
                    normalKey, y, supplier, sampler, regionCache
            );
            if (fallback == null) {
                FALLBACK_FAILED.incrementAndGet();
                return Selection.keepRaw(
                        "protected-centre-no-land-fallback",
                        normalCenterBiome,
                        null,
                        null,
                        -1.0D
                );
            }
            FALLBACK_FOUND.incrementAndGet();
            normalBiome = fallback;
        }

        RegistryEntry<Biome> mountainBiome = getRegionBiome(mountainKey, y, supplier, sampler, regionCache);
        double mountainGate = hashToUnit(
                mountainKey.cellX(),
                mountainKey.cellZ(),
                MOUNTAIN_GATE_SALT
        );

        /*
         * Exact centre samples can miss a mountain range entirely: a 24,000
         * block mountain cell may have its centre over ocean or ordinary land.
         * Only when the deterministic gate allows mountains do we inspect the
         * surrounding 3x3 normal-grid centres. That makes the large mountain
         * cell inherit a nearby real mountain biome without querying a large
         * neighbourhood for cells that will be rejected anyway.
         */
        if (!isMountainBiome(mountainBiome) && mountainGate <= MOUNTAIN_KEEP_CHANCE) {
            RegistryEntry<Biome> nearbyMountain = findMountainBiomeNearRegion(
                    mountainKey, y, supplier, sampler, regionCache
            );
            if (nearbyMountain != null) {
                mountainBiome = nearbyMountain;
                MOUNTAIN_NEIGHBOR_HITS.incrementAndGet();
            } else {
                MOUNTAIN_NEIGHBOR_MISSES.incrementAndGet();
            }
        }

        /*
         * The mountain grid remains independent from the normal grid. If its
         * centre or its nearby normal-grid samples contain a mountain biome,
         * this large cell may be retained according to the stable region gate.
         */
        if (isMountainBiome(mountainBiome)) {
            MOUNTAIN_CANDIDATE_DECISIONS.incrementAndGet();

            if (mountainGate <= MOUNTAIN_KEEP_CHANCE) {
                MOUNTAIN_KEPT.incrementAndGet();
                return Selection.use(
                        mountainBiome,
                        "mountain-region-kept",
                        normalCenterBiome,
                        normalBiome,
                        mountainBiome,
                        mountainGate
                );
            }

            MOUNTAIN_REJECTED.incrementAndGet();
        }

        /*
         * If the normal-grid centre happens to be a mountain but its larger
         * mountain-grid region was not kept, merge it into the nearest normal
         * non-mountain region instead of restoring small original mountain
         * patches.
         */
        if (isMountainBiome(normalBiome)) {
            RegistryEntry<Biome> fallback = findNearestNonMountainBiome(
                    normalKey, y, supplier, sampler, regionCache
            );

            if (fallback != null) {
                FALLBACK_FOUND.incrementAndGet();
                return Selection.use(
                        fallback,
                        "normal-mountain-demoted-to-neighbour",
                        normalCenterBiome,
                        normalBiome,
                        mountainBiome,
                        mountainGate
                );
            }

            FALLBACK_FAILED.incrementAndGet();
            return Selection.keepRaw(
                    "fallback-missing-raw-preserved",
                    normalCenterBiome,
                    normalBiome,
                    mountainBiome,
                    mountainGate
            );
        }

        return Selection.use(
                normalBiome,
                isMountainBiome(mountainBiome) ? "mountain-candidate-rejected-normal-region" : "normal-region",
                normalCenterBiome,
                normalBiome,
                mountainBiome,
                mountainGate
        );
    }

    private static RegistryEntry<Biome> getRegionBiome(
            RegionKey key,
            int y,
            BiomeSupplier supplier,
            MultiNoiseUtil.MultiNoiseSampler sampler,
            Map<RegionSampleKey, RegistryEntry<Biome>> regionCache
    ) {
        RegionSampleKey cacheKey = new RegionSampleKey(
                key.cellX(),
                key.cellZ(),
                y,
                key.regionSize(),
                key.jitterSalt()
        );

        RegistryEntry<Biome> biome = regionCache.get(cacheKey);
        if (biome == null) {
            biome = supplier.getBiome(key.sampleX(), y, key.sampleZ(), sampler);
            regionCache.put(cacheKey, biome);
        }

        return biome;
    }

    private static RegistryEntry<Biome> findMountainBiomeNearRegion(
            RegionKey mountainKey,
            int y,
            BiomeSupplier supplier,
            MultiNoiseUtil.MultiNoiseSampler sampler,
            Map<RegionSampleKey, RegistryEntry<Biome>> regionCache
    ) {
        RegionKey normalAnchor = findNearestRegion(
                mountainKey.sampleX(),
                mountainKey.sampleZ(),
                NORMAL_REGION_SIZE,
                NORMAL_JITTER,
                NORMAL_JITTER_SALT
        );

        /*
         * Search the nearest normal-grid centre, then its eight neighbours.
         * The candidate is selected deterministically by distance to the
         * mountain-grid centre, so all quart cells in a mountain region get
         * the same mountain seed for a given Y level.
         */
        RegistryEntry<Biome> bestBiome = null;
        long bestDistance = Long.MAX_VALUE;

        for (int dx = -1; dx <= 1; ++dx) {
            for (int dz = -1; dz <= 1; ++dz) {
                RegionKey candidate = regionKey(
                        normalAnchor.cellX() + dx,
                        normalAnchor.cellZ() + dz,
                        NORMAL_REGION_SIZE,
                        NORMAL_JITTER,
                        NORMAL_JITTER_SALT
                );

                RegistryEntry<Biome> candidateBiome = getRegionBiome(
                        candidate, y, supplier, sampler, regionCache
                );

                if (!isMountainBiome(candidateBiome)) {
                    continue;
                }

                long distance = squaredDistance(
                        mountainKey.sampleX(),
                        mountainKey.sampleZ(),
                        candidate.sampleX(),
                        candidate.sampleZ()
                );

                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestBiome = candidateBiome;
                }
            }
        }

        return bestBiome;
    }

    private static RegistryEntry<Biome> findNearestNonMountainBiome(
            RegionKey origin,
            int y,
            BiomeSupplier supplier,
            MultiNoiseUtil.MultiNoiseSampler sampler,
            Map<RegionSampleKey, RegistryEntry<Biome>> regionCache
    ) {
        RegistryEntry<Biome> bestBiome = null;
        long bestDistance = Long.MAX_VALUE;

        /*
         * Search outward one ring at a time. Once a ring contains a usable
         * biome, farther rings cannot produce a geographically nearer cell.
         * This avoids dozens of unnecessary biome-source calls in normal use.
         */
        for (int radius = 1; radius <= 2; ++radius) {
            bestBiome = null;
            bestDistance = Long.MAX_VALUE;

            for (int dx = -radius; dx <= radius; ++dx) {
                for (int dz = -radius; dz <= radius; ++dz) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
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
                            candidate, y, supplier, sampler, regionCache
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
                        bestBiome = candidateBiome;
                    }
                }
            }

            if (bestBiome != null) {
                return bestBiome;
            }
        }

        return null;
    }

    private static void logDecision(
            long populateCall,
            int x,
            int y,
            int z,
            RegistryEntry<Biome> raw,
            RegionKey normalKey,
            RegionKey mountainKey,
            Selection selection
    ) {
        if (DETAILED_LOGS.get() >= MAX_DETAILED_PROBES) {
            return;
        }
        int logNumber = DETAILED_LOGS.getAndIncrement();
        if (logNumber >= MAX_DETAILED_PROBES) {
            return;
        }

        String finalBiome = selection.keepRaw() ? biomeId(raw) : biomeId(selection.biome());
        String gate = selection.mountainGate() < 0.0D
                ? "n/a"
                : String.format(Locale.ROOT, "%.3f", selection.mountainGate());

        FixMod.LOGGER.info(
                "[Fix][BiomeProbe] probe={}, call={}, world_pos=({}, {}, {}), raw={}, " +
                        "normal_center=({}, {}), normal_center_biome={}, normal_candidate={}, " +
                        "mountain_center=({}, {}), mountain_candidate={}, mountain_roll={}/{}, " +
                        "decision={}, final={}.",
                logNumber + 1,
                populateCall,
                x * 4,
                y * 4,
                z * 4,
                biomeId(raw),
                normalKey.sampleX() * 4,
                normalKey.sampleZ() * 4,
                biomeId(selection.normalCenterBiome()),
                biomeId(selection.normalBiome()),
                mountainKey.sampleX() * 4,
                mountainKey.sampleZ() * 4,
                biomeId(selection.mountainBiome()),
                gate,
                MOUNTAIN_KEEP_CHANCE,
                selection.decision(),
                finalBiome
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

                long distance = squaredDistance(x, z, candidate.sampleX(), candidate.sampleZ());
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

        return new RegionKey(
                cellX, cellZ, sampleX, sampleZ, regionSize, jitterSalt
        );
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
                || path.contains("jagged")
                || path.contains("windswept")
                || path.contains("slope")
                || path.contains("meadow")
                || path.equals("grove");
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

        /*
         * Protect vanilla and commonly named modded Nether/End biome paths.
         * The populateBiomes hook does not expose the dimension directly, so
         * path-based checks are used to avoid flattening those biome palettes.
         */
        return path.equals("nether_wastes")
                || path.startsWith("nether_")
                || path.equals("soul_sand_valley")
                || path.equals("crimson_forest")
                || path.equals("warped_forest")
                || path.equals("basalt_deltas")
                || path.contains("crimson")
                || path.contains("warped")
                || path.contains("soul_sand")
                || path.contains("basalt_delta")
                || path.equals("the_end")
                || path.equals("end")
                || path.startsWith("end_")
                || path.contains("outer_end")
                || path.contains("chorus_grove")
                || path.equals("small_end_islands")
                || path.contains("end_barrens")
                || path.equals("the_void");
    }

    private static String biomeId(RegistryEntry<Biome> biome) {
        if (biome == null) {
            return "<none>";
        }
        return biome.getKey()
                .map(key -> key.getValue().toString())
                .orElse("<unknown>");
    }

    private static String biomePath(RegistryEntry<Biome> biome) {
        if (biome == null) {
            return "";
        }
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

    private static long regionWidthMin(int size, int jitter) {
        return (long) (size - 2 * jitter) * 4L;
    }

    private static long regionWidthMax(int size, int jitter) {
        return (long) (size + 2 * jitter) * 4L;
    }

    private record RegionKey(
            int cellX,
            int cellZ,
            int sampleX,
            int sampleZ,
            int regionSize,
            long jitterSalt
    ) {
    }

    private record RegionSampleKey(
            int cellX,
            int cellZ,
            int y,
            int regionSize,
            long jitterSalt
    ) {
    }

    private record DecisionKey(RegionKey normalKey, RegionKey mountainKey, int y) {
    }

    private record Selection(
            RegistryEntry<Biome> biome,
            boolean keepRaw,
            String decision,
            RegistryEntry<Biome> normalCenterBiome,
            RegistryEntry<Biome> normalBiome,
            RegistryEntry<Biome> mountainBiome,
            double mountainGate
    ) {
        private static Selection use(
                RegistryEntry<Biome> biome,
                String decision,
                RegistryEntry<Biome> normalCenterBiome,
                RegistryEntry<Biome> normalBiome,
                RegistryEntry<Biome> mountainBiome,
                double mountainGate
        ) {
            return new Selection(
                    biome, false, decision, normalCenterBiome, normalBiome, mountainBiome, mountainGate
            );
        }

        private static Selection keepRaw(
                String decision,
                RegistryEntry<Biome> normalCenterBiome,
                RegistryEntry<Biome> normalBiome,
                RegistryEntry<Biome> mountainBiome,
                double mountainGate
        ) {
            return new Selection(
                    normalBiome, true, decision, normalCenterBiome, normalBiome, mountainBiome, mountainGate
            );
        }
    }
}
