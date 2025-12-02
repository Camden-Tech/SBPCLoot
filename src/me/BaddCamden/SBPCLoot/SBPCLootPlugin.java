package me.BaddCamden.SBPCLoot;

import me.BaddCamden.SBPC.api.SbpcAPI;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.loot.LootTable;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * SBPCLoot
 * - Ancient Tablet: skip an entry (approx: fast-complete current entry in its section)
 * - Ancient Scroll: auto-complete an entire section
 * - Tablets/Scrolls come from structure chests, archaeology, fishing, mob drops, and dragon kill
 *
 * NOTE: For true "skip specific entry" semantics you would ideally add
 * entry-specific completion methods to SBPC's API. Right now we approximate
 * by using applyExternalTimeSkip on the current entry when in the matching section.
 */
public class SBPCLootPlugin extends JavaPlugin implements Listener {

    // --- Persistent data keys for custom items ---
    private NamespacedKey tabletKey;
    private NamespacedKey tabletEntryKey;
    private NamespacedKey tabletSectionKey;

    private NamespacedKey scrollKey;
    private NamespacedKey scrollSectionKey;

    // Reroll recipes (3 tablets -> 1 rerolled tablet, 3 scrolls -> 1 rerolled scroll)
    private NamespacedKey tabletRerollRecipeKey;
    private NamespacedKey scrollRerollRecipeKey;

    // --- Weighted lists for entries & sections ---
    // --- Config-driven text ---

    // Item names & lore
    private String tabletName;
    private List<String> tabletLore;
    private String scrollName;
    private List<String> scrollLore;
    private String rerollTabletName;
    private String rerollScrollName;

    // Tablet messages
    private String msgTabletDormant;
    private String msgTabletProgressUnknown;
    private String msgTabletUnknownSection;
    private String msgTabletNotReachedSection;
    private String msgTabletBeyondSection;
    private String msgTabletAlreadyCompletedEntry;
    private String msgTabletNoCurrentEntry;
    private String msgTabletNotOnEntry;
    private String msgTabletSuccess;

    // Scroll messages
    private String msgScrollDormant;
    private String msgScrollProgressUnknown;
    private String msgScrollUnknownSection;
    private String msgScrollNotReachedSection;
    private String msgScrollBeyondSection;
    private String msgScrollSuccess;

    // Fishing messages
    private String msgFishingFoundTablet;

    private final Map<String, List<String>> sectionEntriesOrdered = new HashMap<>();

    private static class WeightedEntry {
        final String sectionId;
        final String entryId;
        final double weight;

        WeightedEntry(String sectionId, String entryId, double weight) {
            this.sectionId = sectionId;
            this.entryId = entryId;
            this.weight = weight;
        }
    }

    private static class WeightedSection {
        final String sectionId;
        final double weight;

        WeightedSection(String sectionId, double weight) {
            this.sectionId = sectionId;
            this.weight = weight;
        }
    }

    private final List<WeightedEntry> weightedEntries = new ArrayList<>();
    private double totalEntryWeight = 0.0;

    private final List<WeightedSection> weightedSections = new ArrayList<>();
    private double totalSectionWeight = 0.0;

    // Display name lookup
    private final Map<String, String> entryDisplayNames = new HashMap<>();
    private final Map<String, String> sectionDisplayNames = new HashMap<>();
    private final Map<String, Integer> sectionIndexMap = new HashMap<>();
    private final Map<String, String> entryToSectionMap = new HashMap<>();

    // Loot table keys for structures (namespace:key form)
    private static final Set<String> TABLET_CHEST_TABLES = Set.of(
            "minecraft:chests/shipwreck_treasure",
            "minecraft:chests/shipwreck_map",
            "minecraft:chests/shipwreck_supply",
            "minecraft:chests/buried_treasure",
            "minecraft:chests/village_blacksmith",
            "minecraft:chests/ruined_portal",
            "minecraft:chests/simple_dungeon",
            "minecraft:chests/abandoned_mineshaft",
            "minecraft:chests/nether_bridge"
    );

    private static final Set<String> SCROLL_CHEST_TABLES = Set.of(
            "minecraft:chests/bastion_treasure",
            "minecraft:chests/bastion_bridge",
            "minecraft:chests/bastion_hoglin_stable",
            "minecraft:chests/bastion_other",
            "minecraft:chests/ancient_city",
            "minecraft:chests/ancient_city_ice_box",
            "minecraft:chests/end_city_treasure"
    );

