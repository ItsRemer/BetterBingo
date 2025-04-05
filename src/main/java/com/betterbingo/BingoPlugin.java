package com.betterbingo;

import com.google.inject.Provides;
import com.google.inject.Injector;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import javax.swing.JOptionPane;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.events.GameTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageCapture;
import net.runelite.client.util.ImageUtil;
import net.runelite.http.api.item.ItemPrice;
import okhttp3.*;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.Map;

import net.runelite.api.ItemComposition;
import net.runelite.api.NPC;

import java.util.Collection;

import net.runelite.api.events.ActorDeath;
import net.runelite.api.Actor;
import net.runelite.api.events.ItemSpawned;

import java.util.HashSet;
import java.util.Set;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.HashMap;

import net.runelite.client.util.Text;

import java.io.IOException;

import java.util.concurrent.CompletableFuture;
import java.util.Optional;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Bingo Plugin for RuneLite
 * Tracks items for bingo events and sends notifications to Discord
 * Supports loading items from Pastebin for easy sharing
 */
@Slf4j
@PluginDescriptor(
        name = "Bingo",
        description = "Track items for bingo events. Supports Pastebin for easy sharing.",
        tags = {"bingo", "items", "collection", "pastebin"}
)
public class BingoPlugin extends Plugin {

    private static final String CONFIG_GROUP = "bingo";
    private static final String CONFIG_KEY_OBTAINED_ITEMS = "obtainedItems";
    private static final int GRID_SIZE = 5;
    private static final int MAX_ITEMS = 100;

    @Inject
    private Client client;
    @Inject
    private ClientThread clientThread;
    @Inject
    private BingoConfig config;
    @Inject
    private ClientToolbar clientToolbar;
    @Inject
    private ItemManager itemManager;
    @Inject
    private ConfigManager configManager;
    @Inject
    private ScheduledExecutorService executor;
    @Inject
    private OkHttpClient okHttpClient;
    @Inject
    private Injector injector;
    @Inject
    private DrawManager drawManager;
    @Inject
    private ImageCapture imageCapture;
    @Inject
    private Gson gson;
    @Inject
    private BingoAntiCheat antiCheat;
    @Inject
    private BingoDiscordNotifier discordNotifier;
    @Inject
    private BingoProfileManager profileManager;
    @Inject
    private BingoTeamService teamService;

    @Getter
    private final List<BingoItem> items = new ArrayList<>();

    private final Map<String, BingoItem> itemsByName = new HashMap<>();
    private final Set<Integer> recentlyKilledNpcs = new HashSet<>();
    private BingoPanel panel;
    private NavigationButton navButton;
    private boolean hasShownNotification = false;
    private boolean hasShownItemNotification = false;
    private boolean hasShownTeamNotification = false;

    // Backup profile tracking to handle when the config system fails
    private String currentProfileBackup = null;
    
    /**
     * Gets the current profile, using the backup tracking if config system is unreliable
     * @return The current profile name
     */
    public String getCurrentProfileReliably() {
        // Try config first
        String configProfile = config.currentProfile();
        
        // If the config is empty but we have a backup, use that
        if ((configProfile == null || configProfile.isEmpty()) && currentProfileBackup != null) {
            log.warn("Config returned empty profile, using backup: {}", currentProfileBackup);
            return currentProfileBackup;
        }
        
        // If config has a value, update our backup
        if (configProfile != null && !configProfile.isEmpty()) {
            // Update backup value when config has a valid value
            if (!configProfile.equals(currentProfileBackup)) {
                log.debug("Updating profile backup: {} -> {}", currentProfileBackup, configProfile);
                currentProfileBackup = configProfile;
            }
            return configProfile;
        }
        
        // Fallback to default
        return "default";
    }
    
    /**
     * Updates our backup tracking system when switching profiles
     */
    public void setCurrentProfileBackup(String profile) {
        if (profile != null && !profile.isEmpty()) {
            log.info("Setting profile backup to: {}", profile);
            currentProfileBackup = profile;
        }
    }

    /**
     * Represents an item that has been obtained
     */
    @Getter
    private static class ObtainedItem {
        private final BingoItem bingoItem;
        private final int index;

        public ObtainedItem(BingoItem bingoItem, int index) {
            this.bingoItem = bingoItem;
            this.index = index;
        }

        public BingoItem getBingoItem() {
            return bingoItem;
        }

        public int getIndex() {
            return index;
        }

        public String getName() {
            return bingoItem.getName();
        }
    }

    /**
     * Represents the type of bingo completion
     */
    private enum CompletionType {
        ROW,
        COLUMN,
        DIAGONAL1,
        DIAGONAL2,
        FULL
    }

    /**
     * Gets a human-readable message for a completion type
     *
     * @param completionType  The type of completion
     * @param completionIndex The index of the completed row or column
     * @return A human-readable message
     */
    private String getCompletionMessage(CompletionType completionType, int completionIndex) {
        switch (completionType) {
            case ROW:
                return "Row " + (completionIndex + 1) + " complete!";
            case COLUMN:
                return "Column " + (completionIndex + 1) + " complete!";
            case DIAGONAL1:
                return "Diagonal (top-left to bottom-right) complete!";
            case DIAGONAL2:
                return "Diagonal (top-right to bottom-left) complete!";
            case FULL:
                return "BINGO! Full board complete!";
            default:
                return "Bingo completion!";
        }
    }

    /**
     * Represents a pattern for matching chat messages related to item drops
     */
    private static class ChatMessagePattern {
        private final String pattern;
        private final Function<String, String> itemExtractor;
        @Getter
        private final boolean multiItem;

        public ChatMessagePattern(String pattern, Function<String, String> itemExtractor, boolean multiItem) {
            this.pattern = pattern;
            this.itemExtractor = itemExtractor;
            this.multiItem = multiItem;
        }

        public boolean matches(String message) {
            return message.contains(pattern);
        }

        public String extractItem(String message) {
            return itemExtractor.apply(message);
        }
    }

    private final List<ChatMessagePattern> chatPatterns = Arrays.asList(
            // Regular drops
            new ChatMessagePattern("Valuable drop:",
                    message -> message.substring(message.indexOf(":") + 2), false),
            new ChatMessagePattern("You receive a drop:",
                    message -> message.substring(message.indexOf(":") + 2), false),

            // Pet drops
            new ChatMessagePattern("You have a funny feeling like",
                    message -> message.substring(0, message.indexOf("like you") - 1)
                            .replace("You have a funny feeling ", ""), false),

            // Collection log
            new ChatMessagePattern("New item added to your collection log:",
                    message -> message.substring(message.indexOf(":") + 2), false),

            // Raids drops with specific format
            new ChatMessagePattern("- ",
                    message -> {
                        if (message.contains("Chambers of Xeric") ||
                                message.contains("Theatre of Blood") ||
                                message.contains("Tombs of Amascut")) {
                            return message.substring(message.indexOf("- ") + 2);
                        }
                        return null;
                    }, false),

            // Special raid loot messages
            new ChatMessagePattern("Special loot:",
                    message -> message.substring(message.indexOf(":") + 2), true),

            // Chest opening messages
            new ChatMessagePattern("You open the chest and find:",
                    message -> message.substring(message.indexOf("find:") + 6), true),
            new ChatMessagePattern("You find some loot:",
                    message -> message.substring(message.indexOf("loot:") + 6), true),
            new ChatMessagePattern("Your loot is:",
                    message -> message.substring(message.indexOf("is:") + 4), true),

            // Special drop notifications
            new ChatMessagePattern("received a special drop:",
                    message -> message.substring(message.indexOf("drop:") + 6), false),
            new ChatMessagePattern("received unique loot:",
                    message -> message.substring(message.indexOf("loot:") + 6), false)
    );

