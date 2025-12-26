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
import java.lang.reflect.Method;
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

    private static class WeightedEntry {
        final String sectionId;
        final String entryId;
        final double weight;

        /**
         * Creates a weighted progression entry so tablets know which SBPC entry they skip.
         *
         * @param sectionId owning section identifier
         * @param entryId   progression entry identifier
         * @param weight    selection weight for randomization
         */
        WeightedEntry(String sectionId, String entryId, double weight) {
            this.sectionId = sectionId;
            this.entryId = entryId;
            this.weight = weight;
        }
    }

    private static class WeightedSection {
        final String sectionId;
        final double weight;

        /**
         * Creates a weighted section so scrolls can complete an entire SBPC section at once.
         *
         * @param sectionId section identifier in SBPC progression
         * @param weight    selection weight for randomization
         */
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
    /**
     * Bootstraps the plugin by wiring config defaults, ensuring SBPC is available, loading
     * progression weights, registering event listeners, and exposing crafting recipes.
     */
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

    /**
     * Translates ampersand-based color codes into Bukkit ChatColor codes so text renders correctly in-game.
     *
     * @param s raw string containing optional color placeholders
     * @return colored string, or empty string when the input is null
     */
    private String color(String s) {
        if (s == null) return "";
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    /**
     * Applies {@link #color(String)} to every element in a list while guarding against null inputs.
     *
     * @param list source list, possibly null
     * @return new list with color codes translated
     */
    private List<String> colorList(List<String> list) {
        List<String> out = new ArrayList<>();
        if (list == null) return out;
        for (String s : list) {
            out.add(color(s));
        }
        return out;
    }

    /**
     * Reloads all configurable names, lore, and messages from the plugin's config.yml file.
     * This allows the plugin to pick up localization or text tweaks without touching code.
     */
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

    /**
     * Parses SBPC's progression config to populate weighted sections and entries used when
     * generating tablets and scrolls. This keeps loot aligned with progression ordering.
     *
     * @param sbpcPlugin the SBPC plugin instance used to locate its configuration
     */
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
        allSectionIds.sort(Comparator
                .comparingInt((String id) -> {
                    int idx = SbpcAPI.getSectionIndex(id);
                    return idx < 0 ? Integer.MAX_VALUE : idx;
                })
                .thenComparing(Comparator.naturalOrder()));

        // Build list of sections we can skip (exclude First Steps)
        List<String> skippableSectionIds = new ArrayList<>();
        for (String sectionId : allSectionIds) {
            ConfigurationSection sectionCfg = sectionsRoot.getConfigurationSection(sectionId);
            if (sectionCfg == null) continue;
            sectionIndexMap.put(sectionId, SbpcAPI.getSectionIndex(sectionId));

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

            for (String entryId : entriesCfg.getKeys(false)) {
                ConfigurationSection entryCfg = entriesCfg.getConfigurationSection(entryId);
                if (entryCfg == null) continue;

                String entryName = entryCfg.getString("name", entryId);
                entryDisplayNames.put(entryId, entryName);
                entryToSectionMap.put(entryId, sectionId);

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

    /**
     * Registers shapeless crafting recipes that allow players to reroll tablets or scrolls
     * by combining three of the same item type.
     */
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
    /**
     * Builds a new Ancient Tablet item with persistent data marking which entry and section it should skip.
     * Chooses the entry using the weighted configuration parsed from SBPC.
     *
     * @return a fully tagged tablet, or a fallback paper item if no entries are available
     */
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


    /**
     * Builds a new Ancient Scroll item that will complete an entire SBPC section when used.
     *
     * @return a fully tagged scroll, or a fallback enchanted book if no sections are available
     */
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


    /**
     * Chooses a random progression entry using the weighted list derived from SBPC configuration.
     *
     * @return chosen weighted entry, or null when no entries exist
     */
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

    /**
     * Chooses a random section using the weighted list derived from SBPC configuration.
     *
     * @return chosen weighted section, or null when no sections exist
     */
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

    
    /**
     * Determines whether an ItemStack is one of this plugin's Ancient Tablets via type and PDC marker.
     *
     * @param stack candidate item
     * @return true when the item is a tagged tablet
     */
    private boolean isAncientTablet(ItemStack stack) {
        if (stack == null || stack.getType() != Material.PAPER) return false;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return false;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        return pdc.has(tabletKey, PersistentDataType.BYTE);
    }

    /**
     * Determines whether an ItemStack is one of this plugin's Ancient Scrolls via type and PDC marker.
     *
     * @param stack candidate item
     * @return true when the item is a tagged scroll
     */
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

    /**
     * Validates reroll crafting recipes to ensure only genuine tablets or scrolls are accepted
     * and replaces the result with a freshly randomized item.
     */
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

    /**
     * Confirms that a crafting matrix contains exactly three non-air items and that all of them
     * are either tablets or scrolls depending on the requested type.
     *
     * @param matrix 3x3 crafting grid contents
     * @param tablets true to validate tablets, false for scrolls
     * @return true when the grid matches expectations
     */
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

    /**
     * Detects right-click interactions and routes them to tablet or scroll handling when
     * the player is holding one of the custom items in their main hand.
     */
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
    /**
     * Applies an Ancient Tablet to skip the player's current SBPC entry when the tablet's
     * metadata matches the active progression point.
     *
     * @param player player using the tablet
     * @param item   tablet stack being consumed
     */
    private void handleUseTablet(Player player, ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String entryId = pdc.get(tabletEntryKey, PersistentDataType.STRING);
        String sectionId = pdc.get(tabletSectionKey, PersistentDataType.STRING);

        if (entryId == null || sectionId == null) {
            debugTablet(player, "tablet-dormant", entryId, sectionId, null, null, -1, -1, false, false);
            player.sendMessage(msgTabletDormant);
            return;
        }

        UUID uuid = player.getUniqueId();

        // Highest section the player has reached (including completed ones)
        String highestSectionId = SbpcAPI.getCurrentSectionId(uuid, true);
        if (highestSectionId == null) {
            debugTablet(player, "progress-unknown", entryId, sectionId, null, null, -1, -1, false, false);
            player.sendMessage(msgTabletProgressUnknown);
            return;
        }

        int highestIndex = SbpcAPI.getSectionIndex(highestSectionId);
        Integer targetIndexObj = sectionIndexMap.get(sectionId);
        int targetIndex = (targetIndexObj == null) ? -1 : targetIndexObj;

        boolean targetUnlocked = false;
        boolean targetCompleted = false;
        String currentEntryId = null;

        if (targetIndex == -1) {
            debugTablet(player, "unknown-section", entryId, sectionId, null, highestSectionId, highestIndex, targetIndex, false, false);
            player.sendMessage(msgTabletUnknownSection);
            return;
        }

        String sectionName = sectionDisplayNames.getOrDefault(sectionId, sectionId);
        String entryName = entryDisplayNames.getOrDefault(entryId, entryId);

        // 1) Player hasn't even reached this section yet
        if (highestIndex < targetIndex) {
            debugTablet(player, "not-reached-section", entryId, sectionId, null, highestSectionId, highestIndex, targetIndex, false, false);
            player.sendMessage(msgTabletNotReachedSection.replace("{section}", sectionName));
            return;
        }

        // 2) Player is beyond this section or it's fully completed
        if (highestIndex > targetIndex || SbpcAPI.isSectionCompleted(uuid, sectionId)) {
            debugTablet(player, "beyond-section-or-completed", entryId, sectionId, null, highestSectionId, highestIndex, targetIndex, false, false);
            player.sendMessage(msgTabletBeyondSection.replace("{section}", sectionName));
            return;
        }

        currentEntryId = getCurrentEntryId(uuid);
        if (currentEntryId == null) {
            debugTablet(player, "no-current-entry", entryId, sectionId, null, highestSectionId, highestIndex, targetIndex, targetUnlocked, targetCompleted);
            player.sendMessage(msgTabletNoCurrentEntry);
            return;
        }

        if (!currentEntryId.equals(entryId)) {
            debugTablet(player, "not-on-target-entry", entryId, sectionId, currentEntryId, highestSectionId, highestIndex, targetIndex, targetUnlocked, targetCompleted);
            player.sendMessage(msgTabletNotOnEntry.replace("{entry}", entryName));
            return;
        }

        targetUnlocked = SbpcAPI.isEntryUnlocked(uuid, entryId) || currentEntryId.equals(entryId);
        targetCompleted = isEntryCompleted(uuid, entryId);

        if (targetCompleted) {
            // They've already completed this specific entry
            debugTablet(player, "target-already-completed", entryId, sectionId, currentEntryId, highestSectionId, highestIndex, targetIndex, targetUnlocked, targetCompleted);
            player.sendMessage(msgTabletAlreadyCompletedEntry.replace("{entry}", entryName));
            return;
        }

        if (!targetUnlocked) {
            // The player hasn't yet unlocked this entry (still on a prior one)
            debugTablet(player, "entry-not-unlocked", entryId, sectionId, currentEntryId, highestSectionId, highestIndex, targetIndex, targetUnlocked, targetCompleted);
            player.sendMessage(msgTabletNotOnEntry.replace("{entry}", entryName));
            return;
        }

        // Player is currently on this entry: skip it via a big time skip.
        SbpcAPI.applyExternalTimeSkip(uuid, 1_000_000, 0.0,
                "SBPCLoot Ancient Tablet: " + entryId);

        debugTablet(player, "success", entryId, sectionId, currentEntryId, highestSectionId, highestIndex, targetIndex, targetUnlocked, targetCompleted);
        player.sendMessage(msgTabletSuccess.replace("{entry}", entryName));

        // Consume one tablet (stack size is 1 by design)
        item.setAmount(item.getAmount() - 1);
    }

    /**
     * Determines whether a specific SBPC entry is complete for a player, probing multiple API
     * signatures for backwards compatibility and falling back to unlocked state when necessary.
     *
     * @param uuid    target player
     * @param entryId progression entry to check
     * @return true when the entry is considered completed
     */
    private boolean isEntryCompleted(UUID uuid, String entryId) {
        // 1) Try a direct SbpcAPI.isEntryCompleted(...) if it exists.
        try {
            Method apiMethod = SbpcAPI.class.getMethod("isEntryCompleted", UUID.class, String.class);
            Object result = apiMethod.invoke(null, uuid, entryId);
            if (result instanceof Boolean b) {
                return b;
            }
        } catch (NoSuchMethodException ignored) {
            // continue to fallback
        } catch (ReflectiveOperationException e) {
            getLogger().warning("Failed to call SbpcAPI.isEntryCompleted: " + e.getMessage());
        }

        // 2) Try PlayerProgress#isEntryCompleted(String) if it exists.
        try {
            Object progress = SbpcAPI.getProgress(uuid);
            Method progressMethod = progress.getClass().getMethod("isEntryCompleted", String.class);
            Object result = progressMethod.invoke(progress, entryId);
            if (result instanceof Boolean b) {
                return b;
            }
        } catch (NoSuchMethodException ignored) {
            // continue to fallback
        } catch (ReflectiveOperationException e) {
            getLogger().warning("Failed to call PlayerProgress.isEntryCompleted: " + e.getMessage());
        }

        // 3) Fallback: treat unlocked as completed when no completion API exists.
        return SbpcAPI.isEntryUnlocked(uuid, entryId);
    }

    /**
     * Resolves the player's current SBPC entry using whichever API variant is available.
     *
     * @param uuid target player
     * @return the active entry id, or null if it cannot be determined
     */
    private String getCurrentEntryId(UUID uuid) {
        // 1) Try SbpcAPI.getCurrentEntryId(UUID, boolean) for compatibility with APIs
        //    that mirror the getCurrentSectionId signature.
        try {
            Method apiMethod = SbpcAPI.class.getMethod("getCurrentEntryId", UUID.class, boolean.class);
            Object result = apiMethod.invoke(null, uuid, true);
            if (result instanceof String s) {
                return s;
            }
        } catch (NoSuchMethodException ignored) {
            // continue to fallback
        } catch (ReflectiveOperationException e) {
            getLogger().warning("Failed to call SbpcAPI.getCurrentEntryId(UUID, boolean): " + e.getMessage());
        }

        // 2) Try SbpcAPI.getCurrentEntryId(UUID) if available.
        try {
            Method apiMethod = SbpcAPI.class.getMethod("getCurrentEntryId", UUID.class);
            Object result = apiMethod.invoke(null, uuid);
            if (result instanceof String s) {
                return s;
            }
        } catch (NoSuchMethodException ignored) {
            // continue to fallback
        } catch (ReflectiveOperationException e) {
            getLogger().warning("Failed to call SbpcAPI.getCurrentEntryId(UUID): " + e.getMessage());
        }

        // 3) Try PlayerProgress#getCurrentEntryId() on the player progress object.
        try {
            Object progress = SbpcAPI.getProgress(uuid);
            Method progressMethod = progress.getClass().getMethod("getCurrentEntryId");
            Object result = progressMethod.invoke(progress);
            if (result instanceof String s) {
                return s;
            }
        } catch (NoSuchMethodException ignored) {
            // continue to fallback
        } catch (ReflectiveOperationException e) {
            getLogger().warning("Failed to call PlayerProgress.getCurrentEntryId: " + e.getMessage());
        }

        return null;
    }

    /**
     * Applies an Ancient Scroll to complete the target SBPC section when the player is currently
     * progressing within that section and has not already surpassed it.
     *
     * @param player player using the scroll
     * @param item   scroll stack being consumed
     */
    private void handleUseScroll(Player player, ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();

        String sectionId = pdc.get(scrollSectionKey, PersistentDataType.STRING);
        if (sectionId == null) {
            debugScroll(player, "scroll-dormant", null, null, -1, -1);
            player.sendMessage(msgScrollDormant);
            return;
        }

        UUID uuid = player.getUniqueId();
        String currentSectionId = SbpcAPI.getCurrentSectionId(uuid, true);
        if (currentSectionId == null) {
            debugScroll(player, "progress-unknown", sectionId, null, -1, -1);
            player.sendMessage(msgScrollProgressUnknown);
            return;
        }

        int currentIndex = SbpcAPI.getSectionIndex(currentSectionId);
        Integer targetIndexObj = sectionIndexMap.get(sectionId);
        int targetIndex = targetIndexObj == null ? -1 : targetIndexObj;

        if (targetIndex == -1) {
            debugScroll(player, "unknown-section", sectionId, currentSectionId, currentIndex, targetIndex);
            player.sendMessage(msgScrollUnknownSection);
            return;
        }

        String sectionName = sectionDisplayNames.getOrDefault(sectionId, sectionId);

        if (currentIndex < targetIndex) {
            debugScroll(player, "not-reached-section", sectionId, currentSectionId, currentIndex, targetIndex);
            player.sendMessage(msgScrollNotReachedSection.replace("{section}", sectionName));
            return;
        }

        if (currentIndex > targetIndex || SbpcAPI.isSectionCompleted(uuid, sectionId)) {
            debugScroll(player, "beyond-section-or-completed", sectionId, currentSectionId, currentIndex, targetIndex);
            player.sendMessage(msgScrollBeyondSection.replace("{section}", sectionName));
            return;
        }

        SbpcAPI.completeCurrentSection(uuid);

        debugScroll(player, "success", sectionId, currentSectionId, currentIndex, targetIndex);
        player.sendMessage(msgScrollSuccess.replace("{section}", sectionName));

        item.setAmount(item.getAmount() - 1);
    }

    /**
     * Optional debug hook for tracing tablet usage scenarios; logging is intentionally commented out
     * to avoid spam but can be enabled during troubleshooting.
     */
    private void debugTablet(Player player, String reason, String entryId, String sectionId,
                             String currentEntryId, String highestSectionId, int highestIndex,
                             int targetIndex, boolean targetUnlocked, boolean targetCompleted) {
        /*getLogger().info("[TabletDebug] player=" + player.getName()
                + " reason=" + reason
                + " entryId=" + entryId
                + " sectionId=" + sectionId
                + " currentEntryId=" + currentEntryId
                + " highestSectionId=" + highestSectionId
                + " highestIndex=" + highestIndex
                + " targetIndex=" + targetIndex
                + " unlocked=" + targetUnlocked
                + " completed=" + targetCompleted);*/
    }

    /**
     * Optional debug hook for tracing scroll usage scenarios; logging can be toggled on when needed.
     */
    private void debugScroll(Player player, String reason, String sectionId, String currentSectionId,
                             int currentIndex, int targetIndex) {
        /*getLogger().info("[ScrollDebug] player=" + player.getName()
                + " reason=" + reason
                + " sectionId=" + sectionId
                + " currentSectionId=" + currentSectionId
                + " currentIndex=" + currentIndex
                + " targetIndex=" + targetIndex);*/
    }

    // ------------------------------------------------------------------------
    // Loot: chests, archaeology, fishing, mobs, dragon
    // ------------------------------------------------------------------------

    /**
     * Injects tablets and scrolls into vanilla loot tables for structure chests and archaeology
     * loot based on configured drop chances.
     */
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

    /**
     * Adds a chance for fishing to yield an Ancient Tablet, influenced by Luck of the Sea enchantment.
     */
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

    /**
     * Grants a small chance for common hostile mobs to drop an Ancient Tablet upon death.
     */
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

    /**
     * Guarantees that the Ender Dragon drops an Ancient Scroll when defeated.
     */
    @EventHandler
    public void onDragonDropsScroll(EntityDeathEvent event) {
        if (event.getEntityType() != EntityType.ENDER_DRAGON) return;
        Location loc = event.getEntity().getLocation();
        loc.getWorld().dropItemNaturally(loc, createRandomScroll());
    }
}
