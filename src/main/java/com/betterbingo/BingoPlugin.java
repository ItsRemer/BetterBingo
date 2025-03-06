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
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
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
import net.runelite.client.util.ImageUtil;
import okhttp3.*;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.Map;
import net.runelite.api.ItemComposition;
import net.runelite.http.api.item.ItemPrice;
import net.runelite.api.NPC;
import java.util.Collection;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.Actor;
import net.runelite.api.events.ItemSpawned;
import java.util.HashSet;
import java.util.Set;
import net.runelite.client.util.ImageCapture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.HashMap;


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
        panel = injector.getInstance(BingoPanel.class);

        final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/icon.png");

        navButton = NavigationButton.builder()
                .tooltip("Bingo")
                .icon(icon)
                .priority(5)
                .panel(panel)
                .build();

        clientToolbar.addNavigation(navButton);

        // Initialize with the current profile
        log.info("Starting with bingo profile: {}", config.currentProfile());
        loadItems();

        if (config.itemSourceType() == BingoConfig.ItemSourceType.REMOTE) {
            scheduleRemoteUpdate();
        }

        if (config.discordWebhookUrl() == null || config.discordWebhookUrl().isEmpty()) {
            log.warn("Discord webhook URL is not configured. Discord notifications will not be sent.");
        } else {
            log.info("Discord webhook URL is configured: {}", config.discordWebhookUrl().substring(0, Math.min(20, config.discordWebhookUrl().length())) + "...");
        }
        if (config.completionNotifications()) {
            log.info("Completion notifications are enabled");
        } else {
            log.warn("Completion notifications are disabled");
        }
    }

    @Override
    protected void shutDown() {
        clientToolbar.removeNavigation(navButton);
        items.clear();
        itemsByName.clear();
        recentlyKilledNpcs.clear();
    }

    @Provides
    BingoConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(BingoConfig.class);
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (!event.getGroup().equals(CONFIG_GROUP)) {
            return;
        }

        // Handle profile-specific settings
        String key = event.getKey();
        String currentProfileKey = profileManager.getCurrentProfileKey("");

        if (key.startsWith(currentProfileKey)) {
            String actualKey = key.substring(currentProfileKey.length());
            if (actualKey.equals("itemSourceType") || actualKey.equals("itemList") || actualKey.equals("remoteUrl")) {
                loadItems();

                if (profileManager.getProfileItemSourceType() == BingoConfig.ItemSourceType.REMOTE) {
                    scheduleRemoteUpdate();
                }
            } else if (actualKey.equals("refreshInterval") && profileManager.getProfileItemSourceType() == BingoConfig.ItemSourceType.REMOTE) {
                scheduleRemoteUpdate();
            }
        }
        // Handle global settings
        else if (key.equals("itemSourceType") || key.equals("itemList") || key.equals("remoteUrl")) {
            loadItems();

            if (config.itemSourceType() == BingoConfig.ItemSourceType.REMOTE) {
                scheduleRemoteUpdate();
            }
        } else if (key.equals("refreshInterval") && config.itemSourceType() == BingoConfig.ItemSourceType.REMOTE) {
            scheduleRemoteUpdate();
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
     * Finds a bingo item by name and marks it as obtained
     *
     * @param itemName The name of the item to find
     * @return The obtained item, or null if not found or already obtained
     */
    private ObtainedItem findAndMarkItem(String itemName) {
        if (itemName == null || itemName.isEmpty()) {
            return null;
        }

        String normalizedName = cleanItemName(itemName);
        if (normalizedName == null) {
            return null;
        }

        BingoItem bingoItem = itemsByName.get(normalizedName.toLowerCase());

        if (bingoItem != null && !bingoItem.isObtained()) {
            if (!antiCheat.validateItemAcquisition(normalizedName)) {
                if (client.getGameState() == GameState.LOGGED_IN) {
                    client.addChatMessage(
                            ChatMessageType.GAMEMESSAGE,
                            "",
                            "<col=ff0000>Warning:</col> <col=ffffff>Item acquisition validation failed for</col> <col=ffff00>" + itemName + "</col>",
                            null
                    );
                }
                return null;
            }

            bingoItem.setObtained(true);
            int index = items.indexOf(bingoItem);

            saveObtainedItems();
            if (panel != null) {
                SwingUtilities.invokeLater(() -> panel.updateItems(items));
            }

            return new ObtainedItem(bingoItem, index);
        }

        return null;
    }

    /**
     * Notifies the player of an obtained item and sends a Discord notification if configured
     *
     * @param obtainedItem The obtained item
     */
    private void notifyItemObtained(ObtainedItem obtainedItem) {
        if (obtainedItem == null) {
            return;
        }

        BingoItem bingoItem = obtainedItem.getBingoItem();
        int index = obtainedItem.getIndex();

        if (client.getGameState() == GameState.LOGGED_IN) {
            client.addChatMessage(
                    ChatMessageType.GAMEMESSAGE,
                    "",
                    "<col=00ff00>Bingo:</col> <col=ffffff>Obtained item</col> <col=ffff00>" + bingoItem.getName() + "</col>",
                    null
            );
        }

        if (config.discordWebhookUrl() != null && !config.discordWebhookUrl().isEmpty()) {
            executor.execute(() -> captureScreenshotAsync(screenshot -> {
                String playerName = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : "Unknown";
                int worldNumber = client.getWorld();
                String worldType = client.getWorldType().toString();

                String headerMessage = String.format("**Player: %s | World: %d (%s)**\n\n",
                        playerName, worldNumber, worldType);

                String itemMessage = "Obtained bingo item: **" + bingoItem.getName() + "**";

                Set<String> bingoItemNames = itemsByName.keySet().stream()
                        .map(String::toLowerCase)
                        .collect(Collectors.toSet());

                String logMessage = antiCheat.buildBingoItemsAcquisitionLogMessage(bingoItemNames);

                String combinedMessage = headerMessage + itemMessage + "\n\n" + logMessage;

                discordNotifier.sendNotification(
                        combinedMessage,
                        screenshot,
                        false,
                        config.discordWebhookUrl(),
                        executor
                );
            }));
        }

        checkForCompletions(index);
    }

    /**
     * Captures a screenshot asynchronously and calls the callback with the result
     *
     * @param callback The callback to call with the captured screenshot (or null if disabled/failed)
     */
    private void captureScreenshotAsync(Consumer<BufferedImage> callback) {
        if (!config.sendScreenshot()) {
            log.debug("Screenshot capture disabled by config");
            callback.accept(null);
            return;
        }

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
     * Notifies the player of a bingo completion and sends a Discord notification if configured
     *
     * @param completionType  The type of completion (row, column, or full)
     * @param completionIndex The index of the completed row or column
     */
    private void notifyCompletion(CompletionType completionType, int completionIndex) {
        String completionMessage = getCompletionMessage(completionType, completionIndex);

        if (client.getGameState() == GameState.LOGGED_IN) {
            client.addChatMessage(
                    ChatMessageType.GAMEMESSAGE,
                    "",
                    "<col=00ff00>Bingo:</col> <col=ffffff>" + completionMessage + "</col>",
                    null
            );
        }

        if (config.discordWebhookUrl() != null && !config.discordWebhookUrl().isEmpty()) {
            String discordMessage = "**BINGO!** " + completionMessage;

            executor.execute(() -> captureScreenshotAsync(screenshot -> {
                discordNotifier.sendNotification(
                        discordMessage,
                        screenshot,
                        true,
                        config.discordWebhookUrl(),
                        executor
                );
            }));
        }
    }

    /**
     * Loads items from the config based on the selected source type
     */
    private void loadItems() {
        items.clear();
        itemsByName.clear();

        BingoConfig.ItemSourceType itemSourceType = profileManager.getProfileItemSourceType();

        if (itemSourceType == BingoConfig.ItemSourceType.MANUAL) {
            String remoteUrl = profileManager.getProfileRemoteUrl();
            if (!remoteUrl.isEmpty()) {
                clientThread.invoke(() -> {
                    if (client.getLocalPlayer() != null) {
                        client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                                "Warning: Remote URL is configured but not being used because you're in Manual mode.", "");
                    }
                });
            }
            loadItemsFromManualList();
        } else if (itemSourceType == BingoConfig.ItemSourceType.REMOTE) {
            String itemList = profileManager.getProfileItemList();
            String defaultItemList = "Dragon Scimitar\nAbyssal Whip\nFire Cape\nBarrows Gloves\nDragon Boots";

            if (!itemList.equals(defaultItemList) && !itemList.isEmpty()) {
                clientThread.invoke(() -> {
                    if (client.getLocalPlayer() != null) {
                        client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                                "Warning: Manual Bingo Items are configured but not being used because you're in Remote URL mode.", "");
                    }
                });
            }

            String remoteUrl = profileManager.getProfileRemoteUrl();
            if (!remoteUrl.isEmpty()) {
                updateRemoteItems();
            } else {
                clientThread.invoke(() -> {
                    if (client.getLocalPlayer() != null) {
                        client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                                "Remote URL is not configured. Please set a URL in the Bingo plugin settings.", "");
                    } else {
                        log.info("Remote URL is not configured. Please set a URL in the Bingo plugin settings.");
                    }
                });
            }
        }

        SwingUtilities.invokeLater(() -> panel.updateItems(items));
    }

    /**
     * Loads items from the manual list in the config
     */
    private void loadItemsFromManualList() {
        String itemList = profileManager.getProfileItemList();
        if (!itemList.isEmpty()) {
            Arrays.stream(itemList.split("\\n"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .forEach(line -> Arrays.stream(line.split(";"))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .forEach(itemName -> {
                                String properName = itemName.replace("_", " ");
                                List<ItemPrice> searchResults = itemManager.search(properName);
                                if (!searchResults.isEmpty()) {
                                    ItemPrice match = searchResults.get(0);
                                    BingoItem item = new BingoItem(match.getName(), match.getId());
                                    items.add(item);
                                    itemsByName.put(item.getName().toLowerCase(), item);
                                } else {
                                    BingoItem item = new BingoItem(properName);
                                    items.add(item);
                                    itemsByName.put(item.getName().toLowerCase(), item);
                                }
                            }));
        }
    }

    /**
     * Loads saved items from the configuration.
     */
    public void loadSavedItems() {
        if (profileManager.getProfilePersistObtained()) {
            return;
        }

        forceLoadSavedItems();
    }

    /**
     * Forces loading of saved items regardless of persistObtained setting.
     * Used during profile switching to ensure items are loaded correctly.
     */
    public void forceLoadSavedItems() {
        String profileKey = profileManager.getCurrentProfileKey(CONFIG_KEY_OBTAINED_ITEMS);
        String savedItems = configManager.getConfiguration(CONFIG_GROUP, profileKey);
        if (savedItems == null || savedItems.isEmpty()) {
            return;
        }

        Arrays.stream(savedItems.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(name -> {
                    BingoItem item = itemsByName.get(name.toLowerCase());
                    if (item != null) {
                        item.setObtained(true);
                    }
                });
    }

    /**
     * Saves obtained items to the configuration.
     */
    public void saveObtainedItems() {
        if (profileManager.getProfilePersistObtained()) {
            return;
        }

        String obtainedItems = items.stream()
                .filter(BingoItem::isObtained)
                .map(BingoItem::getName)
                .collect(Collectors.joining(","));

        String profileKey = profileManager.getCurrentProfileKey(CONFIG_KEY_OBTAINED_ITEMS);
        configManager.setConfiguration(CONFIG_GROUP, profileKey, obtainedItems);
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
        configManager.unsetConfiguration(CONFIG_GROUP, profileKey);

        clearObtainedItems();
        updateUI();

        if (client.getLocalPlayer() != null) {
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
     * Schedules periodic updates from remote URL
     */
    private void scheduleRemoteUpdate() {
        executor.scheduleAtFixedRate(this::updateRemoteItems,
                0, profileManager.getProfileRefreshInterval(), TimeUnit.MINUTES);
    }

    /**
     * Updates items from remote URL
     */
    private void updateRemoteItems() {
        String remoteUrl = profileManager.getProfileRemoteUrl();
        if (remoteUrl.isEmpty()) {
            return;
        }

        try {
            String url = remoteUrl;
            if (url.contains("pastebin.com/") && !url.contains("raw")) {
                String pasteId = url.substring(url.lastIndexOf("/") + 1);
                url = "https://pastebin.com/raw/" + pasteId;
                log.debug("Converted Pastebin URL to raw format: {}", url);
            }

            log.debug("Fetching remote items from URL: {}", url);

            Request request = new Request.Builder()
                    .url(url)
                    .build();

            try (Response response = okHttpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.warn("Failed to fetch remote items: {}", response.code());
                    clientThread.invoke(() -> {
                        if (client.getLocalPlayer() != null) {
                            client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                                    "Failed to fetch bingo items: " + response.code(), "");
                        }
                    });
                    return;
                }

                String content = response.body().string();

                if (!content.trim().startsWith("[") && !content.trim().startsWith("{")) {
                    List<String> remoteItems = Arrays.stream(content.split("\\n"))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .collect(Collectors.toList());

                    if (remoteItems.isEmpty()) {
                        log.warn("Remote items list is empty");
                        clientThread.invoke(() -> {
                            if (client.getLocalPlayer() != null) {
                                client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                                        "Remote items list is empty", "");
                            }
                        });
                        return;
                    }

                    updateItemsFromList(remoteItems);
                    return;
                }

                Type listType = new TypeToken<List<String>>() {
                }.getType();
                List<String> remoteItems = gson.fromJson(content, listType);

                if (remoteItems == null || remoteItems.isEmpty()) {
                    log.warn("Remote items list is empty");
                    clientThread.invoke(() -> {
                        if (client.getLocalPlayer() != null) {
                            client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                                    "Remote items list is empty", "");
                        }
                    });
                    return;
                }

                updateItemsFromList(remoteItems);
            }
        } catch (Exception e) {
            log.warn("Error fetching remote items", e);
            clientThread.invoke(() -> {
                if (client.getLocalPlayer() != null) {
                    client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                            "Error fetching bingo items: " + e.getMessage(), "");
                }
            });
        }
    }

    /**
     * Updates the items list from a list of item names
     *
     * @param remoteItems List of item names to update from
     */
    private void updateItemsFromList(List<String> remoteItems) {
        items.clear();
        itemsByName.clear();

        for (String itemName : remoteItems) {
            String properName = itemName.trim().replace("_", " ");
            if (properName.isEmpty()) {
                continue;
            }

            List<ItemPrice> searchResults = itemManager.search(properName);
            BingoItem bingoItem;

            if (!searchResults.isEmpty()) {
                ItemPrice match = searchResults.get(0);
                bingoItem = new BingoItem(match.getName(), match.getId());
            } else {
                bingoItem = new BingoItem(properName);
            }

            items.add(bingoItem);
            itemsByName.put(bingoItem.getName().toLowerCase(), bingoItem);
        }

        loadSavedItems();

        SwingUtilities.invokeLater(() -> panel.updateItems(items));

        final int itemCount = items.size();
        clientThread.invoke(() -> {
            if (client.getLocalPlayer() != null) {
                client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                        "Bingo items updated (" + itemCount + " items)", "");
            }
        });

        log.debug("Successfully loaded {} items from remote URL", items.size());
    }

    /**
     * Manually triggers an update from the remote URL
     */
    public void updateRemoteItemsManually() {
        updateRemoteItems();
    }

    /**
     * Reloads items from the current source (manual or remote).
     * This is a public method that can be called from other classes.
     */
    public void reloadItems() {
        loadItems();
    }

}