package com.mortylovelly.fix.biome;

import com.mortylovelly.fix.FixMod;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.BiomeTags;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeSupplier;
import net.minecraft.world.biome.source.util.MultiNoiseUtil;
import net.minecraft.world.chunk.PalettedContainer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Assigns large, deterministic Overworld biome regions using the existing
 * biome source. There is one grid for all eligible land biomes; mountains do
 * not have a separate grid, selection chance, stage, or terrain scaler.
 *
 * Coordinates passed to BiomeSupplier are biome-source (quart) coordinates:
 * one horizontal coordinate step corresponds to four blocks.
 */
public final class BiomeDistributionFix {
    private static final boolean ENABLED = true;

    // 6,000 quart = 24,000 blocks nominal spacing. With +/-1,000 quart
    // centre jitter, approximate Voronoi region widths are 16,000-32,000 blocks.
    private static final int REGION_SIZE_QUART = 6_000;
    private static final int REGION_JITTER_QUART = 1_000;
    private static final int REGION_SAMPLE_OFFSET_QUART = 192;
    private static final int REGION_REFERENCE_Y_QUART = 40; // Y=160 blocks.

    private static final long REGION_JITTER_SALT = 0x18D4A1C73B6E5F21L;

    private static final AtomicBoolean FIRST_HOOK_LOGGED = new AtomicBoolean();
    private static final AtomicLong POPULATE_CALLS = new AtomicLong();
    private static final AtomicLong CELLS_SAMPLED = new AtomicLong();
    private static final AtomicLong PROTECTED_CELLS = new AtomicLong();
    private static final AtomicLong CELLS_CHANGED = new AtomicLong();
    private static final AtomicLong REGION_SAMPLES = new AtomicLong();
    private static final AtomicLong PROTECTED_ONLY_SECTIONS = new AtomicLong();

    private BiomeDistributionFix() {
    }

    public static void logSettings() {
        FixMod.LOGGER.info(
                "[Fix][BiomeDiag] algorithm=v7, enabled={}, grid=single, nominal_region_size={} blocks, " +
                        "estimated_region_width={}..{} blocks, jitter={} blocks, reference_height={} blocks, " +
                        "mountain_height_override=disabled.",
                ENABLED,
                REGION_SIZE_QUART * 4L,
                (REGION_SIZE_QUART - 2L * REGION_JITTER_QUART) * 4L,
                (REGION_SIZE_QUART + 2L * REGION_JITTER_QUART) * 4L,
                REGION_JITTER_QUART * 4L,
                REGION_REFERENCE_Y_QUART * 4L
        );
        FixMod.LOGGER.info(
                "[Fix][BiomeDiag] Chunk generation and /locate biome share the same deterministic region lookup."
        );
    }

    /**
     * Runs after Minecraft/Noisium has populated the section's biome palette.
     * The region choice is cached once per horizontal region touched by this
     * section and reused at all four vertical biome layers.
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
                    "[Fix][BiomeHook] ACTIVE: ChunkSection.populateBiomes post-populate callback executed."
            );
        }

        boolean allProtected = true;
        for (int cellY = 0; cellY < 4 && allProtected; ++cellY) {
            for (int cellZ = 0; cellZ < 4 && allProtected; ++cellZ) {
                for (int cellX = 0; cellX < 4; ++cellX) {
                    if (!isProtected(biomeContainer.get(cellX, cellY, cellZ))) {
                        allProtected = false;
                        break;
                    }
                }
            }
        }

        if (allProtected) {
            CELLS_SAMPLED.addAndGet(64L);
            PROTECTED_CELLS.addAndGet(64L);
            PROTECTED_ONLY_SECTIONS.incrementAndGet();
            logStatistics(populateCall);
            return;
        }

        RegionKey[][] regionKeys = new RegionKey[4][4];
        Map<RegionKey, RegistryEntry<Biome>> regionBiomeCache = new HashMap<>(4);

        for (int cellZ = 0; cellZ < 4; ++cellZ) {
            for (int cellX = 0; cellX < 4; ++cellX) {
                regionKeys[cellZ][cellX] = findNearestRegion(x + cellX, z + cellZ);
            }
        }

        long sampled = 0L;
        long protectedCells = 0L;
        long changed = 0L;

        for (int cellY = 0; cellY < 4; ++cellY) {
            for (int cellZ = 0; cellZ < 4; ++cellZ) {
                for (int cellX = 0; cellX < 4; ++cellX) {
                    RegistryEntry<Biome> raw = biomeContainer.get(cellX, cellY, cellZ);
                    sampled++;

                    // Oceans, rivers, caves, Nether/End and other protected
                    // cells retain the exact biome supplied by the generator.
                    if (isProtected(raw)) {
                        protectedCells++;
                        continue;
                    }

                    RegionKey key = regionKeys[cellZ][cellX];
                    RegistryEntry<Biome> regionBiome = regionBiomeCache.computeIfAbsent(
                            key,
                            regionKey -> sampleRegionBiome(regionKey, supplier, sampler)
                    );

                    // A mostly water/cave region anchor must not turn nearby
                    // land into water/cave. Keep the raw cell in that case.
                    if (isProtected(regionBiome)) {
                        continue;
                    }

                    if (!raw.equals(regionBiome)) {
                        biomeContainer.swapUnsafe(cellX, cellY, cellZ, regionBiome);
                        changed++;
                    }
                }
            }
        }

        CELLS_SAMPLED.addAndGet(sampled);
        PROTECTED_CELLS.addAndGet(protectedCells);
        CELLS_CHANGED.addAndGet(changed);
        REGION_SAMPLES.addAndGet(regionBiomeCache.size());
        logStatistics(populateCall);
    }

    /**
     * Pure deterministic lookup shared with BiomeSource.locateBiome.
     * The supplier must be the original source (source::getBiome), so this
     * method never calls itself recursively through the locate mixin.
     */
    public static RegistryEntry<Biome> getDistributedBiomeAtQuart(
            int quartX,
            int quartY,
            int quartZ,
            BiomeSupplier supplier,
            MultiNoiseUtil.MultiNoiseSampler sampler
    ) {
        RegistryEntry<Biome> raw = supplier.getBiome(quartX, quartY, quartZ, sampler);
        if (isProtected(raw)) {
            return raw;
        }

        RegistryEntry<Biome> regionBiome = sampleRegionBiome(
                findNearestRegion(quartX, quartZ),
                supplier,
                sampler
        );
        return isProtected(regionBiome) ? raw : regionBiome;
    }