    /**
     * Extracts an item name from a string, handling quantity indicators and special cases
     *
     * @param rawItemName The raw item name to clean
     * @return The cleaned item name or null if invalid
     */
    private String cleanItemName(String rawItemName) {
        if (rawItemName == null || rawItemName.trim().isEmpty()) {
            return null;
        }

        String itemName = rawItemName.trim();

        // Handle "x quantity" format (e.g., "5 x Bones")
        if (itemName.contains(" x ")) {
            if (itemName.indexOf("x ") > 0) {
                itemName = itemName.substring(itemName.indexOf("x ") + 2).trim();
            }
        }

        // Handle "quantity x" format (e.g., "5x Bones")
        if (itemName.matches(".*\\d+x .*")) {
            itemName = itemName.replaceAll("\\d+x ", "").trim();
        }

        // Handle "Item (quantity)" format (e.g., "Bones (5)")
        if (itemName.matches(".*\\(\\d+\\)$")) {
            itemName = itemName.replaceAll("\\s*\\(\\d+\\)$", "").trim();
        }

        return itemName;
    }

    @Override
    protected void startUp() throws Exception {
        try {
            log.info("Starting Bingo plugin");

            // Minimal setup on the main thread
            panel = injector.getInstance(BingoPanel.class);
            final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/icon.png");
            navButton = NavigationButton.builder()
                    .tooltip("Bingo")
                    .icon(icon)
                    .priority(5)
                    .panel(panel)
                    .build();

            // Schedule the rest of the initialization on a background thread
            startPlugin();

            log.info("Bingo plugin startup initiated");
        } catch (Exception e) {
            log.error("Error starting Bingo plugin", e);
            throw e;
        }
    }

    /**
     * Initializes the plugin in the background to avoid blocking the main thread
     */
    private void startPlugin() {
        log.info("Bingo plugin startup initiated");
        executor.execute(() -> {
            try {
                log.info("Initializing Bingo plugin in background");
                
                // Load items
                reloadItems();
                
                // Add the panel to the sidebar if enabled in config
                if (config.showSidebar()) {
                    clientThread.invokeLater(() -> {
                        if (clientToolbar != null && navButton != null) {
                            clientToolbar.addNavigation(navButton);
                        }
                    });
                }
                
                // Initialize with the current profile
                log.info("Starting with bingo profile: {}", config.currentProfile());
                
                // If the current profile is a team profile, set up the team listener
                BingoConfig.BingoMode bingoMode = profileManager.getProfileBingoMode();
                if (bingoMode == BingoConfig.BingoMode.TEAM) {
                    String teamCode = profileManager.getProfileTeamCode();
                    if (teamCode != null && !teamCode.isEmpty()) {
                        log.info("Team profile detected with code: {}", teamCode);
                        
                        // Register a team listener to get updates
                        teamService.registerTeamListener(teamCode, this::updateItemsFromFirebase);
                    }
                }
                
                // Update the UI on EDT once after initialization
                SwingUtilities.invokeLater(() -> {
                    if (panel != null) {
                        panel.updateProfileComboBox();
                        panel.updateItems(items);
                        panel.updateSourceWarningLabel();
                    }
                });
                
                log.info("Bingo plugin initialized successfully in background");
            } catch (Exception e) {
                log.error("Error initializing Bingo plugin in background", e);
            }
        });
    }

    @Override
    protected void shutDown() {
        // Remove the panel from the sidebar
        if (clientToolbar != null && navButton != null) {
            clientToolbar.removeNavigation(navButton);
        }

        // Clean up resources
        if (teamService != null) {
            teamService.cleanup();
        }
    }

