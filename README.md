# SBPCLoot

SBPCLoot is a Spigot add-on for the SBPC progression plugin. It sprinkles custom loot items into survival gameplay that let players fast-forward sections of the SBPC challenge. Ancient Tablets skip a single entry when you are standing on it, and Ancient Scrolls finish an entire section at once. The plugin automatically pulls SBPC's progression order so the drops line up with your server's configuration.

## Requirements
- Spigot/Paper 1.21+ server
- [SBPC](https://github.com/) installed and enabled (SBPCLoot will disable itself if SBPC is missing)
- Java 17+ for building and running

## Unique features
- **Progression-aware loot**: Tablets and scrolls are tagged with the exact SBPC entry or section they affect, using SBPC's configured names and order.
- **Multiple loot sources**: Items appear organically in world loot—structure chests, archaeology sites, fishing, hostile mobs, and the Ender Dragon drop pool.
- **Reroll crafting**: Combine three tablets or three scrolls in a shapeless recipe to reroll the target entry/section without commands or admin help.
- **Safety checks**: Players cannot consume a tablet or scroll unless they are on the matching entry/section and have not already surpassed it, preventing accidental waste.

## How to get the items
- **Structure chests**
  - Tablets: 33% chance, dropping 1–2 from shipwreck treasure/map/supply, buried treasure, blacksmith, ruined portal, simple dungeon, mineshaft, and nether fortress chests.
  - Scrolls: 10% chance from bastion (all variants), ancient city (including ice box), and end city treasure.
- **Archaeology**: 10% chance for a tablet and 1% for a scroll from desert pyramid/well, ocean ruin (warm/cold), and trail ruins (common/rare) loot tables.
- **Fishing**: 2% base chance to reel in a tablet, plus +1% per Luck of the Sea level.
- **Mob drops**: 0.2% chance for common mobs (Skeleton, Creeper, Spider, Zombie, Enderman) to drop a tablet.
- **Boss reward**: The Ender Dragon always drops an Ancient Scroll.

## Using Ancient Tablets
1. Hold the tablet in your main hand and right-click.
2. The tablet only works when you are currently on the SBPC entry printed in its lore and still inside the matching section.
3. On success, the tablet crumbles and the entry is auto-completed via a large time skip.

**Example:** If you are stuck on `Iron Pickaxe` in the `Overworld Basics` section, right-click an Ancient Tablet whose lore lists that entry while you are still on it. The tablet will instantly mark the entry complete.

## Using Ancient Scrolls
1. Hold the scroll in your main hand and right-click.
2. The scroll works only when you are progressing inside the target section and have not moved past it.
3. On success, the entire section is completed and the scroll is consumed.

**Example:** Right-click an Ancient Scroll for `Nether Exploration` while you are working through that section to finish the entire section immediately.

## Rerolling loot targets
- Crafting grid recipe (shapeless): place any three Ancient Tablets to craft a rerolled tablet that points to a new random entry. Do the same with three Ancient Scrolls to reroll the target section.
- Only authentic tablets/scrolls count toward the recipe; paper or books without SBPCLoot metadata are rejected.

## Configuration
`src/config.yml` controls item names, lore, and player-facing messages. After editing the file, restart the server or reload the plugin to pick up changes.

Key options include:
- `items.tablet` and `items.scroll` name/lore templates (supports `{section}` and `{entry}` placeholders)
- `items.reroll` names used in crafting outputs
- `messages.*` strings for feedback when using tablets or scrolls

## Building
The project uses Maven.

```bash
mvn clean package
```

The resulting plugin JAR will be in `target/`. Remember to install SBPC into your local Maven repository or configure a repository that provides it so the build can resolve the dependency.
