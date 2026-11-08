# Fix - Modpack Compatibility & Balance

Targeted compatibility, fauna-balancing, stability and biome-distribution fixes for the Mortylovelly Minecraft 1.20.1 Fabric modpack.

## Current version

- Minecraft: 1.20.1
- Fabric Loader: 0.19.5
- Fabric API: 0.92.12+1.20.1
- Java target: 17
- Fix version: 0.4.2

## Biome distribution

Fix keeps the existing registered biome IDs and adjusts how the biome source is represented across larger regions.

- Normal macro-regions use a nominal spacing of 14,000 blocks.
- Mountain macro-regions use a nominal spacing of 20,000 blocks.
- Macro-region representatives use five source samples to reduce the chance that one tiny biome fragment determines an entire large region.
- A narrow transition band uses the original biome source to soften hard macro-region borders.
- A 3x3 neighbourhood pass corrects only strongly isolated, temperature-incompatible cells (at least 7 of 9 votes). Related biome variants are not automatically merged.
- Rivers, oceans, cave biomes, the Nether and the End remain protected from the macro-biome rewrite.
- Mountain regions are selected in deterministic 2x2 clusters, with a small isolated fallback chance, to make connected ranges more likely than isolated mountain patches.
- Vanilla `#minecraft:is_mountain` and commonly named modded mountain biome IDs are recognized. No new biome IDs are registered.

## Mountain height stages

Each selected mountain region receives a deterministic, world-seed-dependent **virtual stage**. These stages are not new biome IDs or global biome tags.

| Stage | Target peak Y |
|---|---:|
| `small` | 500 |
| `medium` | 1200 |
| `large` | 2000 |

Terrain scaling is applied after base noise terrain generation. It stretches the existing mountain column profile above sea level and respects the chunk's actual height boundary.

The scaler:
- only runs in an Overworld-like dimension with bottom Y=-64 and a top boundary above Y=320;
- never writes beyond the chunk's actual top boundary;
- caps each stage if the world height is lower than its target;
- logs when height scaling becomes active or when the world is too short.

**Important:** reaching Y=1200/Y=2000 requires a height mod/configuration that provides that much space. SkyLimitless must be configured accordingly. This is a post-noise terrain transformation, not a complete rewrite of Minecraft's density router; cave/carver alignment and structures on extreme slopes still need in-game validation.

### Command

- `/locate mountain small`
- `/locate mountain medium`
- `/locate mountain large`

This custom child of vanilla `/locate` searches for a selected mountain region with the requested virtual stage. Vanilla `/locate biome` remains unchanged because a random per-region stage cannot be represented by a static biome tag.

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

GitHub Actions uses Java 17 and Gradle 9.5.0.