    @Provides
    BingoConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(BingoConfig.class);
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (event.getGroup().equals(CONFIG_GROUP)) {
            String key = event.getKey();
            String prefix = null;

            // Check if the key is not null and contains a dot
            if (key != null && key.contains(".")) {
                prefix = key.substring(0, key.indexOf('.'));
            }

            // Only proceed if prefix is not null
            if (prefix != null && prefix.startsWith(config.currentProfile())) {
                if (key.endsWith("." + CONFIG_KEY_OBTAINED_ITEMS)) {
                    loadSavedItems();
                } else if (key.endsWith(".itemList") || key.endsWith(".remoteUrl") || key.endsWith(".itemSourceType")) {
                    reloadItems();
                }
            } else if (key.equals("currentProfile")) {
                reloadItems();
                loadSavedItems();
            } else if (key.equals("showSidebar")) {
                // Handle sidebar visibility change
                updateSidebarVisibility();
            }
        }
    }

    /**
     * Updates the sidebar visibility based on the config setting
     */
    private void updateSidebarVisibility() {
        if (config.showSidebar()) {
            // Add the panel to the sidebar
            clientToolbar.addNavigation(navButton);
        } else {
            // Remove the panel from the sidebar
            clientToolbar.removeNavigation(navButton);
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        if (event.getGameState() == GameState.LOGGING_IN) {
            loadItems();
            loadSavedItems();
        }
    }

    @Subscribe
    public void onNpcLootReceived(NpcLootReceived event) {
        NPC npc = event.getNpc();
        Collection<ItemStack> items = event.getItems();
        String npcName = npc.getName();
        String location = antiCheat.getPlayerLocationName();

        for (ItemStack item : items) {
            final ItemComposition itemComp = itemManager.getItemComposition(item.getId());
            String itemName = itemComp.getName();

            antiCheat.recordItemAcquisition(
                    cleanItemName(itemName),
                    BingoAntiCheat.AcquisitionMethod.NPC_DROP,
                    npcName,
                    location
            );
            checkForItem(itemName);
        }
    }

    @Subscribe
    public void onChatMessage(net.runelite.api.events.ChatMessage event) {
        if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM) {
            return;
        }

        String message = event.getMessage();
        String location = antiCheat.getPlayerLocationName();

        for (ChatMessagePattern pattern : chatPatterns) {
            if (pattern.matches(message)) {
                String extractedText = pattern.extractItem(message);

                if (extractedText == null) {
                    continue;
                }
                BingoAntiCheat.AcquisitionMethod method = BingoAntiCheat.AcquisitionMethod.CHAT_MESSAGE;
                String sourceDetails = "Chat message: " + message;

                if (message.contains("collection log")) {
                    method = BingoAntiCheat.AcquisitionMethod.COLLECTION_LOG;
                    sourceDetails = "Collection log";
                } else if (message.contains("chest") || message.contains("loot")) {
                    method = BingoAntiCheat.AcquisitionMethod.CHEST_LOOT;
                    sourceDetails = "Chest/Loot container";
                }

                if (pattern.isMultiItem()) {
                    String[] parts = extractedText.split(",|\\sand\\s");
                    for (String part : parts) {
                        String itemName = cleanItemName(part);
                        if (itemName != null && !itemName.isEmpty()) {
                            antiCheat.recordItemAcquisition(itemName, method, sourceDetails, location);
                            checkForItem(itemName);
                        }
                    }
                } else {
                    String itemName = cleanItemName(extractedText);
                    if (itemName != null && !itemName.isEmpty()) {
                        antiCheat.recordItemAcquisition(itemName, method, sourceDetails, location);
                        checkForItem(itemName);
                    }
                }

                return;
            }
        }
    }

    @Subscribe
    public void onActorDeath(ActorDeath event) {
        Actor actor = event.getActor();
        if (actor instanceof NPC) {
            recentlyKilledNpcs.add(((NPC) actor).getIndex());
            clientThread.invokeLater(() -> {
                recentlyKilledNpcs.remove(((NPC) actor).getIndex());
            });
        }
    }

    @Subscribe
    public void onItemSpawned(ItemSpawned event) {
        final TileItem item = event.getItem();
        final Tile tile = event.getTile();

        if (item == null || tile == null || client.getLocalPlayer() == null) {
            return;
        }

        boolean isNearKilledNpc = false;
        String npcName = "Unknown";

        for (NPC npc : client.getNpcs()) {
            if (npc != null && recentlyKilledNpcs.contains(npc.getIndex()) &&
                    tile.getWorldLocation().distanceTo(npc.getWorldLocation()) <= 1) {
                isNearKilledNpc = true;
                npcName = npc.getName();
                break;
            }
        }

        if (!isNearKilledNpc) {
            return;
        }

        final String finalNpcName = npcName;
        clientThread.invoke(() -> {
            final ItemComposition itemComp = itemManager.getItemComposition(item.getId());
            String itemName = itemComp.getName();
            String location = antiCheat.getPlayerLocationName();

            antiCheat.recordItemAcquisition(
                    cleanItemName(itemName),
                    BingoAntiCheat.AcquisitionMethod.GROUND_SPAWN,
                    finalNpcName,
                    location
            );

            checkForItem(itemName);
        });
    }

    /**
     * Checks if an item is on the bingo board and processes it if found
     *
     * @param itemName The name of the item to check
     */
    private void checkForItem(String itemName) {
        String cleanedItemName = cleanItemName(itemName);
        if (cleanedItemName == null) {
            return;
        }

        ObtainedItem obtainedItem = findAndMarkItem(cleanedItemName);

        if (obtainedItem != null) {
            notifyItemObtained(obtainedItem);
        }
    }

    /**
     * Finds and marks an item as obtained
     *
     * @param itemName The name of the item to find and mark
     * @return The obtained item, or null if not found
     */
    private ObtainedItem findAndMarkItem(String itemName) {
        // Clean up the item name
        itemName = cleanItemName(itemName);
        
        // Try to find the item in our list
        BingoItem bingoItem = itemsByName.get(itemName.toLowerCase());
        if (bingoItem == null) {
            // Try to find a partial match
            for (Map.Entry<String, BingoItem> entry : itemsByName.entrySet()) {
                if (entry.getKey().contains(itemName.toLowerCase()) || itemName.toLowerCase().contains(entry.getKey())) {
                    bingoItem = entry.getValue();
                    break;
                }
            }
            
            // If still not found, try to match against alternative names
            if (bingoItem == null) {
                for (BingoItem item : items) {
                    if (item.matchesName(itemName)) {
                        bingoItem = item;
                        break;
                    }
                }
            }
        }
        
        // If we found the item, mark it as obtained
        if (bingoItem != null) {
            // Check if it's already obtained
            if (bingoItem.isObtained()) {
                log.debug("Item already obtained: {}", itemName);
                return null;
            }
            
            // Mark the item as obtained
            bingoItem.setObtained(true);
            
            // Get the index of the item
            int index = items.indexOf(bingoItem);
            
            // Save the obtained status
            saveObtainedItems();
            
            // For team profiles, update the item in Firebase
            BingoConfig.BingoMode bingoMode = profileManager.getProfileBingoMode();
            if (bingoMode == BingoConfig.BingoMode.TEAM) {
                String teamCode = profileManager.getProfileTeamCode();
                if (teamCode != null && !teamCode.isEmpty()) {
                    log.info("Updating item obtained status in Firebase for team {}: {}", teamCode, bingoItem.getName());
                    
                    // Wait for the Firebase update to complete to ensure it's properly updated
                    try {
                        boolean success = teamService.updateItemObtained(teamCode, bingoItem.getName(), true)
                            .get(10, TimeUnit.SECONDS); // Wait up to 10 seconds for the update to complete
                        
                        if (success) {
                            log.info("Successfully updated item obtained status in Firebase");
                        } else {
                            log.error("Failed to update item obtained status in Firebase");
                            
                            // Try again with a direct call
                            log.info("Trying again with a direct call...");
                            success = teamService.updateItemObtained(teamCode, bingoItem.getName(), true)
                                .get(10, TimeUnit.SECONDS);
                            
                            if (success) {
                                log.info("Successfully updated item obtained status in Firebase on second attempt");
                            } else {
                                log.error("Failed to update item obtained status in Firebase on second attempt");
                            }
                        }
                    } catch (InterruptedException | ExecutionException | TimeoutException e) {
                        log.error("Error waiting for Firebase update to complete", e);
                    }
                }
            }
            
            // Update the UI
            SwingUtilities.invokeLater(() -> {
                Optional.ofNullable(panel).ifPresent(p -> p.updateItems(items));
            });
            
            // If this is a group item, log which specific item was obtained
            if (bingoItem.isGroup()) {
                log.info("Obtained item group '{}' via item '{}'", bingoItem.getName(), itemName);
            }
            
            return new ObtainedItem(bingoItem, index);
        }
        
        return null;
    }

    /**
     * Updates items from Firebase
     *
     * @param updatedItems The updated items from Firebase
     */
    public void updateItemsFromFirebase(List<BingoItem> updatedItems) {
        String currentProfile = config.currentProfile();
        
        if (updatedItems == null || updatedItems.isEmpty()) {
            log.debug("Received empty item list from team storage");
            
            // Check if we currently have items - if so, don't replace with empty
            if (!items.isEmpty()) {
                log.info("[PROFILE_DEBUG] Ignoring empty update when we have {} existing items", items.size());
                return;
            }
            
            return;
        }

        log.info("Updating items from team storage: {} items", updatedItems.size());
        
        // Execute on the client thread to ensure thread safety
        clientThread.invokeLater(() -> {
            // Check if the profile has changed since this update was triggered
            if (!currentProfile.equals(config.currentProfile())) {
                log.info("Ignoring Firebase update because profile has changed from {} to {}", 
                    currentProfile, config.currentProfile());
                return;
            }

            // Create a map of existing items by name for preserving group information
            Map<String, BingoItem> existingItemsByName = new HashMap<>();
            for (BingoItem item : items) {
                existingItemsByName.put(item.getName().toLowerCase(), item);
            }

            // Clear existing items
            items.clear();
            itemsByName.clear();

            // Add the updated items
            for (BingoItem item : updatedItems) {
                // Check if this was a group item in our existing items
                BingoItem existingItem = existingItemsByName.get(item.getName().toLowerCase());
                if (existingItem != null && existingItem.isGroup()) {
                    // Preserve group information
                    item.setGroup(true);
                    item.setAlternativeNames(existingItem.getAlternativeNames());
                    
                    // If the item ID is -1 but the existing item had an ID, use that
                    if (item.getItemId() == -1 && existingItem.getItemId() > 0) {
                        item.setItemId(existingItem.getItemId());
                    }
                } else {
                    // Check if this might be a group item based on its name
                    detectAndProcessGroupItem(item);
                }
                
                // If the item ID is still -1, try to resolve it
                if (item.getItemId() == -1) {
                    resolveItemId(item);
                }

                items.add(item);
                itemsByName.put(item.getName().toLowerCase(), item);
            }

            // Load obtained status
            loadSavedItems();

            // Update the UI
            SwingUtilities.invokeLater(() -> {
                Optional.ofNullable(panel).ifPresent(p -> {
                    p.updateItems(items);
                    p.updateSourceWarningLabel();
                });
            });
        });
    }
    
    /**
     * Detects if an item is a group item based on its name and processes it accordingly
     *
     * @param item The item to check and process
     */
    private void detectAndProcessGroupItem(BingoItem item) {
        // Check if the name contains a slash, which indicates it might be a group item
        // (e.g., "Item1 / Item2 / Item3")
        if (item.getName().contains("/")) {
            log.debug("Detected potential group item from name: {}", item.getName());
            
            // Split the name by slashes and create alternative names
            String[] parts = item.getName().split("/");
            List<String> alternativeNames = new ArrayList<>();
            
            for (int i = 1; i < parts.length; i++) {
                String part = parts[i].trim();
                if (!part.isEmpty()) {
                    alternativeNames.add(part);
                }
            }
            
            if (!alternativeNames.isEmpty()) {
                item.setGroup(true);
                item.setAlternativeNames(alternativeNames);
                
                // Try to resolve the item ID from the first part
                String firstPart = parts[0].trim();
                if (!firstPart.isEmpty()) {
                    List<ItemPrice> results = itemManager.search(firstPart);
                    if (!results.isEmpty()) {
                        item.setItemId(results.get(0).getId());
                    }
                }
            }
        }
    }

    /**
     * Resolves the item ID for an item
     *
     * @param item The item to resolve
     */
    private void resolveItemId(BingoItem item) {
        String itemName = item.getName();

        try {
            // Try to find the item in the item manager
            List<ItemPrice> results = itemManager.search(itemName);
            if (!results.isEmpty()) {
                // Use the first result
                ItemPrice result = results.get(0);
                item.setItemId(result.getId());
                log.debug("Resolved item ID for {}: {}", itemName, result.getId());
            } else {
                // Try a more flexible search
                String searchTerm = itemName.replaceAll("[^a-zA-Z0-9 ]", "").trim();
                results = itemManager.search(searchTerm);
                if (!results.isEmpty()) {
                    // Use the first result
                    ItemPrice result = results.get(0);
                    item.setItemId(result.getId());
                    log.debug("Resolved item ID for {} using flexible search: {}", itemName, result.getId());
                } else {
                    log.debug("Could not resolve item ID for: {}", itemName);
                }
            }
        } catch (Exception e) {
            log.error("Error resolving item ID for {}: {}", itemName, e.getMessage());
        }
    }

    /**
     * Handles when an item is obtained.
     * If in team mode, updates the team service.
     *
     * @param obtainedItem The obtained item
     */
    private void notifyItemObtained(ObtainedItem obtainedItem) {
        if (obtainedItem == null) {
            return;
        }

        BingoItem bingoItem = obtainedItem.getBingoItem();
        int index = obtainedItem.getIndex();
        String itemName = bingoItem.getName();

        // Mark the item as obtained
        bingoItem.setObtained(true);

        // Update the UI
        updateUI();

        // Check for row/column/board completions
        checkForCompletions(index);

        // If in team mode, update the team service
        BingoConfig.BingoMode bingoMode = profileManager.getProfileBingoMode();
        if (bingoMode == BingoConfig.BingoMode.TEAM) {
            String teamCode = profileManager.getProfileTeamCode();
            if (teamCode != null && !teamCode.isEmpty()) {
                teamService.updateItemObtained(teamCode, itemName, true);
            }
        }

        // Send Discord notification
        String message = "Obtained bingo item: " + itemName;

        // Always capture screenshot
        captureScreenshotAsync(screenshot -> {
            discordNotifier.sendNotification(
                    message,
                    screenshot,
                    false,
                    profileManager.getProfileDiscordWebhook(),
                    executor
            );
        });

        // Show chat message only if enabled
        if (shouldShowChatNotifications()) {
            client.addChatMessage(
                    ChatMessageType.GAMEMESSAGE,
                    "",
                    "Bingo: Obtained item " + itemName,
                    null
            );
        }
    }

    /**
     * Captures a screenshot asynchronously and calls the callback with the result
     *
     * @param callback The callback to call with the captured screenshot (or null if disabled/failed)
     */
    private void captureScreenshotAsync(Consumer<BufferedImage> callback) {
        if (client.getGameState() == GameState.LOGIN_SCREEN) {
            log.info("Login screen screenshot prevented");
            callback.accept(null);
            return;
        }

        // Use RuneLite's built-in DrawManager to capture the screenshot
        drawManager.requestNextFrameListener(image -> {
            executor.submit(() -> {
                try {
                    if (image == null) {
                        log.warn("Received null image from drawManager");
                        callback.accept(null);
                        return;
                    }

                    BufferedImage screenshot = new BufferedImage(
                            image.getWidth(null),
                            image.getHeight(null),
                            BufferedImage.TYPE_INT_ARGB);

                    Graphics graphics = screenshot.getGraphics();
                    graphics.drawImage(image, 0, 0, null);
                    graphics.dispose();

                    log.debug("Screenshot captured successfully: {}x{}",
                            screenshot.getWidth(), screenshot.getHeight());

                    callback.accept(screenshot);
                } catch (Exception e) {
                    log.warn("Exception while processing screenshot image", e);
                    callback.accept(null);
                }
            });
        });
    }

    /**
     * Checks for completions in the bingo board based on the last obtained item
     *
     * @param obtainedItemIndex The index of the last obtained item
     */
    private void checkForCompletions(int obtainedItemIndex) {
        int row = obtainedItemIndex / GRID_SIZE;
        int col = obtainedItemIndex % GRID_SIZE;

        if (isRowComplete(row)) {
            notifyCompletion(CompletionType.ROW, row);
        }

        if (isColumnComplete(col)) {
            notifyCompletion(CompletionType.COLUMN, col);
        }

        boolean onDiagonal1 = (row == col);
        if (onDiagonal1 && isDiagonal1Complete()) {
            notifyCompletion(CompletionType.DIAGONAL1, 0);
        }

        boolean onDiagonal2 = (row + col == GRID_SIZE - 1);
        if (onDiagonal2 && isDiagonal2Complete()) {
            notifyCompletion(CompletionType.DIAGONAL2, 0);
        }

        if (isFullBoardComplete()) {
            notifyCompletion(CompletionType.FULL, 0);
        }
    }

    /**
     * Checks if the full board is complete
     *
     * @return True if all items are obtained
     */
    private boolean isFullBoardComplete() {
        for (BingoItem item : items) {
            if (!item.isObtained()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks if a row is complete
     *
     * @param row The row to check
     * @return True if the row is complete
     */
    private boolean isRowComplete(int row) {
        for (int c = 0; c < GRID_SIZE; c++) {
            int index = row * GRID_SIZE + c;
            if (index >= items.size() || !items.get(index).isObtained()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks if a column is complete
     *
     * @param col The column to check
     * @return True if the column is complete
     */
    private boolean isColumnComplete(int col) {
        for (int r = 0; r < GRID_SIZE; r++) {
            int index = r * GRID_SIZE + col;
            if (index >= items.size() || !items.get(index).isObtained()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks if the main diagonal (top-left to bottom-right) is complete
     *
     * @return True if the diagonal is complete
     */
    private boolean isDiagonal1Complete() {
        for (int i = 0; i < GRID_SIZE; i++) {
            int index = i * GRID_SIZE + i;
            if (index >= items.size() || !items.get(index).isObtained()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks if the other diagonal (top-right to bottom-left) is complete
     *
     * @return True if the diagonal is complete
     */
    private boolean isDiagonal2Complete() {
        for (int i = 0; i < GRID_SIZE; i++) {
            int index = i * GRID_SIZE + (GRID_SIZE - 1 - i);
            if (index >= items.size() || !items.get(index).isObtained()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Notifies about a completion (row, column, diagonal, or full board).
     *
     * @param completionType  The type of completion
     * @param completionIndex The index of the completed row/column
     */
    private void notifyCompletion(CompletionType completionType, int completionIndex) {
        if (!config.completionNotifications()) {
            return;
        }

        String message = getCompletionMessage(completionType, completionIndex);

        // Send Discord notification
        captureScreenshotAsync(screenshot -> {
            discordNotifier.sendNotification(
                    message,
                    screenshot,
                    true,
                    profileManager.getProfileDiscordWebhook(),
                    executor
            );
        });

        // Show chat message only if enabled
        if (shouldShowChatNotifications()) {
            client.addChatMessage(
                    ChatMessageType.GAMEMESSAGE,
                    "",
                    "Bingo: " + message,
                    null
            );
        }
    }

    /**
     * Reloads items based on the current profile
     */
    public void reloadItems() {
        String currentProfile = config.currentProfile();
        log.info("Reloading items for profile: {}", currentProfile);
        
        // Clear existing items
        items.clear();
        itemsByName.clear();
        
        // Get the current profile's bingo mode
        BingoConfig.BingoMode bingoMode = profileManager.getProfileBingoMode();
        log.info("Current profile bingo mode: {}", bingoMode);
        
        // If this is a team profile, register a team listener to receive updates
        if (bingoMode == BingoConfig.BingoMode.TEAM) {
            String teamCode = profileManager.getProfileTeamCode();
            if (teamCode != null && !teamCode.isEmpty()) {
                log.info("Registering team listener for team: {}", teamCode);
                
                // IMPORTANT: Check for cached items before registering the team listener
                // This ensures we have items to display immediately during profile switching
                List<BingoItem> cachedItems = teamService.getTeamCachedItems(teamCode);
                if (cachedItems != null && !cachedItems.isEmpty()) {
                    log.info("[PROFILE_DEBUG] Using {} cached items immediately for team {}", 
                        cachedItems.size(), teamCode);
                    
                    // Add the cached items
                    for (BingoItem item : cachedItems) {
                        items.add(item);
                        itemsByName.put(item.getName().toLowerCase(), item);
                    }
                    
                    // Update the UI immediately with cached items
                    SwingUtilities.invokeLater(() -> {
                        Optional.ofNullable(panel).ifPresent(p -> {
                            p.updateItems(items);
                            p.updateSourceWarningLabel();
                        });
                    });
                }
                
                // Use a static boolean to track if we're in the middle of a refresh for this team/profile combo
                final String profileTeamKey = currentProfile + ":" + teamCode;
                
                // Register a team listener
                teamService.registerTeamListener(teamCode, updatedItems -> {
                    // Only process updates if we're still on the same profile
                    if (!currentProfile.equals(config.currentProfile())) {
                        log.info("Ignoring team items update because profile has changed");
                        return;
                    }
                    
                    log.info("Received team items update: {} items", updatedItems.size());
                    
                    // Update our items
                    clientThread.invokeLater(() -> {
                        // Skip empty updates if we already have cached items
                        if (updatedItems.isEmpty() && !items.isEmpty()) {
                            log.info("[PROFILE_DEBUG] Skipping empty update - keeping existing {} items", 
                                items.size());
                            return;
                        }
                        
                        // Clear existing items
                        items.clear();
                        itemsByName.clear();
                        
                        // Add the updated items
                        for (BingoItem item : updatedItems) {
                            items.add(item);
                            itemsByName.put(item.getName().toLowerCase(), item);
                        }
                        
                        // Update the UI
                        SwingUtilities.invokeLater(() -> {
                            Optional.ofNullable(panel).ifPresent(p -> p.updateItems(items));
                        });
                    });
                });
                
                // Return early - the team listener will handle loading items
                return;
            }
        }

        // For non-team profiles or team profiles without a team code, load items based on the item source type
        loadItems();

        // Update the UI
        SwingUtilities.invokeLater(() -> {
            Optional.ofNullable(panel).ifPresent(p -> {
                p.updateItems(items);
                p.updateSourceWarningLabel();
                p.updateButtonVisibility();
            });
        });
    }

    /**
     * Loads items based on the current profile's item source type
     */
    private void loadItems() {
        // Get the item source type
        BingoConfig.ItemSourceType itemSourceType = profileManager.getProfileItemSourceType();

        log.debug("Loading items with source type: {}", itemSourceType);

        // Ensure we display correct source in UI regardless of empty state
        SwingUtilities.invokeLater(() -> {
            Optional.ofNullable(panel).ifPresent(BingoPanel::updateSourceWarningLabel);
        });

        // Always clear existing items first to prevent mixing
        items.clear();
        itemsByName.clear();

        // Load items based on the source type
        if (itemSourceType == BingoConfig.ItemSourceType.REMOTE) {
            // Get the remote URL
            String remoteUrl = profileManager.getProfileRemoteUrl();
            if (remoteUrl != null && !remoteUrl.isEmpty()) {
                // Schedule a remote update
                updateRemoteItems();
            } else {
                // Remote URL is empty, just leave items empty
                log.warn("No remote URL configured for REMOTE source type. Items will remain empty.");
                
                // Update UI to show empty grid
                SwingUtilities.invokeLater(() -> {
                    Optional.ofNullable(panel).ifPresent(p -> p.updateItems(items));
                });
            }
        } else {
            // Load items from the manual list
            String itemList = profileManager.getProfileItemList();
            if (itemList != null && !itemList.isEmpty()) {
                loadItemsFromManualList();
            } else {
                log.warn("No manual items configured");
                
                // Update UI to show empty grid
                SwingUtilities.invokeLater(() -> {
                    Optional.ofNullable(panel).ifPresent(p -> p.updateItems(items));
                });
            }
        }
    }

    /**
     * Loads items from the manual item list.
     */
    private void loadItemsFromManualList() {
        // Clear existing items
        items.clear();
        itemsByName.clear();
        
        // Get the item list
        String itemList = profileManager.getProfileItemList();
        if (itemList == null || itemList.isEmpty()) {
            log.debug("No manual item list found");
            return;
        }
        
        // Split the item list by newlines
        List<String> itemNames = Arrays.asList(itemList.split("\\r?\\n"));
        
        // Trim the list to the maximum number of items
        if (itemNames.size() > MAX_ITEMS) {
            log.warn("Item list contains more than {} items, truncating", MAX_ITEMS);
            itemNames = itemNames.subList(0, MAX_ITEMS);
        }
        
        // Process each item
        for (String itemLine : itemNames) {
            itemLine = itemLine.trim();
            if (itemLine.isEmpty()) {
                continue;
            }
            
            // Check if this is an item group (contains colons)
            if (itemLine.contains(":")) {
                // This is an item group
                BingoItemGroup itemGroup = BingoItemGroup.fromString(itemLine);
                BingoItem groupItem = BingoItem.fromItemGroup(itemGroup, itemManager);
                
                // Add the group item to our lists
                items.add(groupItem);
                itemsByName.put(groupItem.getName().toLowerCase(), groupItem);
                
                // Also add each individual item name to the itemsByName map for lookup
                for (String individualName : itemGroup.getItemNames()) {
                    if (!individualName.equals(groupItem.getName())) {
                        itemsByName.put(individualName.toLowerCase(), groupItem);
                    }
                }
                
                log.debug("Added item group: {} with alternatives: {}", 
                    groupItem.getName(), String.join(", ", groupItem.getAlternativeNames()));
            } else {
                // This is a regular item
                // Try to find the item in the item manager
                List<ItemPrice> results = itemManager.search(itemLine);
                if (!results.isEmpty()) {
                    // Use the first result
                    ItemPrice result = results.get(0);
                    BingoItem item = new BingoItem(itemLine, result.getId());
                    items.add(item);
                    itemsByName.put(itemLine.toLowerCase(), item);
                } else {
                    // Item not found, add it with a placeholder ID
                    BingoItem item = new BingoItem(itemLine, -1);
                    items.add(item);
                    itemsByName.put(itemLine.toLowerCase(), item);
                }
            }
        }
        
        // Load obtained status
        loadSavedItems();
    }

    /**
     * Loads saved obtained items from the configuration.
     */
    public void loadSavedItems() {
        // For team profiles with persistObtained enabled, the obtained status
        // is already loaded from Firebase, so we don't need to load from config
        if (profileManager.getProfileBingoMode() == BingoConfig.BingoMode.TEAM &&
                profileManager.getProfilePersistObtained()) {
            log.debug("Not loading obtained items from config as this is a team profile with persistObtained enabled");
            return;
        }

        forceLoadSavedItems();
    }

    /**
     * Forces loading of saved items regardless of persistObtained setting.
     * Used during profile switching to ensure items are loaded correctly.
     */
    public void forceLoadSavedItems() {
        // Generate the profile key correctly
        String profileName = config.currentProfile();
        if (profileName == null || profileName.isEmpty()) {
            log.error("Cannot load obtained items: current profile is null or empty");
            return;
        }
        
        String profileKey = profileName + "." + CONFIG_KEY_OBTAINED_ITEMS;
        log.debug("Loading obtained items from config key: {}", profileKey);
        
        String savedItems = configManager.getConfiguration(CONFIG_GROUP, profileKey);
        if (savedItems == null || savedItems.isEmpty()) {
            log.debug("No saved items found for profile: {}", profileName);
            return;
        }

        Arrays.stream(savedItems.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(name -> {
                    // First try to find the item by exact name
                    BingoItem item = itemsByName.get(name.toLowerCase());
                    if (item != null) {
                        item.setObtained(true);
                        log.debug("Loaded obtained item: {}", name);
                    } else {
                        // If not found, try to match against all items including alternative names
                        for (BingoItem bingoItem : items) {
                            if (bingoItem.matchesName(name)) {
                                bingoItem.setObtained(true);
                                log.debug("Loaded obtained item (via alternative name): {}", name);
                                break;
                            }
                        }
                    }
                });
    }

    /**
     * Saves obtained items to the configuration.
     */
    public void saveObtainedItems() {
        // If persistObtained is true, we don't need to save the items
        // as they are already persisted in the team data
        if (profileManager.getProfileBingoMode() == BingoConfig.BingoMode.TEAM && 
            profileManager.getProfilePersistObtained()) {
            log.debug("Not saving obtained items to config as persistObtained is true");
            return;
        }

        String obtainedItems = items.stream()
                .filter(BingoItem::isObtained)
                .map(BingoItem::getName)
                .collect(Collectors.joining(","));

        // Generate the profile key correctly
        String profileName = config.currentProfile();
        if (profileName == null || profileName.isEmpty()) {
            log.error("Cannot save obtained items: current profile is null or empty");
            return;
        }
        
        String profileKey = profileName + "." + CONFIG_KEY_OBTAINED_ITEMS;
        log.debug("Saving obtained items to config key: {}", profileKey);
        configManager.setConfiguration(CONFIG_GROUP, profileKey, obtainedItems);
        log.debug("Saved {} obtained items to config", obtainedItems.isEmpty() ? 0 : obtainedItems.split(",").length);
    }

    /**
     * Clears all obtained items.
     */
    public void clearObtainedItems() {
        for (BingoItem item : items) {
            item.setObtained(false);
        }
    }

    /**
     * Clear Aquisition Log items
     */
    public void clearAcquisitionItems() {
        for (BingoItem item : items) {
            antiCheat.clearAcquisitionLog();
        }
    }

    /**
     * Updates the UI to reflect the current state.
     */
    public void updateUI() {
        if (panel != null) {
            SwingUtilities.invokeLater(() -> panel.updateItems(items));
        }
    }

    /**
     * Resets the bingo board.
     *
     * @param showConfirmDialog Whether to show a confirmation dialog
     * @return true if the board was reset, false if the user cancelled
     */
    public boolean resetBingoBoard(boolean showConfirmDialog) {
        if (showConfirmDialog) {
            int result = javax.swing.JOptionPane.showConfirmDialog(
                    panel,
                    "Are you sure you want to reset the bingo board?\nAll progress will be deleted.",
                    "Reset Bingo Board",
                    javax.swing.JOptionPane.YES_NO_OPTION,
                    javax.swing.JOptionPane.WARNING_MESSAGE
            );

            if (result != javax.swing.JOptionPane.YES_OPTION) {
                return false;
            }
        }

        String profileKey = profileManager.getCurrentProfileKey(CONFIG_KEY_OBTAINED_ITEMS);
        if (profileKey != null) {
            configManager.unsetConfiguration(CONFIG_GROUP, profileKey);
        }

        clearObtainedItems();
        clearAcquisitionItems();
        updateUI();

        if (client.getLocalPlayer() != null && shouldShowChatNotifications()) {
            client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                    "Bingo board has been reset.", "");
        }

        log.debug("Bingo board reset for profile: {}", config.currentProfile());
        return true;
    }

    /**
     * Resets the bingo board with confirmation dialog.
     */
    public void resetBingoBoard() {
        resetBingoBoard(true);
    }

    /**
     * Updates items from a remote URL
     */
    private void updateRemoteItems() {
        String currentProfile = config.currentProfile();
        String remoteUrl = profileManager.getProfileRemoteUrl();
        log.info("Updating items from remote URL: {}", remoteUrl);

        if (remoteUrl == null || remoteUrl.isEmpty()) {
            log.warn("Remote URL is empty");
            return;
        }
        
        // Aggressively clear items before fetching remote URL
        // This ensures we don't show manual items while waiting for the remote URL to load
        items.clear();
        itemsByName.clear();
        
        // Update UI immediately to show empty grid
        SwingUtilities.invokeLater(() -> {
            Optional.ofNullable(panel).ifPresent(p -> p.updateItems(items));
        });

        // Handle Pastebin URLs
        if (remoteUrl.contains("pastebin.com")) {
            // Convert pastebin.com/xyz to pastebin.com/raw/xyz
            if (!remoteUrl.contains("/raw/")) {
                remoteUrl = remoteUrl.replace("pastebin.com/", "pastebin.com/raw/");
                log.info("Converted Pastebin URL to raw: {}", remoteUrl);
            }
        }

        // Create the request
        Request request = new Request.Builder()
                .url(remoteUrl)
                .get()
                .build();

        // Execute the request
        log.info("Sending request to remote URL: {}", remoteUrl);
        okHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.error("Failed to fetch items from remote URL: {}", e.getMessage(), e);

                // Don't fall back to manual items, just report the error
                clientThread.invokeLater(() -> {
                    // Check if the profile has changed
                    if (!currentProfile.equals(config.currentProfile())) {
                        log.info("Ignoring remote URL failure because profile has changed");
                        return;
                    }
                    
                    log.info("Remote URL fetch failed, not falling back to manual items");
                    SwingUtilities.invokeLater(() -> {
                        Optional.ofNullable(panel).ifPresent(p -> {
                            // Just update UI with current (likely empty) items
                            p.updateItems(items);
                            // Maybe show an error indicator in the UI
                            JOptionPane.showMessageDialog(p, 
                                "Failed to fetch items from remote URL: " + e.getMessage(), 
                                "Remote URL Error", 
                                JOptionPane.ERROR_MESSAGE);
                        });
                    });
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    if (response.isSuccessful() && responseBody != null) {
                        String content = responseBody.string();
                        log.info("Received content from remote URL (length: {})", content.length());
                        if (content.length() < 100) {
                            log.info("Content preview: {}", content);
                        }

                        List<String> lines = Arrays.asList(content.split("\\r?\\n"));
                        log.info("Parsed {} lines from remote URL", lines.size());

                        // Process the items on the client thread
                        clientThread.invokeLater(() -> {
                            // Check if the profile has changed
                            if (!currentProfile.equals(config.currentProfile())) {
                                log.info("Ignoring remote URL response because profile has changed");
                                return;
                            }
                            
                            updateItemsFromList(lines);
                            SwingUtilities.invokeLater(() -> {
                                Optional.ofNullable(panel).ifPresent(p -> p.updateItems(items));
                            });
                        });
                    } else {
                        log.error("Failed to fetch items from remote URL: HTTP {}", response.code());
                        if (responseBody != null) {
                            log.error("Response body: {}", responseBody.string());
                        }

                        // Don't fall back to manual items, just report the error
                        clientThread.invokeLater(() -> {
                            // Check if the profile has changed
                            if (!currentProfile.equals(config.currentProfile())) {
                                log.info("Ignoring remote URL failure because profile has changed");
                                return;
                            }
                            
                            log.info("Remote URL HTTP error, not falling back to manual items");
                            SwingUtilities.invokeLater(() -> {
                                Optional.ofNullable(panel).ifPresent(p -> {
                                    // Just update UI with current (likely empty) items
                                    p.updateItems(items);
                                    // Show an error indicator in the UI
                                    JOptionPane.showMessageDialog(p, 
                                        "Failed to fetch items from remote URL: HTTP " + response.code(), 
                                        "Remote URL Error", 
                                        JOptionPane.ERROR_MESSAGE);
                                });
                            });
                        });
                    }
                } catch (Exception e) {
                    log.error("Error parsing remote URL content: {}", e.getMessage(), e);

                    // Don't fall back to manual items, just report the error
                    clientThread.invokeLater(() -> {
                        // Check if the profile has changed
                        if (!currentProfile.equals(config.currentProfile())) {
                            log.info("Ignoring remote URL parsing error because profile has changed");
                            return;
                        }
                        
                        log.info("Remote URL parsing error, not falling back to manual items");
                        SwingUtilities.invokeLater(() -> {
                            Optional.ofNullable(panel).ifPresent(p -> {
                                // Just update UI with current (likely empty) items
                                p.updateItems(items);
                                // Show an error indicator in the UI
                                JOptionPane.showMessageDialog(p, 
                                    "Error parsing remote URL content: " + e.getMessage(), 
                                    "Remote URL Error", 
                                    JOptionPane.ERROR_MESSAGE);
                            });
                        });
                    });
                }
            }
        });
    }

    /**
     * Updates items from a list of item names
     *
     * @param remoteItems The list of item names
     */
    private void updateItemsFromList(List<String> remoteItems) {
        log.debug("Updating items from list: {} items", remoteItems.size());

        // Clear existing items
        items.clear();
        itemsByName.clear();

        // Filter out empty items
        remoteItems = remoteItems.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());

        // Limit to MAX_ITEMS
        if (remoteItems.size() > MAX_ITEMS) {
            log.warn("Too many items in list ({}), limiting to {}", remoteItems.size(), MAX_ITEMS);
            remoteItems = remoteItems.subList(0, MAX_ITEMS);
        }

        // Process each item
        for (String itemName : remoteItems) {
            // Try to find the item in the item manager
            List<ItemPrice> results = itemManager.search(itemName);
            if (!results.isEmpty()) {
                // Use the first result
                ItemPrice result = results.get(0);
                BingoItem item = new BingoItem(itemName, result.getId());
                items.add(item);
                itemsByName.put(itemName.toLowerCase(), item);
                log.debug("Added item with ID: {} - {}", result.getId(), itemName);
            } else {
                // Try a more flexible search
                String searchTerm = itemName.replaceAll("[^a-zA-Z0-9 ]", "").trim();
                results = itemManager.search(searchTerm);
                if (!results.isEmpty()) {
                    // Use the first result
                    ItemPrice result = results.get(0);
                    BingoItem item = new BingoItem(itemName, result.getId());
                    items.add(item);
                    itemsByName.put(itemName.toLowerCase(), item);
                    log.debug("Added item with ID (flexible search): {} - {}", result.getId(), itemName);
                } else {
                    // Item not found, add it with a placeholder ID
                    BingoItem item = new BingoItem(itemName, -1);
                    items.add(item);
                    itemsByName.put(itemName.toLowerCase(), item);
                    log.debug("Added item without ID: {}", itemName);
                }
            }
        }

        // Load obtained status
        loadSavedItems();
    }

    /**
     * Manually triggers an update from the remote URL
     */
    public void updateRemoteItemsManually() {
        updateRemoteItems();
    }

    /**
     * Gets the panel
     *
     * @return The panel
     */
    public Optional<BingoPanel> getPanel() {
        return Optional.ofNullable(panel);
    }

    /**
     * Toggles the obtained status of an item
     *
     * @param index The index of the item
     */
    public void toggleItemObtained(int index) {
        if (index < 0 || index >= items.size()) {
            return;
        }
        
        BingoItem item = items.get(index);
        
        // Don't allow manually toggling group items
        if (item.isGroup()) {
            log.debug("Attempted to toggle a group item: {}", item.getName());
            return;
        }
        
        boolean newStatus = !item.isObtained();
        item.setObtained(newStatus);
        
        // Save the obtained status
        saveObtainedItems();
        
        // For team profiles, update the item in the team storage
        BingoConfig.BingoMode bingoMode = profileManager.getProfileBingoMode();
        if (bingoMode == BingoConfig.BingoMode.TEAM) {
            String teamCode = profileManager.getProfileTeamCode();
            if (teamCode != null && !teamCode.isEmpty()) {
                log.info("Updating item obtained status for team {}: {} = {}", 
                    teamCode, item.getName(), newStatus);
                
                // Use a background thread to avoid blocking the UI
                executor.submit(() -> {
                    try {
                        boolean success = teamService.updateItemObtained(teamCode, item.getName(), newStatus)
                            .get(10, TimeUnit.SECONDS); // Wait up to 10 seconds for the update to complete
                        
                        if (success) {
                            log.info("Successfully updated item obtained status in team storage");
                            
                            // If this is a group item, also update the UI for all items in the group
                            if (item.isGroup() && item.getAlternativeNames() != null) {
                                SwingUtilities.invokeLater(() -> {
                                    for (int i = 0; i < items.size(); i++) {
                                        BingoItem otherItem = items.get(i);
                                        if (otherItem != item && item.matchesName(otherItem.getName())) {
                                            otherItem.setObtained(newStatus);
                                        }
                                    }
                                    panel.updateGrid();
                                });
                            }
                        } else {
                            log.error("Failed to update item obtained status in team storage");
                            
                            // Try again with a direct call
                            log.info("Trying again with a direct call...");
                            success = teamService.updateItemObtained(teamCode, item.getName(), newStatus)
                                .get(10, TimeUnit.SECONDS);
                            
                            if (success) {
                                log.info("Successfully updated item obtained status in team storage on second attempt");
                            } else {
                                log.error("Failed to update item obtained status in team storage on second attempt");
                            }
                        }
                    } catch (InterruptedException | ExecutionException | TimeoutException e) {
                        log.error("Error waiting for team storage update to complete", e);
                    }
                });
            }
        }
        
        // Update the UI
        SwingUtilities.invokeLater(() -> {
            Optional.ofNullable(panel).ifPresent(p -> p.updateItems(items));
        });
    }

    /**
     * Converts a team profile to a solo profile
     * This is useful when offline mode is enabled and team synchronization won't work
     *
     * @param profileName The profile name
     * @return A CompletableFuture that resolves to a boolean indicating success
     */
    public CompletableFuture<Boolean> convertTeamToSoloProfile(String profileName) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        
        // Get the current profile settings
        BingoConfig.ItemSourceType itemSourceType = profileManager.getProfileItemSourceType();
        String remoteUrl = profileManager.getProfileRemoteUrl();
        String manualItems = profileManager.getProfileItemList();
        int refreshInterval = profileManager.getProfileRefreshInterval();
        boolean persistObtained = profileManager.getProfilePersistObtained();
        
        // Create a temporary profile
        String tempProfileName = profileName + "_temp";
        boolean created = profileManager.createProfile(tempProfileName);
        
        if (!created) {
            future.complete(false);
            return future;
        }
        
        // Copy settings to the temporary profile
        profileManager.setProfileItemSourceType(itemSourceType);
        profileManager.setProfileRemoteUrl(remoteUrl);
        profileManager.setProfileItemList(manualItems);
        profileManager.setProfileRefreshInterval(refreshInterval);
        profileManager.setProfilePersistObtained(persistObtained);
        
        // Delete the team profile
        boolean deleted = profileManager.deleteProfile(profileName);
        
        if (!deleted) {
            // Clean up the temporary profile
            profileManager.deleteProfile(tempProfileName);
            future.complete(false);
            return future;
        }
        
        // Rename the temporary profile to the original name
        boolean renamed = profileManager.createProfile(profileName);
        
        if (!renamed) {
            future.complete(false);
            return future;
        }
        
        // Copy settings to the renamed profile
        profileManager.setProfileItemSourceType(itemSourceType);
        profileManager.setProfileRemoteUrl(remoteUrl);
        profileManager.setProfileItemList(manualItems);
        profileManager.setProfileRefreshInterval(refreshInterval);
        profileManager.setProfilePersistObtained(persistObtained);
        
        // Clean up the temporary profile
        profileManager.deleteProfile(tempProfileName);
        
        // Switch to the renamed profile
        profileManager.switchProfile(profileName);
        
        future.complete(true);
        return future;
    }

    /**
     * Manually refreshes team items from the remote URL
     */
    public void refreshTeamItems() {
        // Check if we're in a team profile
        if (profileManager.getProfileBingoMode() != BingoConfig.BingoMode.TEAM) {
            log.warn("Cannot refresh team items: not in a team profile");
            if (client.getLocalPlayer() != null && shouldShowChatNotifications()) {
                client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                        "Cannot refresh team items: not in a team profile.", "");
            }
            return;
        }
        
        // Get the team code
        String teamCode = profileManager.getProfileTeamCode();
        if (teamCode == null || teamCode.isEmpty()) {
            log.warn("Cannot refresh team items: no team code");
            if (client.getLocalPlayer() != null && shouldShowChatNotifications()) {
                client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                        "Cannot refresh team items: no team code.", "");
            }
            return;
        }
        
        // Check if the profile is using a remote URL
        if (profileManager.getProfileItemSourceType() != BingoConfig.ItemSourceType.REMOTE) {
            log.warn("Cannot refresh team items: profile is not using a remote URL");
            if (client.getLocalPlayer() != null && shouldShowChatNotifications()) {
                client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                        "Cannot refresh team items: profile is not using a remote URL.", "");
            }
            return;
        }
        
        // Get the remote URL
        String remoteUrl = profileManager.getProfileRemoteUrl();
        if (remoteUrl == null || remoteUrl.isEmpty()) {
            log.warn("Cannot refresh team items: no remote URL configured");
            if (client.getLocalPlayer() != null && shouldShowChatNotifications()) {
                client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                        "Cannot refresh team items: no remote URL configured.", "");
            }
            return;
        }
        
        log.info("Manually refreshing team items for team: {}", teamCode);
        if (client.getLocalPlayer() != null && shouldShowChatNotifications()) {
            client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                    "Refreshing team items from remote URL...", "");
        }
        
        // Use a background thread to avoid blocking the UI
        executor.submit(() -> {
            try {
                boolean success = teamService.refreshTeamItems(teamCode)
                    .get(30, TimeUnit.SECONDS); // Wait up to 30 seconds for the refresh to complete
                
                if (success) {
                    log.info("Successfully refreshed team items from remote URL");
                    if (client.getLocalPlayer() != null && shouldShowChatNotifications()) {
                        client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                                "Successfully refreshed team items from remote URL.", "");
                    }
                    
                    // Reload items
                    clientThread.invokeLater(() -> {
                        reloadItems();
                    });
                } else {
                    log.error("Failed to refresh team items from remote URL");
                    if (client.getLocalPlayer() != null && shouldShowChatNotifications()) {
                        client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                                "Failed to refresh team items from remote URL.", "");
                    }
                }
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                log.error("Error refreshing team items", e);
                if (client.getLocalPlayer() != null && shouldShowChatNotifications()) {
                    client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                            "Error refreshing team items: " + e.getMessage(), "");
                }
            }
        });
    }

    /**
     * Clears all items and item maps.
     * Used when switching profiles to ensure no items are carried over.
     */
    public void clearItems() {
        log.debug("Clearing all items");
        items.clear();
        itemsByName.clear();
    }

    /**
     * Checks if chat notifications should be shown
     *
     * @return True if chat notifications should be shown
     */
    private boolean shouldShowChatNotifications() {
        return config.showChatNotifications();
    }

    public void onGameTick(GameTick event) {
        try {
            // ... existing code ...
        } catch (Exception e) {
            log.error("Error during game tick", e);
        }
    }

    /**
     * Force a complete reload of items and refresh the UI.
     * This method clears all cached items and reloads them from scratch,
     * ensuring a fresh state when switching profiles.
     */
    public void forceReloadItems() {
        String currentProfile = config.currentProfile();
        log.info("Force reloading items for profile: {}", currentProfile);
        
        // Clear existing items first
        clearItems();
        
        // Reload all items
        reloadItems();
        
        // Load saved items (obtained status)
        loadSavedItems();
        
        // For team profiles, also refresh team items
        BingoConfig.BingoMode bingoMode = profileManager.getProfileBingoMode();
        if (bingoMode == BingoConfig.BingoMode.TEAM) {
            String teamCode = profileManager.getProfileTeamCode();
            if (teamCode != null && !teamCode.isEmpty()) {
                log.info("Refreshing team items for profile: {}, team: {}", currentProfile, teamCode);
                refreshTeamItems();
            }
        }
        
        // Update the UI
        updateUI();
        
        log.info("Completed force reload of items for profile: {}", currentProfile);
    }

    /**
     * Force a configuration update to ensure the profile switch takes effect
     * This is a critical method to ensure profile switching works correctly
     */
    public void forceConfigUpdate(String targetProfile) {
        if (targetProfile == null || targetProfile.isEmpty()) {
            log.error("Attempted to force config update with null/empty profile");
            return;
        }
        
        // Get the current config value
        String currentProfile = config.currentProfile();
        log.info("Force config update requested: current={}, target={}", currentProfile, targetProfile);
        
        if (targetProfile.equals(currentProfile)) {
            log.info("Config already showing correct profile: {}", currentProfile);
            return;
        }
        
        // Try direct config update with multiple approaches
        try {
            // Try EVERY possible config path to force the update
            String[] possibleGroups = {"bingo", "betterbingo", "bingo.profile", "betterBingo"};
            String[] possibleKeys = {"currentProfile", "profile", "bingoProfile"};
            
            log.info("Aggressively trying ALL possible config paths to update profile");
            
            // First, unset ALL possible combinations
            for (String group : possibleGroups) {
                for (String key : possibleKeys) {
                    try {
                        log.debug("Unsetting {}.{}", group, key);
                        configManager.unsetConfiguration(group, key);
                    } catch (Exception e) {
                        // Ignore errors during unset
                    }
                }
            }
            
            // Small delay
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            
            // Then set with ALL possible combinations
            for (String group : possibleGroups) {
                for (String key : possibleKeys) {
                    try {
                        log.debug("Setting {}.{} = {}", group, key, targetProfile);
                        configManager.setConfiguration(group, key, targetProfile);
                    } catch (Exception e) {
                        // Ignore errors during set
                    }
                }
            }
            
            // Direct reset via reflection (only attempt as a last resort)
            try {
                // This is a VERY aggressive approach that might work if all else fails
                // Attempt a direct reflection update to the ConfigManager's internal cache
                Field configMapField = configManager.getClass().getDeclaredField("configMap");
                configMapField.setAccessible(true);
                Object configMap = configMapField.get(configManager);
                
                // Get the map
                if (configMap instanceof Map) {
                    Map<String, Object> map = (Map<String, Object>) configMap;
                    
                    // Attempt direct cache manipulation
                    for (String group : possibleGroups) {
                        String key = group + ".currentProfile";
                        map.put(key, targetProfile);
                    }
                    
                    log.info("Attempted direct cache manipulation via reflection");
                }
            } catch (Exception e) {
                log.debug("Reflection manipulation failed (this is expected): {}", e.getMessage());
            }
            
            // Force a config reload by reading all config values
            log.info("Forcing config reload...");
            configManager.getConfiguration("bingo", "currentProfile");
            configManager.getConfiguration("betterbingo", "currentProfile");
            config.currentProfile(); // Force the config to reload
            
            // Small delay to ensure change propagates
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            
            // Check if it worked
            currentProfile = config.currentProfile();
            if (!targetProfile.equals(currentProfile)) {
                log.error("Config STILL showing wrong profile after extreme measures: {}", currentProfile);
                
                // As an absolute last resort, try to get the BingoConfig to update via a setter
                try {
                    Method setMethod = config.getClass().getMethod("setCurrentProfile", String.class);
                    setMethod.invoke(config, targetProfile);
                    log.info("Attempted direct setter invocation on BingoConfig");
                } catch (Exception e) {
                    log.debug("Direct setter invocation failed (this is expected): {}", e.getMessage());
                }
            } else {
                log.info("Config successfully updated to: {}", currentProfile);
            }
        } catch (Exception e) {
            log.error("Error during force config update", e);
        }
    }
}