    private static final Set<String> ARCHAEOLOGY_TABLES = Set.of(
            "minecraft:archaeology/desert_pyramid",
            "minecraft:archaeology/desert_well",
            "minecraft:archaeology/ocean_ruin_cold",
            "minecraft:archaeology/ocean_ruin_warm",
            "minecraft:archaeology/trail_ruins_common",
            "minecraft:archaeology/trail_ruins_rare"
    );
    @Override
    public void onEnable() {
        saveDefaultConfig();

        Plugin sbpc = getServer().getPluginManager().getPlugin("SBPC");
        if (sbpc == null || !sbpc.isEnabled()) {
            getLogger().severe("[SBPCLoot] SBPC not found or not enabled. Disabling SBPCLoot.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        tabletKey = new NamespacedKey(this, "ancient_tablet");
        tabletEntryKey = new NamespacedKey(this, "ancient_tablet_entry");
        tabletSectionKey = new NamespacedKey(this, "ancient_tablet_section");

        scrollKey = new NamespacedKey(this, "ancient_scroll");
        scrollSectionKey = new NamespacedKey(this, "ancient_scroll_section");

        tabletRerollRecipeKey = new NamespacedKey(this, "tablet_reroll");
        scrollRerollRecipeKey = new NamespacedKey(this, "scroll_reroll");

        reloadSettings();               // <-- NEW
        loadSbpcProgressionWeights(sbpc);

        getServer().getPluginManager().registerEvents(this, this);
        registerRecipes();

        getLogger().info("[SBPCLoot] Enabled with " + weightedEntries.size() +
                " skippable entries and " + weightedSections.size() + " skippable sections.");
    }

    private String color(String s) {
        if (s == null) return "";
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private List<String> colorList(List<String> list) {
        List<String> out = new ArrayList<>();
        if (list == null) return out;
        for (String s : list) {
            out.add(color(s));
        }
        return out;
    }

    private void reloadSettings() {
        FileConfiguration cfg = getConfig();

        // Items
        tabletName = color(cfg.getString("items.tablet.name", "&6Ancient Tablet"));
        tabletLore = colorList(cfg.getStringList("items.tablet.lore"));

        scrollName = color(cfg.getString("items.scroll.name", "&dAncient Scroll"));
        scrollLore = colorList(cfg.getStringList("items.scroll.lore"));

        rerollTabletName = color(cfg.getString("items.reroll.tablet-name", "&6Ancient Tablet (Rerolled)"));
        rerollScrollName = color(cfg.getString("items.reroll.scroll-name", "&dAncient Scroll (Rerolled)"));

        // Tablet messages
        msgTabletDormant = color(cfg.getString("messages.tablet.dormant",
                "&cThis tablet's magic seems dormant."));
        msgTabletProgressUnknown = color(cfg.getString("messages.tablet.progress-unknown",
                "&cYour progression could not be determined."));
        msgTabletUnknownSection = color(cfg.getString("messages.tablet.unknown-section",
                "&cThis tablet refers to an unknown section."));
        msgTabletNotReachedSection = color(cfg.getString("messages.tablet.not-reached-section",
                "&eYou have not yet reached the section &e{section}&e. The tablet does nothing."));
        msgTabletBeyondSection = color(cfg.getString("messages.tablet.beyond-section",
                "&7You have already progressed beyond &e{section}&7. The tablet has no effect."));
        msgTabletAlreadyCompletedEntry = color(cfg.getString("messages.tablet.already-completed-entry",
                "&7You have already completed &b{entry}&7. The tablet has no effect."));
        msgTabletNoCurrentEntry = color(cfg.getString("messages.tablet.no-current-entry",
                "&cYou cannot use this tablet right now."));
        msgTabletNotOnEntry = color(cfg.getString("messages.tablet.not-on-entry",
                "&eYou are not currently on &b{entry}&e. The tablet's magic remains dormant."));
        msgTabletSuccess = color(cfg.getString("messages.tablet.success",
                "&6The Ancient Tablet crumbles as knowledge of &b{entry}&6 floods your mind!"));

        // Scroll messages
        msgScrollDormant = color(cfg.getString("messages.scroll.dormant",
                "&cThis scroll's magic seems dormant."));
        msgScrollProgressUnknown = color(cfg.getString("messages.scroll.progress-unknown",
                "&cYour progression could not be determined."));
        msgScrollUnknownSection = color(cfg.getString("messages.scroll.unknown-section",
                "&cThis scroll refers to an unknown section."));
        msgScrollNotReachedSection = color(cfg.getString("messages.scroll.not-reached-section",
                "&eYou have not yet reached the section &e{section}&e."));
        msgScrollBeyondSection = color(cfg.getString("messages.scroll.beyond-section",
                "&7You have already surpassed the section &e{section}&7."));
        msgScrollSuccess = color(cfg.getString("messages.scroll.success",
                "&dThe Ancient Scroll bursts into light as the section &e{section}&d is completed!"));

        // Fishing
        msgFishingFoundTablet = color(cfg.getString("messages.fishing.found-tablet",
                "&6You fish up an &eAncient Tablet&6!"));
    }


    // ------------------------------------------------------------------------
    // SBPC progression parsing & weights
    // ------------------------------------------------------------------------

    private void loadSbpcProgressionWeights(Plugin sbpcPlugin) {
        weightedEntries.clear();
        weightedSections.clear();
        entryDisplayNames.clear();
        sectionDisplayNames.clear();
        sectionIndexMap.clear();
        entryToSectionMap.clear();
        totalEntryWeight = 0.0;
        totalSectionWeight = 0.0;

        File configFile = new File(sbpcPlugin.getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            getLogger().severe("[SBPCLoot] Could not find SBPC config.yml at " + configFile.getAbsolutePath());
            return;
        }

        FileConfiguration cfg = YamlConfiguration.loadConfiguration(configFile);
        ConfigurationSection sectionsRoot = cfg.getConfigurationSection("progression.sections");
        if (sectionsRoot == null) {
            getLogger().severe("[SBPCLoot] SBPC config missing 'progression.sections' section.");
            return;
        }

        List<String> allSectionIds = new ArrayList<>(sectionsRoot.getKeys(false));
        allSectionIds.sort(Comparator.naturalOrder()); // consistent order, though SBPC has its own index

        // Build list of sections we can skip (exclude First Steps)
        List<String> skippableSectionIds = new ArrayList<>();
        int index = 0;
        for (String sectionId : allSectionIds) {
            ConfigurationSection sectionCfg = sectionsRoot.getConfigurationSection(sectionId);
            if (sectionCfg == null) continue;
            sectionIndexMap.put(sectionId, index++);

            String displayName = sectionCfg.getString("name", sectionId);
            sectionDisplayNames.put(sectionId, displayName);

            if (sectionId.equalsIgnoreCase("first_steps")) {
                continue; // do not allow skipping the very first section
            }
            skippableSectionIds.add(sectionId);
        }

        // Assign weights for sections: linearly from 10 down to 1
        int nSections = skippableSectionIds.size();
        if (nSections == 1) {
            weightedSections.add(new WeightedSection(skippableSectionIds.get(0), 10.0));
            totalSectionWeight = 10.0;
        } else if (nSections > 1) {
            for (int i = 0; i < nSections; i++) {
                String sectionId = skippableSectionIds.get(i);
                double w = 10.0 - (9.0 * i) / (nSections - 1); // 10 ... 1
                weightedSections.add(new WeightedSection(sectionId, w));
                totalSectionWeight += w;
            }
        }

        List<String> allEntryIdsInOrder = new ArrayList<>();
        List<String> allEntrySectionIdsInOrder = new ArrayList<>();

        for (String sectionId : allSectionIds) {
            ConfigurationSection sectionCfg = sectionsRoot.getConfigurationSection(sectionId);
            if (sectionCfg == null) continue;
            ConfigurationSection entriesCfg = sectionCfg.getConfigurationSection("entries");
            if (entriesCfg == null) continue;

            List<String> sectionOrder = new ArrayList<>();

            for (String entryId : entriesCfg.getKeys(false)) {
                ConfigurationSection entryCfg = entriesCfg.getConfigurationSection(entryId);
                if (entryCfg == null) continue;

                String entryName = entryCfg.getString("name", entryId);
                entryDisplayNames.put(entryId, entryName);
                entryToSectionMap.put(entryId, sectionId);

                // Always track the full per-section order (even for first_steps / wood_axe)
                sectionOrder.add(entryId);

                // Skip from *weights* but still keep in ordering:
                if (sectionId.equalsIgnoreCase("first_steps")) {
                    continue;
                }

                // Skip the Wooden Axe entry from being skippable by tablet,
                // but still keep in ordering for progression logic.
                if (entryId.equalsIgnoreCase("wood_axe") || entryId.equalsIgnoreCase("wooden_axe")) {
                    continue;
                }

                allEntryIdsInOrder.add(entryId);
                allEntrySectionIdsInOrder.add(sectionId);
            }

            sectionEntriesOrdered.put(sectionId, sectionOrder);
        }

        int nEntries = allEntryIdsInOrder.size();
        if (nEntries == 1) {
            String entryId = allEntryIdsInOrder.get(0);
            String sectionId = allEntrySectionIdsInOrder.get(0);
            weightedEntries.add(new WeightedEntry(sectionId, entryId, 10.0));
            totalEntryWeight = 10.0;
        } else if (nEntries > 1) {
            for (int i = 0; i < nEntries; i++) {
                String entryId = allEntryIdsInOrder.get(i);
                String sectionId = allEntrySectionIdsInOrder.get(i);
                double w = 10.0 - (9.0 * i) / (nEntries - 1); // 10 ... 1
                weightedEntries.add(new WeightedEntry(sectionId, entryId, w));
                totalEntryWeight += w;
            }
        }
    }

    // ------------------------------------------------------------------------
    // Recipes: reroll tablets/scrolls
    // ------------------------------------------------------------------------

    private void registerRecipes() {
        // Reroll Ancient Tablet: 3 tablets -> 1 tablet with new random entry mapping
        ItemStack baseTablet = new ItemStack(Material.PAPER, 1);
        ItemMeta tabletMeta = baseTablet.getItemMeta();
        if (tabletMeta != null) {
            tabletMeta.setDisplayName(rerollTabletName);
            baseTablet.setItemMeta(tabletMeta);
        }

        ShapelessRecipe tabletReroll = new ShapelessRecipe(tabletRerollRecipeKey, baseTablet);
        tabletReroll.addIngredient(3, Material.PAPER); // we validate they are real tablets in PrepareItemCraftEvent
        Bukkit.addRecipe(tabletReroll);

        // Reroll Ancient Scroll: 3 scrolls -> 1 scroll with new random section mapping
        ItemStack baseScroll = new ItemStack(Material.ENCHANTED_BOOK, 1);
        ItemMeta scrollMeta = baseScroll.getItemMeta();
        if (scrollMeta != null) {
            scrollMeta.setDisplayName(rerollScrollName);
            baseScroll.setItemMeta(scrollMeta);
        }

        ShapelessRecipe scrollReroll = new ShapelessRecipe(scrollRerollRecipeKey, baseScroll);
        scrollReroll.addIngredient(3, Material.ENCHANTED_BOOK);
        Bukkit.addRecipe(scrollReroll);
    }


    // ------------------------------------------------------------------------
    // Item creation & detection
    // ------------------------------------------------------------------------
    private ItemStack createRandomTablet() {
        WeightedEntry picked = pickRandomEntry();
        if (picked == null) {
            ItemStack fallback = new ItemStack(Material.PAPER, 1);
            ItemMeta meta = fallback.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(tabletName != null ? tabletName : color("&6Ancient Tablet"));
                meta.setLore(Collections.singletonList(color("&cNo valid entries configured.")));
                fallback.setItemMeta(meta);
            }
            return fallback;
        }

        String sectionName = sectionDisplayNames.getOrDefault(picked.sectionId, picked.sectionId);
        String entryName = entryDisplayNames.getOrDefault(picked.entryId, picked.entryId);

        ItemStack stack = new ItemStack(Material.PAPER, 1);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(tabletName);

            List<String> lore = new ArrayList<>();
            for (String line : tabletLore) {
                lore.add(line
                        .replace("{section}", sectionName)
                        .replace("{entry}", entryName));
            }
            meta.setLore(lore);

            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(tabletKey, PersistentDataType.BYTE, (byte) 1);
            pdc.set(tabletEntryKey, PersistentDataType.STRING, picked.entryId);
            pdc.set(tabletSectionKey, PersistentDataType.STRING, picked.sectionId);
            stack.setItemMeta(meta);
        }
        return stack;
    }


    private ItemStack createRandomScroll() {
        WeightedSection picked = pickRandomSection();
        if (picked == null) {
            ItemStack fallback = new ItemStack(Material.ENCHANTED_BOOK, 1);
            ItemMeta meta = fallback.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(scrollName != null ? scrollName : color("&dAncient Scroll"));
                meta.setLore(Collections.singletonList(color("&cNo valid sections configured.")));
                fallback.setItemMeta(meta);
            }
            return fallback;
        }

        String sectionName = sectionDisplayNames.getOrDefault(picked.sectionId, picked.sectionId);

        ItemStack stack = new ItemStack(Material.ENCHANTED_BOOK, 1);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(scrollName);

            List<String> lore = new ArrayList<>();
            for (String line : scrollLore) {
                lore.add(line.replace("{section}", sectionName));
            }
            meta.setLore(lore);

            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(scrollKey, PersistentDataType.BYTE, (byte) 1);
            pdc.set(scrollSectionKey, PersistentDataType.STRING, picked.sectionId);
            stack.setItemMeta(meta);
        }
        return stack;
    }


    private WeightedEntry pickRandomEntry() {
        if (weightedEntries.isEmpty() || totalEntryWeight <= 0.0) return null;
        double r = ThreadLocalRandom.current().nextDouble() * totalEntryWeight;
        for (WeightedEntry e : weightedEntries) {
            r -= e.weight;
            if (r <= 0.0) {
                return e;
            }
        }
        return weightedEntries.get(weightedEntries.size() - 1); // fallback
    }

    private WeightedSection pickRandomSection() {
        if (weightedSections.isEmpty() || totalSectionWeight <= 0.0) return null;
        double r = ThreadLocalRandom.current().nextDouble() * totalSectionWeight;
        for (WeightedSection s : weightedSections) {
            r -= s.weight;
            if (r <= 0.0) {
                return s;
            }
        }
        return weightedSections.get(weightedSections.size() - 1);
    }

    
    private boolean isAncientTablet(ItemStack stack) {
        if (stack == null || stack.getType() != Material.PAPER) return false;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return false;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        return pdc.has(tabletKey, PersistentDataType.BYTE);
    }

    private boolean isAncientScroll(ItemStack stack) {
        if (stack == null || stack.getType() != Material.ENCHANTED_BOOK) return false;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return false;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        return pdc.has(scrollKey, PersistentDataType.BYTE);
    }

    // ------------------------------------------------------------------------
    // Crafting rerolls (3 tablets -> 1 tablet, 3 scrolls -> 1 scroll)
    // ------------------------------------------------------------------------

    @EventHandler
    public void onPrepareRerollCraft(PrepareItemCraftEvent event) {
        Recipe recipe = event.getRecipe();
        if (!(recipe instanceof ShapelessRecipe)) return;
        NamespacedKey key = ((ShapelessRecipe) recipe).getKey();
        if (key == null) return;

        ItemStack[] matrix = event.getInventory().getMatrix();

        if (key.equals(tabletRerollRecipeKey)) {
            if (!areExactlyThree(matrix, true)) {
                event.getInventory().setResult(null);
                return;
            }
            event.getInventory().setResult(createRandomTablet());
        } else if (key.equals(scrollRerollRecipeKey)) {
            if (!areExactlyThree(matrix, false)) {
                event.getInventory().setResult(null);
                return;
            }
            event.getInventory().setResult(createRandomScroll());
        }
    }

    private boolean areExactlyThree(ItemStack[] matrix, boolean tablets) {
        int count = 0;
        for (ItemStack stack : matrix) {
            if (stack == null || stack.getType() == Material.AIR) continue;
            count++;
            if (tablets && !isAncientTablet(stack)) return false;
            if (!tablets && !isAncientScroll(stack)) return false;
        }
        return count == 3;
    }

    // ------------------------------------------------------------------------
    // Using Tablets & Scrolls (right-click)
    // ------------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onUseTabletOrScroll(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return;

        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (item == null) return;

        if (isAncientTablet(item)) {
            event.setCancelled(true);
            handleUseTablet(player, item);
        } else if (isAncientScroll(item)) {
            event.setCancelled(true);
            handleUseScroll(player, item);
        }
    }
    private void handleUseTablet(Player player, ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String entryId = pdc.get(tabletEntryKey, PersistentDataType.STRING);
        String sectionId = pdc.get(tabletSectionKey, PersistentDataType.STRING);

        if (entryId == null || sectionId == null) {
            player.sendMessage(msgTabletDormant);
            return;
        }

        UUID uuid = player.getUniqueId();

        // Highest section the player has reached (including completed ones)
        String highestSectionId = SbpcAPI.getCurrentSectionId(uuid, true);
        if (highestSectionId == null) {
            player.sendMessage(msgTabletProgressUnknown);
            return;
        }

        int highestIndex = SbpcAPI.getSectionIndex(highestSectionId);
        Integer targetIndexObj = sectionIndexMap.get(sectionId);
        int targetIndex = (targetIndexObj == null) ? -1 : targetIndexObj;

        if (targetIndex == -1) {
            player.sendMessage(msgTabletUnknownSection);
            return;
        }

        String sectionName = sectionDisplayNames.getOrDefault(sectionId, sectionId);
        String entryName = entryDisplayNames.getOrDefault(entryId, entryId);

        // 1) Player hasn't even reached this section yet
        if (highestIndex < targetIndex) {
            player.sendMessage(msgTabletNotReachedSection.replace("{section}", sectionName));
            return;
        }

        // 2) Player is beyond this section or it's fully completed
        if (highestIndex > targetIndex || SbpcAPI.isSectionCompleted(uuid, sectionId)) {
            player.sendMessage(msgTabletBeyondSection.replace("{section}", sectionName));
            return;
        }

        // 3) Player is in this section and it is not complete.
        //    Now determine if they are currently ON this entry using per-section ordering.
        List<String> orderedEntries = sectionEntriesOrdered.get(sectionId);
        if (orderedEntries == null || !orderedEntries.contains(entryId)) {
            // Section exists but we couldn't resolve this entry in the section ordering.
            player.sendMessage(msgTabletDormant);
            return;
        }

        int idx = orderedEntries.indexOf(entryId);

        // Check which entries in this section are unlocked
        boolean targetUnlocked = SbpcAPI.isEntryUnlocked(uuid, entryId);
        boolean targetCompleted = SbpcAPI.isEntryCompleted(uuid, entryId);

        // If any prior entry is not unlocked, the player hasn't progressed that far yet.
        for (int j = 0; j < idx; j++) {
            String priorId = orderedEntries.get(j);
            if (!SbpcAPI.isEntryCompleted(uuid, priorId)) {
                // They haven't reached this entry in the section order.
                player.sendMessage(msgTabletNotOnEntry.replace("{entry}", entryName));
                return;
            }
        }

        // At this point, all prior entries are unlocked.

        if (targetCompleted) {
            // They've already completed this specific entry
            player.sendMessage(msgTabletAlreadyCompletedEntry.replace("{entry}", entryName));
            return;
        }

        if (!targetUnlocked) {
            // The player hasn't yet unlocked this entry (still on a prior one)
            player.sendMessage(msgTabletNotOnEntry.replace("{entry}", entryName));
            return;
        }

        // All earlier entries are unlocked, and this entry isn't unlocked yet:
        // treat this as "the current entry" and skip it via a big time skip.
        SbpcAPI.applyExternalTimeSkip(uuid, 1_000_000, 0.0,
                "SBPCLoot Ancient Tablet: " + entryId);

        player.sendMessage(msgTabletSuccess.replace("{entry}", entryName));

        // Consume one tablet (stack size is 1 by design)
        item.setAmount(item.getAmount() - 1);
    }

    private void handleUseScroll(Player player, ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();

        String sectionId = pdc.get(scrollSectionKey, PersistentDataType.STRING);
        if (sectionId == null) {
            player.sendMessage(msgScrollDormant);
            return;
        }

        UUID uuid = player.getUniqueId();
        String currentSectionId = SbpcAPI.getCurrentSectionId(uuid, true);
        if (currentSectionId == null) {
            player.sendMessage(msgScrollProgressUnknown);
            return;
        }

        int currentIndex = SbpcAPI.getSectionIndex(currentSectionId);
        Integer targetIndexObj = sectionIndexMap.get(sectionId);
        int targetIndex = targetIndexObj == null ? -1 : targetIndexObj;

        if (targetIndex == -1) {
            player.sendMessage(msgScrollUnknownSection);
            return;
        }

        String sectionName = sectionDisplayNames.getOrDefault(sectionId, sectionId);

        if (currentIndex < targetIndex) {
            player.sendMessage(msgScrollNotReachedSection.replace("{section}", sectionName));
            return;
        }

        if (currentIndex > targetIndex || SbpcAPI.isSectionCompleted(uuid, sectionId)) {
            player.sendMessage(msgScrollBeyondSection.replace("{section}", sectionName));
            return;
        }

        SbpcAPI.completeCurrentSection(uuid);

        player.sendMessage(msgScrollSuccess.replace("{section}", sectionName));

        item.setAmount(item.getAmount() - 1);
    }

    // ------------------------------------------------------------------------
    // Loot: chests, archaeology, fishing, mobs, dragon
    // ------------------------------------------------------------------------

    @EventHandler
    public void onLootGenerate(LootGenerateEvent event) {
        LootTable table = event.getLootTable();
        NamespacedKey key = table.getKey();
        if (key == null) return;

        String fullKey = key.getNamespace() + ":" + key.getKey();
        List<ItemStack> loot = event.getLoot();
        Random random = ThreadLocalRandom.current();

        // Structure chests for tablets
        if (TABLET_CHEST_TABLES.contains(fullKey)) {
            if (random.nextDouble() < 0.33) {
                int count = 1 + random.nextInt(2); // 1 or 2
                for (int i = 0; i < count; i++) {
                    loot.add(createRandomTablet());
                }
            }
        }

        // Structure chests for scrolls
        if (SCROLL_CHEST_TABLES.contains(fullKey)) {
            if (random.nextDouble() < 0.10) {
                loot.add(createRandomScroll());
            }
        }

        // Archaeology loot: tablets 10%, scrolls 1%
        if (ARCHAEOLOGY_TABLES.contains(fullKey)) {
            if (random.nextDouble() < 0.10) {
                loot.add(createRandomTablet());
            }
            if (random.nextDouble() < 0.01) {
                loot.add(createRandomScroll());
            }
        }
    }

    @EventHandler
    public void onFishing(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;

        Player player = event.getPlayer();
        double baseChance = 0.02; // 2% base
        int luckLevel = 0;

        ItemStack main = player.getInventory().getItemInMainHand();
        ItemStack off = player.getInventory().getItemInOffHand();
        ItemStack rod = null;

        if (main != null && main.getType() == Material.FISHING_ROD) {
            rod = main;
        } else if (off != null && off.getType() == Material.FISHING_ROD) {
            rod = off;
        }

        if (rod != null) {
            luckLevel = rod.getEnchantmentLevel(Enchantment.LUCK_OF_THE_SEA);
        }

        double chance = baseChance + 0.01 * luckLevel;

        if (ThreadLocalRandom.current().nextDouble() < chance) {
            Location dropLoc;
            if (event.getCaught() instanceof Item caught) {
                dropLoc = caught.getLocation();
            } else {
                dropLoc = player.getLocation();
            }
            dropLoc.getWorld().dropItemNaturally(dropLoc, createRandomTablet());
            player.sendMessage(msgFishingFoundTablet);
        }
    }

    @EventHandler
    public void onMobDropsTablet(EntityDeathEvent event) {
        EntityType type = event.getEntityType();
        if (type != EntityType.SKELETON &&
                type != EntityType.CREEPER &&
                type != EntityType.SPIDER &&
                type != EntityType.ZOMBIE &&
                type != EntityType.ENDERMAN) {
            return;
        }

        if (ThreadLocalRandom.current().nextDouble() < 0.002) { // 0.2%
            event.getDrops().add(createRandomTablet());
        }
    }

    @EventHandler
    public void onDragonDropsScroll(EntityDeathEvent event) {
        if (event.getEntityType() != EntityType.ENDER_DRAGON) return;
        Location loc = event.getEntity().getLocation();
        loc.getWorld().dropItemNaturally(loc, createRandomScroll());
    }
}
