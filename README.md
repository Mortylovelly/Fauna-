# Fix — Modpack Compatibility & Balance

Targeted compatibility, fauna-balancing, stability and biome-distribution fixes for the Mortylovelly Minecraft 1.20.1 Fabric modpack.

## Current version

- Minecraft: 1.20.1
- Fabric Loader: 0.19.5
- Fabric API: 0.92.12+1.20.1
- Java bytecode target: 17
- Gradle/Loom runtime: Java 21
- Fix version: 0.4.9

## Biome distribution

Fix keeps existing biome IDs and redistributes eligible Overworld land biomes into large deterministic regions.

- A single jittered Voronoi grid is used for all eligible land biomes. Nominal spacing is 24,000 blocks; estimated region widths are approximately 16,000–32,000 blocks.
- The source biome is sampled around each region anchor with a deterministic five-point majority vote at Y=160.
- Region selections are stable across vertical layers of a region.
- Original river, ocean, cave and deep-dark biomes remain protected. Nether, End and other biomes not tagged `#minecraft:is_overworld` are left unchanged.
- There is no separate mountain grid, mountain-cluster selection chance, mountain-stage system, or forced mountain-biome substitution.
- Biome distribution does not add or rename biome registry entries.

The same deterministic region lookup is used while filling chunk-section biome palettes and by the vanilla `/locate biome` search path. The locate mixin covers the BiomeSource overloads used by vanilla; redirects are optional so an unused overload does not prevent the game from starting.

## Mountain terrain

Fix no longer raises mountain columns or writes artificial stone columns.

- Mountain heights and landforms come from the world's normal terrain generator, including Tectonic when installed.
- There are no synthetic `small`, `medium`, or `large` mountain stages.
- There is no 500/1200/2000-block peak target and no separate mountain terrain height field.
- The world height configured by SkyLimitless is not changed.

Mountain biomes remain ordinary registered biomes chosen by the underlying biome source and the shared large-region distribution. A mountain region can contain natural ridges, valleys, foothills and lower slopes; it is not forced into one narrow peak or a vertical stone wall.

## Runtime diagnostics

On startup:
- `[Fix][BiomeDiag]` reports the grid dimensions, reference height and protected-biome policy.
- `[Fix][WorldgenDiag]` reports whether Tectonic and Distant Horizons are loaded.

During generation:
- `[Fix][BiomeHook] ACTIVE` confirms the chunk biome palette hook is running.
- `[Fix][BiomeStats]` reports sampled cells, protected cells, region samples and changed cells periodically rather than logging every section.

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
