package com.mortylovelly.fix.biome;

import com.mortylovelly.fix.FixMod;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.BiomeTags;
import net.minecraft.world.biome.source.BiomeSource;
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
     * Mountains: nominal spacing 20,000 blocks; normal regions are 14,000.
     * Jitter gives approximate mountain-region widths of 16,800-23,200 blocks.
     */
    private static final int MOUNTAIN_REGION_SIZE = 5000;
    private static final int MOUNTAIN_JITTER = 400;

    /*
     * Retain correlated 2x2 mountain-cell clusters, with a small isolated
     * fallback chance. Expected selected-cell density remains close to 35%.
     */
    private static final int MOUNTAIN_CLUSTER_SIZE = 2;
    private static final double MOUNTAIN_CLUSTER_KEEP_CHANCE = 0.30D;
    private static final double MOUNTAIN_ISOLATED_KEEP_CHANCE = 0.07D;
    private static final int MOUNTAIN_SEARCH_RADIUS = 1;

    private static final int TRANSITION_HALF_WIDTH_QUART = 350;
    private static final float INCOMPATIBLE_TEMPERATURE_DELTA = 0.75F;
    private static final int MOUNTAIN_LOCATE_SAMPLE_QUART_Y = 32;
    private static final int REGION_SAMPLE_OFFSET = 500;

    private static final long NORMAL_JITTER_SALT = 0x18D4A1C73B6E5F21L;
    private static final long MOUNTAIN_JITTER_SALT = 0x6A09E667F3BCC909L;
    private static final long MOUNTAIN_CLUSTER_SALT = 0x3C6EF372FE94F82BL;
    private static final long MOUNTAIN_CELL_KEEP_SALT = 0xA54FF53A5F1D36F1L;
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
                "[Fix][BiomeDiag] algorithm=v4, enabled={}, coordinate_scale=4 blocks/quart, " +
                        "normal_regions~{}-{} blocks (nominal {}), mountain_regions~{}-{} blocks " +
                        "(nominal {}), mountain_cluster={}x{}, cluster_keep_chance={}, " +
                        "isolated_keep_chance={}, transition_half_width={} blocks, outlier_vote=7/9, " +
                        "detail_probe_limit={}.",
                ENABLED,
                regionWidthMin(NORMAL_REGION_SIZE, NORMAL_JITTER),
                regionWidthMax(NORMAL_REGION_SIZE, NORMAL_JITTER),
                NORMAL_REGION_SIZE * 4L,
                regionWidthMin(MOUNTAIN_REGION_SIZE, MOUNTAIN_JITTER),
                regionWidthMax(MOUNTAIN_REGION_SIZE, MOUNTAIN_JITTER),
                MOUNTAIN_REGION_SIZE * 4L,
                MOUNTAIN_CLUSTER_SIZE,
                MOUNTAIN_CLUSTER_SIZE,
                MOUNTAIN_CLUSTER_KEEP_CHANCE,
                MOUNTAIN_ISOLATED_KEEP_CHANCE,
                TRANSITION_HALF_WIDTH_QUART * 4L,
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

        Map<RegionSampleKey, RegistryEntry<Biome>> regionCache = new HashMap<>(96);
        Map<DecisionKey, Selection> decisionCache = new HashMap<>(48);
        long sampledCells = 0L;
        long protectedCells = 0L;
        long changedCells = 0L;

        RegionKey[][] normalKeys = new RegionKey[6][6];
        RegionKey[][] mountainKeys = new RegionKey[6][6];

        // One-cell halo: smoothing sees adjacent cells even at a section border.
        for (int haloZ = 0; haloZ < 6; ++haloZ) {
            for (int haloX = 0; haloX < 6; ++haloX) {
                int sampleX = x + haloX - 1;
                int sampleZ = z + haloZ - 1;

                normalKeys[haloX][haloZ] = findNearestRegion(
                        sampleX, sampleZ,
                        NORMAL_REGION_SIZE, NORMAL_JITTER, NORMAL_JITTER_SALT
                );
                mountainKeys[haloX][haloZ] = findNearestRegion(
                        sampleX, sampleZ,
                        MOUNTAIN_REGION_SIZE, MOUNTAIN_JITTER, MOUNTAIN_JITTER_SALT
                );
            }
        }

        for (int posY = 0; posY < 4; ++posY) {
            int sampleY = y + posY;

            @SuppressWarnings("unchecked")
            RegistryEntry<Biome>[][] selected = new RegistryEntry[6][6];

            for (int haloZ = 0; haloZ < 6; ++haloZ) {
                for (int haloX = 0; haloX < 6; ++haloX) {
                    int sampleX = x + haloX - 1;
                    int sampleZ = z + haloZ - 1;
                    boolean insidePalette = haloX >= 1 && haloX <= 4 && haloZ >= 1 && haloZ <= 4;

                    RegistryEntry<Biome> raw = insidePalette
                            ? biomeContainer.get(haloX - 1, posY, haloZ - 1)
                            : supplier.getBiome(sampleX, sampleY, sampleZ, sampler);

                    sampledCells++;
                    if (isProtected(raw)) {
                        selected[haloZ][haloX] = raw;
                        protectedCells++;
                        continue;
                    }

                    RegionKey normalKey = normalKeys[haloX][haloZ];
                    RegionKey mountainKey = mountainKeys[haloX][haloZ];
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
                    }

                    RegistryEntry<Biome> finalBiome = selection.keepRaw()
                            ? raw
                            : selection.biome();

                    if (finalBiome == null) {
                        finalBiome = raw;
                    }

                    // Use the original noise biome only in a narrow macro-region
                    // transition band. Mountain regions remain controlled by
                    // their own larger grid.
                    if (!isProtected(finalBiome)
                        && !isMountainBiome(finalBiome)
                        && isNearRegionBoundary(
                                sampleX, sampleZ, normalKey,
                                NORMAL_REGION_SIZE, NORMAL_JITTER, NORMAL_JITTER_SALT,
                                TRANSITION_HALF_WIDTH_QUART
                        )) {
                    RegistryEntry<Biome> transitionBiome =
                            supplier.getBiome(sampleX, sampleY, sampleZ, sampler);
                    if (!isProtected(transitionBiome)
                            && !isMountainBiome(transitionBiome)) {
                        finalBiome = transitionBiome;
                    }
                }

                // The mountain grid has its own border band as well. This
                // prevents rectangular biome cliffs at the edge of retained
                // large mountain regions.
                if (isMountainBiome(finalBiome)
                        && isNearRegionBoundary(
                                sampleX, sampleZ, mountainKey,
                                MOUNTAIN_REGION_SIZE, MOUNTAIN_JITTER, MOUNTAIN_JITTER_SALT,
                                TRANSITION_HALF_WIDTH_QUART
                        )) {
                    RegistryEntry<Biome> transitionBiome =
                            supplier.getBiome(sampleX, sampleY, sampleZ, sampler);
                    if (!isProtected(transitionBiome) && !isMountainBiome(transitionBiome)) {
                        finalBiome = transitionBiome;
                    }
                }

                    selected[haloZ][haloX] = finalBiome;

                    if (haloX == 1 && haloZ == 1) {
                        logDecision(
                                populateCall,
                                sampleX,
                                sampleY,
                                sampleZ,
                                raw,
                                normalKey,
                                mountainKey,
                                selection
                        );
                    }
                }
            }

            // Replace only clear climate mistakes: at least 7/9 neighbours
            // must agree, and the isolated cell must be temperature-incompatible.
            for (int posZ = 0; posZ < 4; ++posZ) {
                for (int posX = 0; posX < 4; ++posX) {
                    RegistryEntry<Biome> raw = biomeContainer.get(posX, posY, posZ);
                    if (isProtected(raw)) {
                        continue;
                    }

                    RegistryEntry<Biome> finalBiome = selected[posZ + 1][posX + 1];
                    if (!isProtected(finalBiome) && !isMountainBiome(finalBiome)) {
                        RegistryEntry<Biome> dominant = findDominantNeighbourBiome(
                                selected,
                                posX + 1,
                                posZ + 1
                        );

                        if (dominant != null
                                && !dominant.equals(finalBiome)
                                && areClimateIncompatible(finalBiome, dominant)) {
                            finalBiome = dominant;
                        }
                    }

                    if (finalBiome != null && !finalBiome.equals(raw)) {
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

        if (isProtected(normalCenterBiome)) {
            RegistryEntry<Biome> fallback = findNearestNonMountainBiome(
                    normalKey, y, supplier, sampler, regionCache, null
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

        RegistryEntry<Biome> mountainBiome = getRegionBiome(
                mountainKey, y, supplier, sampler, regionCache
        );
        boolean keepMountainRegion = shouldKeepMountainRegion(mountainKey);
        double mountainGate = keepMountainRegion ? 0.0D : 1.0D;

        if (!isMountainBiome(mountainBiome) && keepMountainRegion) {
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

        if (isMountainBiome(mountainBiome)) {
            MOUNTAIN_CANDIDATE_DECISIONS.incrementAndGet();

            // Do not transplant a mountain biome into a strongly different
            // climate macro-region (for example, cold peaks into hot desert).
            if (keepMountainRegion
                    && !areClimateIncompatible(mountainBiome, normalBiome)) {
                MOUNTAIN_KEPT.incrementAndGet();
                return Selection.use(
                        mountainBiome,
                        "clustered-mountain-region-kept",
                        normalCenterBiome,
                        normalBiome,
                        mountainBiome,
                        mountainGate
                );
            }

            MOUNTAIN_REJECTED.incrementAndGet();
        }

        if (isMountainBiome(normalBiome)) {
            RegistryEntry<Biome> fallback = findNearestNonMountainBiome(
                    normalKey, y, supplier, sampler, regionCache, normalBiome
            );

            if (fallback != null) {
                FALLBACK_FOUND.incrementAndGet();
                return Selection.use(
                        fallback,
                        "unselected-mountain-demoted-to-compatible-neighbour",
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
                isMountainBiome(mountainBiome)
                        ? "mountain-candidate-in-unselected-cluster"
                        : "normal-region",
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

        RegistryEntry<Biome> cached = regionCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        int offset = REGION_SAMPLE_OFFSET;
        RegistryEntry<Biome> center = supplier.getBiome(
                key.sampleX(), y, key.sampleZ(), sampler
        );

        Map<RegistryEntry<Biome>, Integer> votes = new HashMap<>();
        votes.merge(center, 1, Integer::sum);
        votes.merge(supplier.getBiome(key.sampleX() + offset, y, key.sampleZ(), sampler), 1, Integer::sum);
        votes.merge(supplier.getBiome(key.sampleX() - offset, y, key.sampleZ(), sampler), 1, Integer::sum);
        votes.merge(supplier.getBiome(key.sampleX(), y, key.sampleZ() + offset, sampler), 1, Integer::sum);
        votes.merge(supplier.getBiome(key.sampleX(), y, key.sampleZ() - offset, sampler), 1, Integer::sum);

        RegistryEntry<Biome> majority = center;
        int bestVotes = 0;
        for (Map.Entry<RegistryEntry<Biome>, Integer> vote : votes.entrySet()) {
            if (vote.getValue() > bestVotes) {
                bestVotes = vote.getValue();
                majority = vote.getKey();
            }
        }

        RegistryEntry<Biome> result = bestVotes >= 3 ? majority : center;
        regionCache.put(cacheKey, result);
        return result;
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

        RegistryEntry<Biome> bestBiome = null;
        long bestDistance = Long.MAX_VALUE;
        int mountainSamples = 0;

        for (int dx = -MOUNTAIN_SEARCH_RADIUS; dx <= MOUNTAIN_SEARCH_RADIUS; ++dx) {
            for (int dz = -MOUNTAIN_SEARCH_RADIUS; dz <= MOUNTAIN_SEARCH_RADIUS; ++dz) {
                RegionKey candidate = regionKey(
                        normalAnchor.cellX() + dx,
                        normalAnchor.cellZ() + dz,
                        NORMAL_REGION_SIZE,
                        NORMAL_JITTER,
                        NORMAL_JITTER_SALT
                );

                // One independent centre sample per neighbouring normal
                // macro-cell. Two mountain hits are required, which rejects
                // single stray fragments without the cost of 5 samples x 25 cells.
                RegistryEntry<Biome> candidateBiome = supplier.getBiome(
                        candidate.sampleX(), y, candidate.sampleZ(), sampler
                );
                if (!isMountainBiome(candidateBiome)) {
                    continue;
                }

                mountainSamples++;
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

        return mountainSamples >= 2 ? bestBiome : null;
    }

    private static RegistryEntry<Biome> findNearestNonMountainBiome(
            RegionKey origin,
            int y,
            BiomeSupplier supplier,
            MultiNoiseUtil.MultiNoiseSampler sampler,
            Map<RegionSampleKey, RegistryEntry<Biome>> regionCache,
            RegistryEntry<Biome> climateReference
    ) {
        RegistryEntry<Biome> bestBiome = null;
        long bestDistance = Long.MAX_VALUE;

        for (int radius = 1; radius <= 3; ++radius) {
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
                    if (climateReference != null
                            && areClimateIncompatible(climateReference, candidateBiome)) {
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
        String mountainStatus = selection.mountainGate() < 0.0D
                ? "n/a"
                : (selection.mountainGate() < 0.5D ? "selected" : "not-selected");

        FixMod.LOGGER.info(
                "[Fix][BiomeProbe] probe={}, call={}, world_pos=({}, {}, {}), raw={}, " +
                        "normal_center=({}, {}), normal_center_biome={}, normal_candidate={}, " +
                        "mountain_center=({}, {}), mountain_candidate={}, mountain_region_status={}, " +
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
                mountainStatus,
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

    public static boolean isMountainBiome(RegistryEntry<Biome> biome) {
        if (biome == null) {
            return false;
        }
        if (biome.isIn(BiomeTags.IS_MOUNTAIN)) {
            return true;
        }

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

    private static boolean shouldKeepMountainRegion(RegionKey key) {
        int clusterX = Math.floorDiv(key.cellX(), MOUNTAIN_CLUSTER_SIZE);
        int clusterZ = Math.floorDiv(key.cellZ(), MOUNTAIN_CLUSTER_SIZE);
        double clusterRoll = hashToUnit(clusterX, clusterZ, MOUNTAIN_CLUSTER_SALT);

        if (clusterRoll < MOUNTAIN_CLUSTER_KEEP_CHANCE) {
            return true;
        }
        return hashToUnit(key.cellX(), key.cellZ(), MOUNTAIN_CELL_KEEP_SALT)
                < MOUNTAIN_ISOLATED_KEEP_CHANCE;
    }

    private static boolean isNearRegionBoundary(
            int x,
            int z,
            RegionKey nearest,
            int regionSize,
            int jitter,
            long jitterSalt,
            int transitionHalfWidth
    ) {
        long nearestDistance = squaredDistance(x, z, nearest.sampleX(), nearest.sampleZ());
        long secondDistance = Long.MAX_VALUE;
        int cellX = Math.floorDiv(x, regionSize);
        int cellZ = Math.floorDiv(z, regionSize);

        for (int dx = -1; dx <= 1; ++dx) {
            for (int dz = -1; dz <= 1; ++dz) {
                RegionKey candidate = regionKey(
                        cellX + dx, cellZ + dz,
                        regionSize, jitter, jitterSalt
                );
                if (candidate.cellX() == nearest.cellX()
                        && candidate.cellZ() == nearest.cellZ()) {
                    continue;
                }

                long distance = squaredDistance(
                        x, z, candidate.sampleX(), candidate.sampleZ()
                );
                if (distance < secondDistance) {
                    secondDistance = distance;
                }
            }
        }

        if (secondDistance == Long.MAX_VALUE) {
            return false;
        }

        double gap = Math.sqrt(secondDistance) - Math.sqrt(nearestDistance);
        return gap <= transitionHalfWidth * 2.0D;
    }

    private static RegistryEntry<Biome> findDominantNeighbourBiome(
            RegistryEntry<Biome>[][] selected,
            int centerX,
            int centerZ
    ) {
        Map<RegistryEntry<Biome>, Integer> votes = new HashMap<>();
        for (int dz = -1; dz <= 1; ++dz) {
            for (int dx = -1; dx <= 1; ++dx) {
                RegistryEntry<Biome> candidate = selected[centerZ + dz][centerX + dx];
                if (candidate == null || isProtected(candidate) || isMountainBiome(candidate)) {
                    continue;
                }
                votes.merge(candidate, 1, Integer::sum);
            }
        }

        RegistryEntry<Biome> dominant = null;
        int bestVotes = 0;
        for (Map.Entry<RegistryEntry<Biome>, Integer> vote : votes.entrySet()) {
            if (vote.getValue() > bestVotes) {
                dominant = vote.getKey();
                bestVotes = vote.getValue();
            }
        }
        return bestVotes >= 7 ? dominant : null;
    }

    private static boolean areClimateIncompatible(
            RegistryEntry<Biome> first,
            RegistryEntry<Biome> second
    ) {
        if (first == null || second == null) {
            return false;
        }
        return Math.abs(
                first.value().getTemperature() - second.value().getTemperature()
        ) >= INCOMPATIBLE_TEMPERATURE_DELTA;
    }

    public static MountainRegionCell nearestMountainRegionForQuart(int quartX, int quartZ) {
        RegionKey key = findNearestRegion(
                quartX, quartZ,
                MOUNTAIN_REGION_SIZE, MOUNTAIN_JITTER, MOUNTAIN_JITTER_SALT
        );
        return new MountainRegionCell(
                key.cellX(), key.cellZ(), key.sampleX(), key.sampleZ()
        );
    }

    public static MountainRegionCell mountainRegionCellAt(int cellX, int cellZ) {
        RegionKey key = regionKey(
                cellX, cellZ,
                MOUNTAIN_REGION_SIZE, MOUNTAIN_JITTER, MOUNTAIN_JITTER_SALT
        );
        return new MountainRegionCell(
                key.cellX(), key.cellZ(), key.sampleX(), key.sampleZ()
        );
    }

    /**
     * Fast deterministic gate used by terrain generation. The biome check is
     * performed by the caller; this only rejects cells outside selected
     * clustered mountain regions.
     */
    public static boolean shouldScaleMountainRegion(int cellX, int cellZ) {
        RegionKey key = regionKey(
                cellX, cellZ,
                MOUNTAIN_REGION_SIZE, MOUNTAIN_JITTER, MOUNTAIN_JITTER_SALT
        );
        return shouldKeepMountainRegion(key);
    }

    public static boolean isSelectedMountainRegion(
            int cellX,
            int cellZ,
            BiomeSource biomeSource,
            MultiNoiseUtil.MultiNoiseSampler sampler
    ) {
        RegionKey mountainKey = regionKey(
                cellX, cellZ,
                MOUNTAIN_REGION_SIZE, MOUNTAIN_JITTER, MOUNTAIN_JITTER_SALT
        );
        if (!shouldKeepMountainRegion(mountainKey)) {
            return false;
        }

        BiomeSupplier supplier = biomeSource::getBiome;
        Map<RegionSampleKey, RegistryEntry<Biome>> cache = new HashMap<>(64);
        int sampleY = MOUNTAIN_LOCATE_SAMPLE_QUART_Y; // 128 blocks: representative surface climate layer.

        RegionKey normalKey = findNearestRegion(
                mountainKey.sampleX(),
                mountainKey.sampleZ(),
                NORMAL_REGION_SIZE,
                NORMAL_JITTER,
                NORMAL_JITTER_SALT
        );

        RegistryEntry<Biome> normalBiome = getRegionBiome(
                normalKey, sampleY, supplier, sampler, cache
        );
        if (isProtected(normalBiome)) {
            normalBiome = findNearestNonMountainBiome(
                    normalKey, sampleY, supplier, sampler, cache, null
            );
        }

        RegistryEntry<Biome> mountainBiome = getRegionBiome(
                mountainKey, sampleY, supplier, sampler, cache
        );
        if (!isMountainBiome(mountainBiome)) {
            mountainBiome = findMountainBiomeNearRegion(
                    mountainKey, sampleY, supplier, sampler, cache
            );
        }

        return isMountainBiome(mountainBiome)
                && normalBiome != null
                && !areClimateIncompatible(mountainBiome, normalBiome);
    }

    public record MountainRegionCell(
            int cellX,
            int cellZ,
            int sampleQuartX,
            int sampleQuartZ
    ) {
        public int centerBlockX() {
            return sampleQuartX * 4;
        }

        public int centerBlockZ() {
            return sampleQuartZ * 4;
        }
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