    private static RegistryEntry<Biome> sampleRegionBiome(
            RegionKey key,
            BiomeSupplier supplier,
            MultiNoiseUtil.MultiNoiseSampler sampler
    ) {
        int x = key.sampleX();
        int z = key.sampleZ();
        int y = REGION_REFERENCE_Y_QUART;
        int offset = REGION_SAMPLE_OFFSET_QUART;

        List<RegistryEntry<Biome>> samples = List.of(
                supplier.getBiome(x, y, z, sampler),
                supplier.getBiome(x + offset, y, z, sampler),
                supplier.getBiome(x - offset, y, z, sampler),
                supplier.getBiome(x, y, z + offset, sampler),
                supplier.getBiome(x, y, z - offset, sampler)
        );

        // Deterministic majority vote. Ties keep the centre sample, so hash
        // iteration order cannot change the biome map between runs.
        RegistryEntry<Biome> best = samples.get(0);
        int bestVotes = 0;
        for (RegistryEntry<Biome> candidate : samples) {
            int votes = 0;
            for (RegistryEntry<Biome> sample : samples) {
                if (candidate.equals(sample)) {
                    votes++;
                }
            }
            if (votes > bestVotes) {
                bestVotes = votes;
                best = candidate;
            }
        }

        return bestVotes >= 3 ? best : samples.get(0);
    }

    private static RegionKey findNearestRegion(int quartX, int quartZ) {
        int cellX = Math.floorDiv(quartX, REGION_SIZE_QUART);
        int cellZ = Math.floorDiv(quartZ, REGION_SIZE_QUART);

        RegionKey best = null;
        long bestDistance = Long.MAX_VALUE;

        for (int offsetX = -1; offsetX <= 1; ++offsetX) {
            for (int offsetZ = -1; offsetZ <= 1; ++offsetZ) {
                RegionKey candidate = regionKey(cellX + offsetX, cellZ + offsetZ);
                long distance = squaredDistance(
                        quartX, quartZ, candidate.sampleX(), candidate.sampleZ()
                );
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = candidate;
                }
            }
        }
        return best;
    }

    private static RegionKey regionKey(int cellX, int cellZ) {
        int sampleX = cellX * REGION_SIZE_QUART
                + REGION_SIZE_QUART / 2
                + jitter(cellX, cellZ, 0);
        int sampleZ = cellZ * REGION_SIZE_QUART
                + REGION_SIZE_QUART / 2
                + jitter(cellX, cellZ, 1);
        return new RegionKey(cellX, cellZ, sampleX, sampleZ);
    }

    public static boolean isProtected(RegistryEntry<Biome> biome) {
        if (biome == null) {
            return true;
        }

        if (!biome.isIn(BiomeTags.IS_OVERWORLD)
                || biome.isIn(BiomeTags.IS_NETHER)
                || biome.isIn(BiomeTags.IS_END)) {
            return true;
        }

        String path = biome.getKey()
                .map(key -> key.getValue().getPath())
                .orElse("");
        if (path.isEmpty()) {
            return true;
        }

        return path.contains("river")
                || path.contains("ocean")
                || path.contains("cave")
                || path.contains("deep_dark")
                || path.equals("nether_wastes")
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

    private static void logStatistics(long populateCall) {
        if (populateCall == 1L || populateCall % 65_536L == 0L) {
            FixMod.LOGGER.info(
                    "[Fix][BiomeStats] populate_calls={}, cells_sampled={}, protected_cells={}, " +
                            "protected_only_sections_skipped={}, region_samples={}, cells_changed={}.",
                    POPULATE_CALLS.get(),
                    CELLS_SAMPLED.get(),
                    PROTECTED_CELLS.get(),
                    PROTECTED_ONLY_SECTIONS.get(),
                    REGION_SAMPLES.get(),
                    CELLS_CHANGED.get()
            );
        }
    }

    private static long squaredDistance(int x1, int z1, int x2, int z2) {
        long dx = (long) x1 - x2;
        long dz = (long) z1 - z2;
        return dx * dx + dz * dz;
    }

    private static int jitter(int cellX, int cellZ, int axis) {
        long value = REGION_JITTER_SALT
                ^ ((long) cellX * 0x9E3779B97F4A7C15L)
                ^ ((long) cellZ * 0xC2B2AE3D27D4EB4FL)
                ^ ((long) axis * 0x165667B19E3779F9L);
        value = mix(value);
        return Math.floorMod(value, REGION_JITTER_QUART * 2 + 1) - REGION_JITTER_QUART;
    }

    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    private record RegionKey(int cellX, int cellZ, int sampleX, int sampleZ) {
    }
}
