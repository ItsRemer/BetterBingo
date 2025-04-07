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
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
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
import java.util.HashMap;

import java.io.IOException;

import java.util.concurrent.CompletableFuture;
import java.util.Optional;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;

/**
 * Bingo Plugin for RuneLite
 * Tracks items for bingo events and sends notifications to Discord
 * Supports loading items from Pastebin for easy sharing
 */
@Slf4j
@SuppressWarnings({"deprecation", "unchecked"})
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
    @Inject
    private ChatMessageManager chatMessageManager;

    @Getter
    private final List<BingoItem> items = new ArrayList<>();

    private final Map<String, BingoItem> itemsByName = new HashMap<>();
    private final Set<Integer> recentlyKilledNpcs = new HashSet<>();
    private BingoPanel panel;
    private NavigationButton navButton;

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
     * Extracts an item name from a string, handling quantity indicators and special cases
     *
     * @param rawItemName The raw item name to clean
     * @return The cleaned item name or null if invalid
     */
    private String cleanItemName(String rawItemName) {
        return BingoChatPatterns.cleanItemName(rawItemName);
    }

    @Override
    protected void startUp() {
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
                
                // Load items without clearing existing obtained items
                reloadItems();
                
                // Make sure we load saved obtained items from local config first
                // This is critical to ensure we don't overwrite local state with remote state
                loadSavedItems();
                // Add the panel to the sidebar if enabled in config
                if (config.showSidebar()) {
                    clientThread.invokeLater(() -> {
                        if (clientToolbar != null && navButton != null) {
                            clientToolbar.addNavigation(navButton);
                        }
                    });
                }

                // If the current profile is a team profile, set up the team listener
                BingoConfig.BingoMode bingoMode = profileManager.getProfileBingoMode();
                if (bingoMode == BingoConfig.BingoMode.TEAM) {
                    String teamCode = profileManager.getProfileTeamCode();
                    if (teamCode != null && !teamCode.isEmpty()) {
                        // Register a team listener to get updates
                        teamService.registerTeamListener(teamCode, this::updateItemsFromFirebase);
                        
                        // CRITICAL: Force a UI refresh from Firebase to ensure UI shows correct state
                        // This is needed because the UI might not reflect what's in Firebase on startup
                        clientThread.invokeLater(() -> {
                            try {
                                // Give Firebase a moment to connect
                                Thread.sleep(2000);
                                refreshUIFromFirebase();
                            } catch (InterruptedException e) {
                                log.error("Error during startup UI refresh", e);
                            }
                        });
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
                
                log.info("Bingo plugin initialization completed");
            } catch (Exception e) {
                log.error("Error during Bingo plugin initialization", e);
            }
        });
    }

    @Override
    protected void shutDown() {
        // Save obtained items before shutting down
        saveObtainedItems();
        
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
            clientThread.invokeLater(() -> {
                log.debug("Game state changed to LOGGING_IN, loading items and saved state");
                loadItems();
                loadSavedItems();
                updateUI(); // Ensure UI reflects the loaded state
            });
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

        for (BingoChatPatterns.ChatMessagePattern pattern : BingoChatPatterns.CHAT_PATTERNS) {
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
        itemName = cleanItemName(itemName);
        
        // Try to find the item in our list
        BingoItem bingoItem = itemsByName.get(itemName.toLowerCase());
        if (bingoItem == null) {
            for (Map.Entry<String, BingoItem> entry : itemsByName.entrySet()) {
                if (entry.getKey().contains(itemName.toLowerCase()) && 
                    (entry.getKey().startsWith(itemName.toLowerCase()) || 
                     entry.getKey().contains(" " + itemName.toLowerCase()))) {
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
            {
                if (bingoMode == BingoConfig.BingoMode.TEAM) {
                    String teamCode = profileManager.getProfileTeamCode();
                    if (teamCode != null && !teamCode.isEmpty()) {
                        try {
                            boolean success = teamService.updateItemObtained(teamCode, bingoItem.getName(), true)
                                    .get(10, TimeUnit.SECONDS);

                            {
                                if (success) {
                                    log.info("Successfully updated item obtained status in Firebase");
                                } else {
                                    log.error("Failed to update item obtained status in Firebase");

                                    log.info("Trying again with a direct call...");
                                }
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
        
        // Safety check for null or empty updates
        if (updatedItems == null || updatedItems.isEmpty()) {
            log.debug("Received empty item list from team storage");
            
            // Only ignore empty updates when we already have items
            if (!items.isEmpty()) {
                log.info("Ignoring empty update when we have {} existing items", items.size());
                return;
            }
            
            return;
        }

        // Execute on the client thread to ensure thread safety
        clientThread.invokeLater(() -> {
            // Check if the profile has changed since this update was triggered
            if (!currentProfile.equals(config.currentProfile())) {
                log.info("Ignoring Firebase update because profile has changed from {} to {}", 
                    currentProfile, config.currentProfile());
                return;
            }

            // Create a map of existing items by name for preserving group information and obtained status
            Map<String, BingoItem> existingItemsByName = new HashMap<>();
            for (BingoItem item : items) {
                existingItemsByName.put(item.getName().toLowerCase(), item);
            }

            // Get current obtained items from local config before clearing the list
            // This helps preserve obtained status during client restart
            String profileKey = currentProfile + "." + CONFIG_KEY_OBTAINED_ITEMS;
            String savedItemsStr = configManager.getConfiguration(CONFIG_GROUP, profileKey);
            Set<String> savedObtainedItems = new HashSet<>();
            if (savedItemsStr != null && !savedItemsStr.isEmpty()) {
                Arrays.stream(savedItemsStr.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .forEach(savedObtainedItems::add);
                log.debug("Found {} saved obtained items in config", savedObtainedItems.size());
            }

            // Clear existing items
            items.clear();
            itemsByName.clear();

            // Add the updated items
            for (BingoItem item : updatedItems) {
                // Check if the item was previously obtained in our config
                boolean wasObtainedLocally = savedObtainedItems.contains(item.getName());
                
                // Preserve obtained status if the item already existed
                BingoItem existingItem = existingItemsByName.get(item.getName().toLowerCase());
                if (existingItem != null) {
                    // If the item was already obtained in our existing data, keep it that way
                    // Unless the updated item specifically marks it as not obtained (team state takes priority)
                    if ((existingItem.isObtained() || wasObtainedLocally) && !item.isObtained()) {
                        // Only apply the existing obtained status if we're using local persistence
                        if (!profileManager.getProfilePersistObtained()) {
                            log.debug("Preserving obtained status for item: {}", item.getName());
                            item.setObtained(true);
                        }
                    }
                    
                    // Preserve group information if it exists
                    if (existingItem.isGroup() && !item.isGroup()) {
                        item.setGroup(true);
                        item.setAlternativeNames(existingItem.getAlternativeNames());
                    }
                } else if (wasObtainedLocally && !item.isObtained()) {
                    // Item wasn't in memory but was in saved config
                    if (!profileManager.getProfilePersistObtained()) {
                        log.debug("Setting obtained=true for item from config: {}", item.getName());
                        item.setObtained(true);
                    }
                }
                
                items.add(item);
                itemsByName.put(item.getName().toLowerCase(), item);
            }

            // Load any saved items (applicable for non-persistObtained team profiles)
            loadSavedItems();

            // Update the UI
            SwingUtilities.invokeLater(() -> {
                Optional.ofNullable(panel).ifPresent(p -> p.updateItems(new ArrayList<>(items)));
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

        // Get the team info if in team mode
        String teamCode = null;
        String teamName = null;
        BingoConfig.BingoMode bingoMode = profileManager.getProfileBingoMode();
        if (bingoMode == BingoConfig.BingoMode.TEAM) {
            teamCode = profileManager.getProfileTeamCode();
            teamName = profileManager.getProfileTeamName();
            
            // If we have a team code but no team name, use the code as the name
            if (teamCode != null && !teamCode.isEmpty() && (teamName == null || teamName.isEmpty())) {
                teamName = "Team " + teamCode;
            }
            
            // Update the team service if we have a team code
            if (teamCode != null && !teamCode.isEmpty()) {
                teamService.updateItemObtained(teamCode, itemName, true);
            }
        }

        // Send Discord notification
        String message = "Obtained bingo item: " + itemName;
        final String finalTeamName = teamName;

        // Always capture screenshot
        captureScreenshotAsync(screenshot -> {
            discordNotifier.sendNotification(
                    message,
                    screenshot,
                    false,
                    profileManager.getProfileDiscordWebhook(),
                    executor,
                    finalTeamName
            );
        });

        // Show chat message only if enabled
        if (shouldShowChatNotifications()) {
            chatMessageManager.queue(QueuedMessage.builder()
                    .type(ChatMessageType.GAMEMESSAGE)
                    .runeLiteFormattedMessage("Bingo: Obtained item " + itemName)
                    .build());
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

        // Get the team name if in team mode
        String teamName = null;
        BingoConfig.BingoMode bingoMode = profileManager.getProfileBingoMode();
        if (bingoMode == BingoConfig.BingoMode.TEAM) {
            teamName = profileManager.getProfileTeamName();
            String teamCode = profileManager.getProfileTeamCode();
            
            // If we have a team code but no team name, use the code as the name
            if ((teamName == null || teamName.isEmpty()) && teamCode != null && !teamCode.isEmpty()) {
                teamName = "Team " + teamCode;
            }
        }
        
        final String finalTeamName = teamName;

        // Send Discord notification
        captureScreenshotAsync(screenshot -> {
            discordNotifier.sendNotification(
                    message,
                    screenshot,
                    true,
                    profileManager.getProfileDiscordWebhook(),
                    executor,
                    finalTeamName
            );
        });

        // Show chat message only if enabled
        if (shouldShowChatNotifications()) {
            chatMessageManager.queue(QueuedMessage.builder()
                    .type(ChatMessageType.GAMEMESSAGE)
                    .runeLiteFormattedMessage("Bingo: " + message)
                    .build());
        }
    }

    /**
     * Reloads items based on the current profile
     */
    public void reloadItems() {
        // Clear existing items
        items.clear();
        itemsByName.clear();
        
        // Get the current profile's bingo mode
        BingoConfig.BingoMode bingoMode = profileManager.getProfileBingoMode();
        
        // If this is a team profile, handle team-specific logic
        if (bingoMode == BingoConfig.BingoMode.TEAM) {
            String teamCode = profileManager.getProfileTeamCode();
            if (teamCode != null && !teamCode.isEmpty()) {
                // This ensures we have items to display immediately during profile switching
                List<BingoItem> cachedItems = teamService.getTeamCachedItems(teamCode);
                if (cachedItems != null && !cachedItems.isEmpty()) {

                    // Add the cached items to our collections
                    for (BingoItem item : cachedItems) {
                        items.add(item);
                        itemsByName.put(item.getName().toLowerCase(), item);
                    }
                    
                    // CRITICAL STEP 2: Update the UI immediately with cached items
                    // Must happen before async operations to ensure instant display
                    SwingUtilities.invokeLater(() -> {
                        if (panel != null) {
                            panel.updateItems(new ArrayList<>(items));
                            panel.updateSourceWarningLabel();
                        }
                    });
                } else {
                    log.info("No cached items available for team {}", teamCode);
                }

                teamService.registerTeamListener(teamCode, this::updateItemsFromFirebase);
                
                // Provide fast feedback in the UI regardless of network state
                loadSavedItems();
                
                // If we already loaded items from cache, we're done
                if (!items.isEmpty()) {
                    log.info("Using cached items for display, async update will happen in background");
                    return;
                }
                
                // If no cached items, continue with standard item loading below
            }
        }
        
        // Load items based on the current source
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
            
            // CRITICAL FIX: For team profiles, we still need to ensure Firebase state is properly reflected
            // This ensures that if an item is obtained in Firebase, it's displayed correctly in the UI
            String teamCode = profileManager.getProfileTeamCode();
            if (teamCode != null && !teamCode.isEmpty()) {
                teamService.getTeamData(teamCode)
                    .thenAccept(teamData -> {
                        if (teamData != null && teamData.containsKey("items")) {
                            // The items might be stored as a Map or as a List in the response
                            Object itemsObj = teamData.get("items");
                            if (itemsObj instanceof Map) {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> itemsMap = (Map<String, Object>) itemsObj;
                                
                                clientThread.invokeLater(() -> {
                                    boolean updatedAnyItem = false;
                                    
                                    for (Map.Entry<String, Object> entry : itemsMap.entrySet()) {
                                        if (entry.getValue() instanceof Map) {
                                            @SuppressWarnings("unchecked")
                                            Map<String, Object> itemData = (Map<String, Object>) entry.getValue();
                                            
                                            if (itemData.containsKey("obtained") && Boolean.TRUE.equals(itemData.get("obtained"))) {
                                                // This item is marked as obtained in Firebase
                                                String itemName = (String) itemData.getOrDefault("name", entry.getKey());
                                                // Find this item in our local list and mark it as obtained
                                                BingoItem item = itemsByName.get(itemName.toLowerCase());
                                                if (item != null && !item.isObtained()) {
                                                    item.setObtained(true);
                                                    updatedAnyItem = true;
                                                }
                                            }
                                        }
                                    }
                                    
                                    // If we updated any items, update the UI
                                    if (updatedAnyItem) {
                                        updateUI();
                                    }
                                });
                            }
                        }
                    })
                    .exceptionally(e -> {
                        log.error("Error checking Firebase state", e);
                        return null;
                    });
            }
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
            
            // CRITICAL FIX: For team profiles, check Firebase even if there's no local data
            if (profileManager.getProfileBingoMode() == BingoConfig.BingoMode.TEAM) {
                String teamCode = profileManager.getProfileTeamCode();
                if (teamCode != null && !teamCode.isEmpty()) {
                    loadSavedItems(); // This will trigger the Firebase check in team mode
                }
            }
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
            chatMessageManager.queue(QueuedMessage.builder()
                    .type(ChatMessageType.GAMEMESSAGE)
                    .runeLiteFormattedMessage("Bingo board has been reset.")
                    .build());
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
            }
        }

        // Create the request
        Request request = new Request.Builder()
                .url(remoteUrl)
                .get()
                .build();

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
                        if (content.length() < 100) {
                            log.info("Content preview: {}", content);
                        }

                        List<String> lines = Arrays.asList(content.split("\\r?\\n"));
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
                // Use a background thread to avoid blocking the UI
                executor.submit(() -> {
                    try {
                        boolean success = teamService.updateItemObtained(teamCode, item.getName(), newStatus)
                            .get(10, TimeUnit.SECONDS); // Wait up to 10 seconds for the update to complete
                        
                        if (success) {
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

        profileManager.deleteProfile(tempProfileName);
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
                chatMessageManager.queue(QueuedMessage.builder()
                        .type(ChatMessageType.GAMEMESSAGE)
                        .runeLiteFormattedMessage("Cannot refresh team items: not in a team profile.")
                        .build());
            }
            return;
        }
        
        // Get the team code
        String teamCode = profileManager.getProfileTeamCode();
        if (teamCode == null || teamCode.isEmpty()) {
            log.warn("Cannot refresh team items: no team code");
            if (client.getLocalPlayer() != null && shouldShowChatNotifications()) {
                chatMessageManager.queue(QueuedMessage.builder()
                        .type(ChatMessageType.GAMEMESSAGE)
                        .runeLiteFormattedMessage("Cannot refresh team items: no team code.")
                        .build());
            }
            return;
        }
        
        // Check if the profile is using a remote URL
        if (profileManager.getProfileItemSourceType() != BingoConfig.ItemSourceType.REMOTE) {
            log.warn("Cannot refresh team items: profile is not using a remote URL");
            if (client.getLocalPlayer() != null && shouldShowChatNotifications()) {
                chatMessageManager.queue(QueuedMessage.builder()
                        .type(ChatMessageType.GAMEMESSAGE)
                        .runeLiteFormattedMessage("Cannot refresh team items: profile is not using a remote URL.")
                        .build());
            }
            return;
        }
        
        // Get the remote URL
        String remoteUrl = profileManager.getProfileRemoteUrl();
        if (remoteUrl == null || remoteUrl.isEmpty()) {
            log.warn("Cannot refresh team items: no remote URL configured");
            if (client.getLocalPlayer() != null && shouldShowChatNotifications()) {
                chatMessageManager.queue(QueuedMessage.builder()
                        .type(ChatMessageType.GAMEMESSAGE)
                        .runeLiteFormattedMessage("Cannot refresh team items: no remote URL configured.")
                        .build());
            }
            return;
        }
        
        // Show notification that refresh has started
        if (client.getLocalPlayer() != null && shouldShowChatNotifications()) {
            chatMessageManager.queue(QueuedMessage.builder()
                    .type(ChatMessageType.GAMEMESSAGE)
                    .runeLiteFormattedMessage("Refreshing team items from remote URL...")
                    .build());
        }
        
        final String finalTeamCode = teamCode;
        
        // Make the operation fully asynchronous - don't wait for completion
        teamService.refreshTeamItems(finalTeamCode)
            .thenAcceptAsync(success -> {
                // This runs after the operation completes successfully
                clientThread.invokeLater(() -> {
                    if (success) {
                        log.info("Successfully refreshed team items from remote URL");
                        if (client.getLocalPlayer() != null && shouldShowChatNotifications()) {
                            chatMessageManager.queue(QueuedMessage.builder()
                                    .type(ChatMessageType.GAMEMESSAGE)
                                    .runeLiteFormattedMessage("Successfully refreshed team items from remote URL.")
                                    .build());
                        }
                        
                        // Reload items
                        reloadItems();
                    } else {
                        log.error("Failed to refresh team items from remote URL");
                        if (client.getLocalPlayer() != null && shouldShowChatNotifications()) {
                            chatMessageManager.queue(QueuedMessage.builder()
                                    .type(ChatMessageType.GAMEMESSAGE)
                                    .runeLiteFormattedMessage("Failed to refresh team items from remote URL.")
                                    .build());
                        }
                        
                        // Try to reload items anyway from cache or local storage
                        reloadItems();
                    }
                });
            }, executor)
            .exceptionally(ex -> {
                // This runs if an exception occurs
                clientThread.invokeLater(() -> {
                    if (ex.getCause() instanceof TimeoutException) {
                        log.error("Timeout while refreshing team items", ex);
                        if (client.getLocalPlayer() != null && shouldShowChatNotifications()) {
                            chatMessageManager.queue(QueuedMessage.builder()
                                    .type(ChatMessageType.GAMEMESSAGE)
                                    .runeLiteFormattedMessage("Timeout while refreshing team items. The server is taking too long to respond.")
                                    .build());
                        }
                    } else {
                        log.error("Error refreshing team items", ex);
                        if (client.getLocalPlayer() != null && shouldShowChatNotifications()) {
                            chatMessageManager.queue(QueuedMessage.builder()
                                    .type(ChatMessageType.GAMEMESSAGE)
                                    .runeLiteFormattedMessage("Error refreshing team items: " + ex.getMessage())
                                    .build());
                        }
                    }
                    
                    // Try to reload items anyway from cache or local storage
                    clientThread.invokeLater(this::reloadItems);
                });
                
                return null;
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

    /**
     * Force a complete reload of items and refresh the UI.
     * This method clears all cached items and reloads them from scratch,
     * ensuring a fresh state when switching profiles.
     */
    public void forceReloadItems() {
        String currentProfile = config.currentProfile();
        // Clear existing items first
        clearItems();
        
        // Get the current profile's bingo mode
        BingoConfig.BingoMode bingoMode = profileManager.getProfileBingoMode();
        
        // For team profiles, check the cache first for immediate display
        if (bingoMode == BingoConfig.BingoMode.TEAM) {
            String teamCode = profileManager.getProfileTeamCode();
            if (teamCode != null && !teamCode.isEmpty()) {
                // Get cached items immediately
                List<BingoItem> cachedItems = teamService.getTeamCachedItems(teamCode);
                if (cachedItems != null && !cachedItems.isEmpty()) {
                    for (BingoItem item : cachedItems) {
                        items.add(item);
                        itemsByName.put(item.getName().toLowerCase(), item);
                    }
                    
                    // Update the UI immediately with cached items
                    SwingUtilities.invokeLater(() -> {
                        if (panel != null) {
                            panel.updateItems(new ArrayList<>(items));
                        }
                    });
                }
            }
        }

        reloadItems();
        loadSavedItems();
        
        // For team profiles, also refresh team items
        if (bingoMode == BingoConfig.BingoMode.TEAM) {
            String teamCode = profileManager.getProfileTeamCode();
            if (teamCode != null && !teamCode.isEmpty()) {
                refreshTeamItems();
            }
        }
        updateUI();
    }

    /**
     * Force a configuration update to ensure the profile switch takes effect
     * This is a critical method to ensure profile switching works correctly
     */
    public void forceConfigUpdate(String targetProfile) {
        if (targetProfile == null || targetProfile.isEmpty()) {
            log.error("Attempted to update profile with null/empty profile name");
            return;
        }
        
        // Get the current config value
        String currentProfile = config.currentProfile();
        if (targetProfile.equals(currentProfile)) {
            log.debug("Profile already set to {}, no update needed", targetProfile);
            return;
        }
        
        log.info("Switching profile from {} to {}", currentProfile, targetProfile);
        
        // Use the correct configuration key
        configManager.setConfiguration(CONFIG_GROUP, "currentProfile", targetProfile);
        
        // Verify the change was applied
        String updatedProfile = config.currentProfile();
        if (!targetProfile.equals(updatedProfile)) {
            log.warn("Profile update may not have been applied. Expected: {}, Current: {}", 
                    targetProfile, updatedProfile);
            
            // Try with the betterbingo group as a fallback
            configManager.setConfiguration("betterbingo", "currentProfile", targetProfile);
        } else {
            log.info("Successfully updated profile to: {}", targetProfile);
        }
    }

    /**
     * Forces a refresh of the UI based on Firebase data.
     * This is a safety mechanism to ensure the UI always reflects what's in Firebase.
     */
    public void refreshUIFromFirebase() {
        if (profileManager.getProfileBingoMode() != BingoConfig.BingoMode.TEAM) {
            return;
        }
        
        String teamCode = profileManager.getProfileTeamCode();
        if (teamCode == null || teamCode.isEmpty()) {
            return;
        }

        teamService.getTeamData(teamCode)
            .thenAccept(teamData -> {
                if (teamData == null || !teamData.containsKey("items")) {
                    return;
                }
                
                // Get the items data
                Object itemsObj = teamData.get("items");
                if (!(itemsObj instanceof Map)) {
                    return;
                }
                
                @SuppressWarnings("unchecked")
                Map<String, Object> itemsMap = (Map<String, Object>) itemsObj;
                
                clientThread.invokeLater(() -> {
                    int updatedCount = 0;
                    
                    // Process each item in Firebase
                    for (Map.Entry<String, Object> entry : itemsMap.entrySet()) {
                        if (!(entry.getValue() instanceof Map)) {
                            continue;
                        }
                        
                        @SuppressWarnings("unchecked")
                        Map<String, Object> itemData = (Map<String, Object>) entry.getValue();
                        
                        // Check if this item is obtained in Firebase
                        boolean obtainedInFirebase = itemData.containsKey("obtained") && 
                                                    Boolean.TRUE.equals(itemData.get("obtained"));
                        
                        // Get the item name
                        String itemName = (String) itemData.getOrDefault("name", entry.getKey());
                        
                        // Find this item in our local list
                        BingoItem localItem = itemsByName.get(itemName.toLowerCase());
                        if (localItem != null) {
                            // If Firebase says it's obtained but our UI doesn't, update the UI
                            if (obtainedInFirebase && !localItem.isObtained()) {
                                localItem.setObtained(true);
                                updatedCount++;
                            }
                        }
                    }
                    
                    // If we made any changes, update the UI
                    if (updatedCount > 0) {
                        log.info("Updated {} items in UI based on Firebase data", updatedCount);
                        updateUI();
                    } else {
                        log.info("UI is already in sync with Firebase data");
                    }
                });
            })
            .exceptionally(e -> {
                log.error("Error refreshing UI from Firebase", e);
                return null;
            });
    }
}