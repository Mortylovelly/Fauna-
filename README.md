# Fix - Modpack Compatibility & Balance

A targeted compatibility, fauna-balancing and stability mod for the Mortylovelly Minecraft 1.20.1 Fabric modpack.

## What Fix does

Fix is built specifically for this modpack. It does not replace C2ME, Lithium, ModernFix, ServerCore or the other major optimization mods.

Its main jobs are:

- remove selected duplicate natural animal spawns while keeping the best/most distinctive implementation;
- repair confirmed registration and data errors;
- apply compatibility patches only when their target mods are present;
- keep manual summons, spawn eggs, existing animals and entity registrations intact.

## Fauna deduplication policy

Fix currently prefers:

- Alex's Mobs Continued over Naturalist for bear, catfish, elephant, rhinoceros and rattlesnake.
- Critters and Companions over Faunify for dragonflies, ladybugs, beetles, leaf insects, roly-polies, stick bugs and weevils.
- Naturalist over Hybrid Birds for ducks.
- Friends&Foes over Ecologics for the Mob Vote-style crab.

Only the duplicate entity's biome natural-spawn entries are removed. The entity itself is not deleted.

## Stability fixes

- Repairs missing default attributes for Faunify entities listed in the current pack log.
- Removes the known broken Naturalist Delight cattail recipe when it references the removed `naturalist:cattail`.
- Includes targeted YUNG's Cave Biomes / Cave Biomes Delight loot-table compatibility data.

## Safety rules

Fix deliberately avoids:

- changing Tectonic terrain parameters;
- changing SkyLimitless world height;
- changing Better Caves configuration;
- changing C2ME/VMP/ServerCore thread configuration;
- adding new permanent entity ticking systems;
- killing duplicate entities after they spawn.

New fixes should be tied to a confirmed problem in this exact 1.20.1 modpack.

## Development

Minecraft: 1.20.1  
Fabric Loader: 0.19.5  
Fabric API: 0.92.12+1.20.1  
Java: 17  
Fix version: 0.2.0

Build command:

```text
gradle build --no-daemon
```
