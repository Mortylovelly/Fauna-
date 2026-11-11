# Fix - Modpack Compatibility & Balance

Targeted compatibility, fauna-balancing, stability and biome-distribution fixes for the Mortylovelly Minecraft 1.20.1 Fabric modpack.

## Current version

- Minecraft: 1.20.1
- Fabric Loader: 0.19.5
- Fabric API: 0.92.12+1.20.1
- Java bytecode target: 17
- Gradle/Loom runtime: Java 21
- Fix version: 0.4.6

## Biome distribution

Fix keeps existing registered biome IDs and adjusts how the biome source is represented across larger regions.

- Normal macro-regions use a nominal spacing of 50,000 blocks (jittered region widths are approximately 30,000–70,000 blocks).
- Mountain macro-regions use a nominal spacing of 30,000 blocks (jittered region widths are approximately 22,000–38,000 blocks); the existing 2×2 mountain cluster and stage assignment remain aligned.
- One land-biome decision is reused across the vertical layers of a horizontal macro-region. The original biome source is sampled at a fixed reference height of Y=160.
- Redistribution is restricted to biomes tagged `#minecraft:is_overworld`; Nether, End and other untagged-dimension biomes are left untouched. River, ocean, cave and deep-dark biomes remain protected.
- The source biome is sampled farther from each region centre, and the raw-biome boundary strip is narrowed to reduce small vanilla/modded biome fragments.
- Entirely protected sections and protected-only vertical layers are skipped before expensive region calculations.
- Horizontal region borders are calculated once per section rather than repeatedly for every vertical layer.
- Neighbour smoothing corrects only strongly isolated, temperature-incompatible cells (at least 7 of 9 votes).
- Mountain regions are selected in deterministic 2x2 clusters, with a small isolated fallback chance.
- Vanilla `#minecraft:is_mountain` and common mountain-style modded biome IDs are recognized. Meadows and groves are treated as foothill/ecotone biomes, not mountain peaks.
- No new biome IDs or registry entries are added.

## Mountain height stages

Each selected 2x2 mountain-region cluster receives one deterministic, world-seed-dependent virtual stage. All four cells in that cluster share one stage and one terrain seed, preventing stage/seed seams between adjacent cells in the same connected range.

| Stage | Peak cap Y |
|---|---:|
| `small` | 500 |
| `medium` | 1200 |
| `large` | 2000 |

The terrain model is a continuous 2D height field. Mountain scaling now runs at the start of `NoiseChunkGenerator.buildSurface`, after base noise has populated the chunk and before the biome's top surface material is applied. This ensures the scaler reads the generated surface heightmap instead of an unpopulated heightmap:
- a large-scale continental mask forms broad mountain belts;
- domain-warped ridged noise creates long ridges, secondary peaks and valleys;
- the same world-coordinate noise field is evaluated across chunk borders and across the four region cells in a 2x2 stage cluster;
- existing mountain ground is raised with a stone core, then Minecraft's later biome surface stage applies the appropriate top material;
- later cave-carver stages can carve into the mountain mass;
- writes are clamped to a safe height below the world's actual ceiling.

This replaces the old proportional column-stretching code. The old algorithm could turn a 90-block hill into a 200-block hill even when the requested stage was 1200 or 2000. The new one computes the target surface from world X/Z and the stage.

**World-height requirement:** reaching the 1200/2000 caps requires the world to provide that much space. Vanilla height is insufficient. SkyLimitless must be configured high enough. Fix reserves eight blocks above generated mountain surfaces so players have room to stand below the dimension ceiling. For example, a world top boundary of Y=2000 allows mountain surface blocks up to Y=1992; raise the world ceiling above the desired peak to use the full stage cap.

## Mountain search command

- `/locate mountain small`
- `/locate mountain medium`
- `/locate mountain large`

The command no longer reports only a jittered region centre. It samples candidate surface points and verifies:
1. the requested stage belongs to the point's selected 2x2 region cluster;
2. the final distributed biome at the actual noise-surface height is a mountain biome;
3. the predicted 2D mountain surface rises above the source terrain.

The result prints X, Y and Z, the final biome ID, original surface Y, predicted surface Y, stage cap, actual world top and safe terrain top. It also prints a ready-to-use `/tp @s X Y Z` command.

Negative X/Z coordinates are valid Minecraft world coordinates.

## Runtime diagnostics

On startup:
- `[Fix][BiomeDiag]` reports region sizes, stable biome reference height and transition settings.
- `[Fix][BiomeProbe]` samples the original biome, both macro-region anchors, selected mountain candidate and final biome. These project logger entries are automatically marked as Custom logs by Minecraft AI Agent.
- Startup logs now also report whether Tectonic is loaded, so world-generation investigations can distinguish the mod's biome grid from terrain-shape changes.
- `[Fix][MountainHeight]` reports the active 2D terrain model, shared 2x2 stage clusters and the three stage caps.

During generation:
- `[Fix][BiomeHook] ACTIVE` confirms the biome palette hook is running.
- `[Fix][BiomeStats]` reports protected-only sections skipped, sampled cells, changed cells and mountain decisions.
- `[Fix][MountainHeight][Chunk]` reports mountain-biome columns, selected-region columns, raised columns, columns per stage, capped columns, original/target surfaces, world top, safe terrain top and elapsed time. The first 12 chunks and then every 256th processed chunk are logged.

## Fauna deduplication policy

Fix removes only selected duplicate natural-spawn entries while keeping entity registrations, spawn eggs, manual summons and existing entities.

Current preferences include:
- Alex's Mobs Continued over Naturalist for bear, catfish, elephant, rhinoceros and rattlesnake.
- Critters and Companions over Faunify for dragonflies, ladybugs, beetles, leaf insects, roly-polies, stick bugs and weevils.
- Naturalist over Hybrid Birds for ducks.
- Friends&Foes over Ecologics for the Mob Vote-style crab.

## Stability and compatibility

- Repairs missing default attributes for selected Faunify entities.
- Removes the known broken Naturalist Delight cattail recipe when it references the removed `naturalist:cattail`.
- Includes targeted YUNG's Cave Biomes / Cave Biomes Delight loot-table compatibility data.
- Does not change Tectonic parameters, SkyLimitless world-height settings, Better Caves configuration, or C2ME/VMP/ServerCore threading.

## Development

Build with:

```text
gradle build --no-daemon
```

GitHub Actions runs Gradle/Loom on Java 21 because Loom 1.17.21 requires that runtime. Java source and bytecode compatibility remain Java 17 (`options.release = 17`).
