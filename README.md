# Fix — Modpack Compatibility & Balance

Targeted compatibility, fauna-balancing and stability fixes for the Mortylovelly Minecraft 1.20.1 Fabric modpack.

## Current version

- Minecraft: 1.20.1
- Fabric Loader: 0.19.5
- Fabric API: 0.92.12+1.20.1
- Java bytecode target: 17
- Gradle/Loom runtime: Java 21
- Fix version: 0.4.10

## World generation

Fix does not modify world generation.

- No biome redistribution or biome-palette replacement.
- No changes to the vanilla `/locate biome` search.
- No custom mountain selection, mountain frequency, mountain regions or mountain height overrides.
- No terrain, continent, erosion, noise or world-height modifications.
- Tectonic, Traverse and the world's other generation mods remain responsible for biome placement and terrain.
- The previous biome-distribution experiments and their Mixins have been removed from the active mod. The mod metadata does not register a Mixin configuration.

Existing chunks are not rewritten by this mod. All new chunk generation is left to the active world-generation mods and the selected world preset.

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

## Runtime logging

The mod no longer emits biome-grid, biome-hook, biome-statistics or mountain/world-generation diagnostic messages. Only compatibility, fauna and relevant startup messages remain.

## Development

Build with:

```text
gradle build --no-daemon
```

GitHub Actions runs Gradle/Loom on Java 21 because Loom 1.17.21 requires that runtime. Java source and bytecode compatibility remain Java 17 (`options.release = 17`).
