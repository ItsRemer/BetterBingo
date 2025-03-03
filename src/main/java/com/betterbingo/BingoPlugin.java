package com.betterbingo;

import com.google.gson.JsonObject;
import com.google.inject.Provides;
import com.google.inject.Injector;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
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

import java.io.IOException;
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

    private static final MediaType MEDIA_TYPE_PNG = MediaType.parse("image/png");
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final String CONFIG_GROUP = "bingo";
    private static final String CONFIG_KEY_OBTAINED_ITEMS = "obtainedItems";
    private static final int GRID_SIZE = 5;
    private static final int MAX_ITEMS = GRID_SIZE * GRID_SIZE; // Maximum 25 items

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

    @Getter
    private final List<BingoItem> items = new ArrayList<>();

    private final Map<String, BingoItem> itemsByName = new HashMap<>();
    private final Set<Integer> recentlyKilledNpcs = new HashSet<>();
    private BingoPanel panel;
    private NavigationButton navButton;

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

        if (event.getKey().equals("itemSourceType") || event.getKey().equals("itemList") || event.getKey().equals("remoteUrl")) {
            loadItems();
            
            if (config.itemSourceType() == BingoConfig.ItemSourceType.REMOTE) {
                scheduleRemoteUpdate();
            }
        } else if (event.getKey().equals("refreshInterval") && config.itemSourceType() == BingoConfig.ItemSourceType.REMOTE) {
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
        Collection<ItemStack> items = event.getItems();

        if (items == null) {
            return;
        }

        for (ItemStack item : items) {
            if (item == null) {
                continue;
            }

            final ItemComposition itemComp = itemManager.getItemComposition(item.getId());
            checkForItem(itemComp.getName());
        }
    }

    /**
     * Processes a message that might contain multiple items separated by commas or "and"
     * 
     * @param message The message containing item names
     */
    private void processMultiItemMessage(String message) {
        String[] parts = message.split(",|\\sand\\s");
        
        for (String part : parts) {
            String itemName = cleanItemName(part);
            if (itemName != null && !itemName.isEmpty()) {
                checkForItem(itemName);
            }
        }
    }

    @Subscribe
    public void onChatMessage(net.runelite.api.events.ChatMessage event) {
        if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM) {
            return;
        }

        String message = event.getMessage();
        
        for (ChatMessagePattern pattern : chatPatterns) {
            if (pattern.matches(message)) {
                String extractedText = pattern.extractItem(message);
                
                if (extractedText == null) {
                    continue;
                }
                
                if (pattern.isMultiItem()) {
                    processMultiItemMessage(extractedText);
                } else {
                    String itemName = cleanItemName(extractedText);
                    if (itemName != null && !itemName.isEmpty()) {
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
        for (NPC npc : client.getNpcs()) {
            if (npc != null && recentlyKilledNpcs.contains(npc.getIndex()) &&
                    tile.getWorldLocation().distanceTo(npc.getWorldLocation()) <= 1) {
                isNearKilledNpc = true;
                break;
            }
        }

        if (!isNearKilledNpc) {
            return;
        }

        clientThread.invoke(() -> {
            final ItemComposition itemComp = itemManager.getItemComposition(item.getId());
            checkForItem(itemComp.getName());
        });
    }

    /**
     * Represents an item that has been obtained
     */
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
        if (itemName == null) {
            return null;
        }

        String normalizedName = itemName.toLowerCase();
        BingoItem bingoItem = itemsByName.get(normalizedName);
        
        if (bingoItem != null && !bingoItem.isObtained()) {
            bingoItem.setObtained(true);
            int index = items.indexOf(bingoItem);
            log.debug("Item obtained: {} at index {}", itemName, index);

            if (bingoItem.getItemId() == -1) {
                lookupItemId(bingoItem);
            }
            saveObtainedItems();
            SwingUtilities.invokeLater(() -> panel.updateItems(items));
            
            return new ObtainedItem(bingoItem, index);
        }
        
        return null;
    }
    
    /**
     * Looks up an item ID from the item manager
     * 
     * @param bingoItem The bingo item to update
     */
    private void lookupItemId(BingoItem bingoItem) {
        List<ItemPrice> searchResults = itemManager.search(bingoItem.getName());
        if (!searchResults.isEmpty()) {
            bingoItem.setItemId(searchResults.get(0).getId());
        }
    }
    
    /**
     * Notifies the player that an item has been obtained
     * 
     * @param obtainedItem The obtained item
     */
    private void notifyItemObtained(ObtainedItem obtainedItem) {
        if (obtainedItem == null) {
            return;
        }

        String itemName = obtainedItem.getName();
        String gameMessage = String.format("<col=00ff00>Congratulations!</col> <col=ffffff>You've obtained:</col> <col=ffff00>%s</col>", itemName);

        if (client.getGameState() == GameState.LOGGED_IN) {
            client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", gameMessage, null);
        }
        
        if (config.discordWebhookUrl() != null && !config.discordWebhookUrl().isEmpty()) {
            String discordMessage = String.format("%s has obtained: %s", 
                    client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : "Player", 
                    itemName);

            executor.execute(() -> captureScreenshotAsync(screenshot -> sendDiscordNotification(discordMessage, screenshot, false)));
        }
        checkForCompletions(obtainedItem.getIndex());
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

        drawManager.requestNextFrameListener(image -> {
            executor.submit(() -> {
                try {
                    if (image == null) {
                        log.warn("Received null image from drawManager");
                        callback.accept(null);
                        return;
                    }
                    
                    int width = image.getWidth(null);
                    int height = image.getHeight(null);
                    
                    if (width <= 0 || height <= 0) {
                        log.warn("Invalid image dimensions: {}x{}", width, height);
                        callback.accept(null);
                        return;
                    }
                    
                    BufferedImage screenshot = new BufferedImage(
                            width, height, BufferedImage.TYPE_INT_ARGB);
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
            notifyBoardCompletion("Row " + (row + 1) + " complete!");
        }

        if (isColumnComplete(col)) {
            notifyBoardCompletion("Column " + (col + 1) + " complete!");
        }

        boolean onDiagonal1 = (row == col);
        if (onDiagonal1 && isDiagonal1Complete()) {
            notifyBoardCompletion("Diagonal (top-left to bottom-right) complete!");
        }

        boolean onDiagonal2 = (row + col == GRID_SIZE - 1);
        if (onDiagonal2 && isDiagonal2Complete()) {
            notifyBoardCompletion("Diagonal (top-right to bottom-left) complete!");
        }

        if (isFullBoardComplete()) {
            notifyBoardCompletion("BINGO! Full board complete!");
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
     * Sends a special notification for bingo completions
     *
     * @param message The completion message
     */
    private void notifyBoardCompletion(String message) {
        if (!config.completionNotifications()) {
            return;
        }

        String gameMessage = String.format("<col=00ff00>BINGO COMPLETION!</col> <col=ffffff>%s</col>", message);

        if (client.getGameState() == GameState.LOGGED_IN) {
            client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", gameMessage, null);
        }

        if (config.discordWebhookUrl() != null && !config.discordWebhookUrl().isEmpty()) {
            String discordMessage = String.format("%s completed: %s", 
                    client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : "Player", 
                    message);

            executor.execute(() -> captureScreenshotAsync(screenshot -> {

                sendDiscordNotification(discordMessage, screenshot, true);
            }));
        }
    }

    /**
     * Represents a Discord notification with optional screenshot
     */
    private static class DiscordNotification {
        @Getter
        private final String message;
        @Getter
        private final BufferedImage screenshot;
        private final boolean isCompletion;
        
        public DiscordNotification(String message, BufferedImage screenshot, boolean isCompletion) {
            this.message = message;
            this.screenshot = screenshot;
            this.isCompletion = isCompletion;
        }

        public boolean hasScreenshot() {
            return screenshot != null;
        }
        
        public boolean isCompletion() {
            return isCompletion;
        }
    }
    
    /**
     * Sends a Discord notification with optional screenshot
     * 
     * @param message The notification message
     * @param screenshot The screenshot to include, or null for text-only
     * @param isCompletion Whether this is a completion notification
     */
    private void sendDiscordNotification(String message, BufferedImage screenshot, boolean isCompletion) {
        if (client.getLocalPlayer() == null) {
            log.debug("Not sending Discord notification because player is not logged in");
            return;
        }

        String webhookUrl = config.discordWebhookUrl();
        if (isValidWebhookUrl(webhookUrl)) {
            return;
        }

        boolean shouldSendScreenshot = config.sendScreenshot() && screenshot != null;

        byte[] imageBytes = null;
        if (shouldSendScreenshot) {
            imageBytes = imageToByteArray(screenshot);
            shouldSendScreenshot = imageBytes != null;
        }
        
        if (config.sendScreenshot() && (screenshot == null || imageBytes == null)) {
            log.warn("Screenshot capture or conversion failed, sending notification without screenshot");
        }
        
        log.debug("Sending Discord notification: {} (with screenshot: {}, completion: {})", 
                message, shouldSendScreenshot, isCompletion);

        DiscordNotification notification = new DiscordNotification(message, 
                shouldSendScreenshot ? screenshot : null, isCompletion);
        
        executor.execute(() -> sendDiscordNotificationAsync(notification, webhookUrl.trim()));
    }

    /**
     * Asynchronously sends a Discord notification
     * 
     * @param notification The notification to send
     * @param webhookUrl The Discord webhook URL
     */
    private void sendDiscordNotificationAsync(DiscordNotification notification, String webhookUrl) {
        if (isValidWebhookUrl(webhookUrl)) {
            return;
        }
        
        try {
            if (!notification.hasScreenshot()) {
                log.debug("Sending text-only Discord notification");
                sendTextOnlyDiscordMessage(notification, webhookUrl);
                return;
            }

            log.debug("Sending Discord notification with screenshot");
            sendDiscordMessageWithScreenshot(notification, webhookUrl);
        } catch (Exception e) {
            log.warn("Error sending Discord notification", e);
            if (notification.hasScreenshot()) {
                try {
                    log.debug("Attempting to send text-only notification as fallback");
                    DiscordNotification textOnlyNotification = new DiscordNotification(
                            notification.getMessage() + " (screenshot failed)", null, notification.isCompletion());
                    sendTextOnlyDiscordMessage(textOnlyNotification, webhookUrl);
                } catch (Exception fallbackException) {
                    log.warn("Failed to send fallback text-only Discord notification", fallbackException);
                }
            }
        }
    }
    
    /**
     * Sends a text-only Discord message
     * 
     * @param notification The notification to send
     * @param webhookUrl The Discord webhook URL
     */
    private void sendTextOnlyDiscordMessage(DiscordNotification notification, String webhookUrl) throws IOException {
        if (isValidWebhookUrl(webhookUrl)) {
            return;
        }
        
        try {
            JsonObject json = createDiscordPayload(notification);
            RequestBody body = RequestBody.create(JSON, json.toString());
            Request request = new Request.Builder()
                    .url(webhookUrl)
                    .post(body)
                    .build();
    
            try (Response response = okHttpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String responseBody = extractResponseBody(response);
                    log.warn("Error sending text-only Discord message: {} ({}), response: {}", 
                            response.code(), response.message(), responseBody);
                } else {
                    log.debug("Successfully sent text-only Discord message");
                }
            }
        } catch (Exception e) {
            log.warn("Unexpected error sending text-only Discord message", e);
            throw new IOException("Failed to send Discord message", e);
        }
    }
    
    /**
     * Converts a BufferedImage to a byte array in PNG format
     * 
     * @param screenshot The screenshot to convert
     * @return The byte array or null if conversion failed
     */
    private byte[] imageToByteArray(BufferedImage screenshot) {
        if (screenshot == null) {
            return null;
        }

        if (screenshot.getWidth() <= 0 || screenshot.getHeight() <= 0) {
            log.warn("Invalid screenshot dimensions: {}x{}", 
                    screenshot.getWidth(), screenshot.getHeight());
            return null;
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            boolean success = ImageIO.write(screenshot, "png", baos);
            if (!success) {
                log.warn("Failed to write screenshot to PNG format");
                return null;
            }
            
            byte[] imageBytes = baos.toByteArray();
            if (imageBytes.length == 0) {
                log.warn("Screenshot converted to empty byte array");
                return null;
            }

            if (imageBytes.length > 8 * 1024 * 1024) {
                log.warn("Screenshot is too large for Discord ({} bytes)", imageBytes.length);
                return null;
            }
            
            return imageBytes;
        } catch (Exception e) {
            log.warn("Error writing screenshot to PNG format", e);
            return null;
        }
    }
    
    /**
     * Sends a Discord message with a screenshot
     * 
     * @param notification The notification to send
     * @param webhookUrl The Discord webhook URL
     */
    private void sendDiscordMessageWithScreenshot(DiscordNotification notification, String webhookUrl) throws IOException {
        try {
            if (isValidWebhookUrl(webhookUrl)) {
                return;
            }

            byte[] imageBytes = imageToByteArray(notification.getScreenshot());
            if (imageBytes == null) {
                log.warn("Cannot send Discord message with screenshot, falling back to text-only");
                sendTextOnlyDiscordMessage(notification, webhookUrl);
                return;
            }

            JsonObject json = createDiscordPayload(notification);

            MultipartBody.Builder requestBuilder = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM);

            requestBuilder.addFormDataPart("payload_json", json.toString());

            requestBuilder.addFormDataPart("file", "screenshot.png", 
                    RequestBody.create(MEDIA_TYPE_PNG, imageBytes));

            Request request = new Request.Builder()
                    .url(webhookUrl)
                    .post(requestBuilder.build())
                    .build();
            
            try (Response response = okHttpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String responseBody = extractResponseBody(response);
                    log.warn("Error sending Discord message with screenshot: {} ({}), response: {}", 
                            response.code(), response.message(), responseBody);

                    log.info("Discord rejected the screenshot, falling back to text-only");
                    sendTextOnlyDiscordMessage(notification, webhookUrl);
                } else {
                    log.debug("Successfully sent Discord message with screenshot");
                }
            }
        } catch (Exception e) {
            log.warn("Error sending Discord message with screenshot", e);
            try {
                sendTextOnlyDiscordMessage(notification, webhookUrl);
            } catch (Exception fallbackException) {
                log.warn("Failed to send fallback text-only Discord message", fallbackException);
            }
        }
    }

    /**
     * Loads items from the config based on the selected source type
     */
    private void loadItems() {
        items.clear();
        itemsByName.clear();

        if (config.itemSourceType() == BingoConfig.ItemSourceType.MANUAL) {
            if (!config.remoteUrl().isEmpty()) {
                clientThread.invoke(() -> {
                    if (client.getLocalPlayer() != null) {
                        client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                                "Warning: Remote URL is configured but not being used because you're in Manual mode.", "");
                    }
                });
            }
            loadItemsFromManualList();
        } else if (config.itemSourceType() == BingoConfig.ItemSourceType.REMOTE) {

            if (!config.itemList().equals("Dragon Scimitar\nAbyssal Whip\nFire Cape\nBarrows Gloves\nDragon Boots") &&
                    !config.itemList().isEmpty()) {
                clientThread.invoke(() -> {
                    if (client.getLocalPlayer() != null) {
                        client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                                "Warning: Manual Bingo Items are configured but not being used because you're in Remote URL mode.", "");
                    }
                });
            }

            if (!config.remoteUrl().isEmpty()) {
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
        if (!config.itemList().isEmpty()) {
            Arrays.stream(config.itemList().split("\\n"))
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
     * Loads saved obtained items from config
     */
    private void loadSavedItems() {
        if (!config.persistObtained()) {
            return;
        }

        String savedItems = configManager.getConfiguration(CONFIG_GROUP, CONFIG_KEY_OBTAINED_ITEMS);
        if (savedItems == null || savedItems.isEmpty()) {
            return;
        }

        Arrays.stream(savedItems.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(name -> {
                    // Use the map for faster lookups
                    BingoItem item = itemsByName.get(name.toLowerCase());
                    if (item != null) {
                        item.setObtained(true);
                    }
                });

        SwingUtilities.invokeLater(() -> panel.updateItems(items));
    }

    /**
     * Saves obtained items to config
     */
    private void saveObtainedItems() {
        if (!config.persistObtained()) {
            return;
        }

        String obtainedItems = items.stream()
                .filter(BingoItem::isObtained)
                .map(BingoItem::getName)
                .collect(Collectors.joining(","));

        configManager.setConfiguration(CONFIG_GROUP, CONFIG_KEY_OBTAINED_ITEMS, obtainedItems);
    }

    /**
     * Schedules periodic updates from remote URL
     */
    private void scheduleRemoteUpdate() {
        executor.scheduleAtFixedRate(this::updateRemoteItems,
                0, config.refreshInterval(), TimeUnit.MINUTES);
    }

    /**
     * Updates items from remote URL
     */
    private void updateRemoteItems() {
        if (config.remoteUrl().isEmpty()) {
            return;
        }

        try {
            String url = config.remoteUrl();
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
     * Creates a Discord message payload based on the notification type
     * 
     * @param notification The notification to create a payload for
     * @return The JSON payload
     */
    private JsonObject createDiscordPayload(DiscordNotification notification) {
        JsonObject json = new JsonObject();

        if (notification.isCompletion()) {
            json.addProperty("content", "🎉 **" + notification.getMessage() + "** 🎉");
        } else {
            json.addProperty("content", notification.getMessage());
        }
        
        return json;
    }
    
    /**
     * Validates a webhook URL
     * 
     * @param webhookUrl The webhook URL to validate
     * @return True if valid, false otherwise
     */
    private boolean isValidWebhookUrl(String webhookUrl) {
        if (webhookUrl == null || webhookUrl.trim().isEmpty()) {
            log.warn("Discord webhook URL is null or empty");
            return true;
        }

        if (!webhookUrl.startsWith("https://discord.com/api/webhooks/") && 
            !webhookUrl.startsWith("https://discordapp.com/api/webhooks/")) {
            log.warn("Invalid Discord webhook URL format: {}", webhookUrl);
            return true;
        }
        
        return false;
    }
    
    /**
     * Extracts and logs the response body from a Discord API response
     * 
     * @param response The response to extract the body from
     * @return The response body or an empty string if not available
     */
    private String extractResponseBody(Response response) {
        if (response == null || response.body() == null) {
            return "";
        }
        
        try {
            return response.body().string();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Resets the bingo board after showing a confirmation dialog
     * Preserves the Discord webhook URL
     */
    public void resetBingoBoard() {
        int result = javax.swing.JOptionPane.showConfirmDialog(
                panel,
                "Are you sure you want to reset the bingo board?\nAll progress will be deleted.",
                "Reset Bingo Board",
                javax.swing.JOptionPane.YES_NO_OPTION,
                javax.swing.JOptionPane.WARNING_MESSAGE
        );

        if (result == javax.swing.JOptionPane.YES_OPTION) {
            configManager.unsetConfiguration(CONFIG_GROUP, CONFIG_KEY_OBTAINED_ITEMS);

            for (BingoItem item : items) {
                item.setObtained(false);
            }

            SwingUtilities.invokeLater(() -> panel.updateItems(items));

            if (client.getLocalPlayer() != null) {
                client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                        "Bingo board has been reset.", "");
            }

            log.debug("Bingo board reset while preserving Discord webhook URL: {}",
                    config.discordWebhookUrl().isEmpty() ? "not set" : "set");
        }
    }

    /**
     * Manually triggers an update from the remote URL
     */
    public void updateRemoteItemsManually() {
        updateRemoteItems();
    }
}