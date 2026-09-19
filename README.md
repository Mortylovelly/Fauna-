# Fix - Modpack Compatibility Fixes

A targeted compatibility and stability mod for the Mortylovelly 1.20.1 Fabric modpack.

## Purpose

Fix is not intended to replace major optimization mods or world-generation mods. It exists to apply small, targeted fixes when two installed mods disagree, a broken data file creates startup errors, or a known incompatibility needs a stable compatibility layer.

The project is intentionally modular so additional fixes can be added without changing already-working mechanics.

## Current targets

### Naturalist Delight / Naturalist 5.x

Naturalist 5.x removed the old `naturalist:cattail` item while older Naturalist Delight data can still reference it. Fix removes only the broken `naturalistdelight:piece_of_cattail` recipe before Minecraft tries to parse it.

### YUNG's Cave Biomes / Cave Biomes Delight

Fix provides a conditional replacement for the broken Sand Snapper loot table when both mods are present. The replacement intentionally avoids the missing `cavebiomesdelight:sand_snapper` item so the world can load without a loot-table parse error.

Fix also provides a valid Ice Cube loot table that removes the invalid tool-context function reported by the current pack.

## Design rules

- Minecraft 1.20.1 only for this branch.
- Java 17.
- No duplicate performance systems.
- No world-generation rewriting unless a specific compatibility issue requires it.
- Fixes are enabled automatically when their target resources/mods are present.
- Known fixes are isolated so they can be removed or replaced independently.

## Build

Use:

```text
gradle build --no-daemon
```

The GitHub Actions workflow uses Gradle 8.12 and Java 17.
