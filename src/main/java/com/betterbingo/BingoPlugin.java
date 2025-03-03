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

import java.util.function.Function;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

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
    private static final int GRID_SIZE = 5; // 5x5 bingo board
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
    private final Set<Integer> recentlyKilledNpcs = new HashSet<>();
    private BingoPanel panel;
    private NavigationButton navButton;

    /**
     * Represents a pattern for matching chat messages related to item drops
     */
    private static class ChatMessagePattern {
        private final String pattern;
        private final Function<String, String> itemExtractor;
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

        public boolean isMultiItem() {
            return multiItem;
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
     * Configuration for screenshot capture
     */
    private static class ScreenshotConfig {
        private final int maxAttempts;
        private final int delayBetweenAttempts;
        private final int timeoutMillis;
        
        public ScreenshotConfig(int maxAttempts, int delayBetweenAttempts, int timeoutMillis) {
            this.maxAttempts = maxAttempts;
            this.delayBetweenAttempts = delayBetweenAttempts;
            this.timeoutMillis = timeoutMillis;
        }
    }
    
    private final ScreenshotConfig screenshotConfig = new ScreenshotConfig(3, 500, 2000);
    
    /**
     * Captures a screenshot with multiple attempts if needed
     * 
     * @return The captured screenshot or null if capture failed
     */
    private BufferedImage captureScreenshot() {
        if (!config.sendScreenshot()) {
            log.debug("Screenshot capture disabled by config");
            return null;
        }

        // Add a small delay before attempting to capture screenshot
        // This helps ensure the game UI is fully rendered
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        for (int attempt = 1; attempt <= screenshotConfig.maxAttempts; attempt++) {
            log.debug("Capturing screenshot (attempt {}/{})", attempt, screenshotConfig.maxAttempts);
            BufferedImage screenshot = captureScreenshotAttempt();
            
            if (screenshot != null) {
                log.debug("Screenshot captured successfully on attempt {}: {}x{}",
                        attempt, screenshot.getWidth(), screenshot.getHeight());
                return screenshot;
            }
            
            if (attempt < screenshotConfig.maxAttempts) {
                try {
                    log.debug("Waiting {}ms before next screenshot attempt", screenshotConfig.delayBetweenAttempts);
                    Thread.sleep(screenshotConfig.delayBetweenAttempts);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        
        log.warn("All screenshot capture attempts failed");
        return null;
    }
    
    /**
     * Attempts to capture a screenshot once
     * 
     * @return The captured screenshot or null if capture failed
     */
    private BufferedImage captureScreenshotAttempt() {
        try {
            // Check if client is valid
            if (client == null || client.getCanvas() == null) {
                log.warn("Cannot capture screenshot: client or canvas is null");
                return null;
            }
            
            CompletableFuture<BufferedImage> future = new CompletableFuture<>();
            
            drawManager.requestNextFrameListener(image -> {
                try {
                    // Check if image is null before processing
                    if (image == null) {
                        log.warn("Received null image from drawManager");
                        future.complete(null);
                        return;
                    }
                    
                    // Get dimensions safely
                    int width = image.getWidth(null);
                    int height = image.getHeight(null);
                    
                    // Validate dimensions
                    if (width <= 0 || height <= 0) {
                        log.warn("Invalid image dimensions: {}x{}", width, height);
                        future.complete(null);
                        return;
                    }
                    
                    BufferedImage screenshot = new BufferedImage(
                            width, height, BufferedImage.TYPE_INT_ARGB);
                    Graphics graphics = screenshot.getGraphics();
                    graphics.drawImage(image, 0, 0, null);
                    graphics.dispose();
                    
                    future.complete(screenshot);
                } catch (Exception e) {
                    log.warn("Exception while processing screenshot image", e);
                    future.completeExceptionally(e);
                }
            });
            
            // Use a slightly longer timeout to ensure we get the frame
            return future.get(screenshotConfig.timeoutMillis + 500, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("Screenshot capture timed out after {}ms", screenshotConfig.timeoutMillis + 500);
            return null;
        } catch (InterruptedException e) {
            log.warn("Screenshot capture interrupted");
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            log.warn("Error during screenshot capture", e.getCause());
            return null;
        } catch (Exception e) {
            log.warn("Unexpected error during screenshot capture", e);
            return null;
        }
    }

    /**
     * Extracts an item name from a string, handling quantity indicators
     */
    private String cleanItemName(String rawItemName) {
        if (rawItemName == null) {
            return null;
        }
        
        String itemName = rawItemName.trim();

        if (itemName.contains(" x ")) {
            if (itemName.indexOf("x ") > 0) {
                itemName = itemName.substring(0, itemName.indexOf(" x "));
            } else {
                itemName = itemName.substring(itemName.indexOf("x ") + 2);
            }
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
     * Finds a bingo item by name and marks it as obtained if found
     * 
     * @param itemName The name of the item to check
     */
    private void checkForItem(String itemName) {
        ObtainedItem obtainedItem = findAndMarkItem(itemName);
        
        if (obtainedItem != null) {
            notifyItemObtained(obtainedItem);
            checkForCompletions(obtainedItem.getIndex());
        }
    }
    
    /**
     * Finds a bingo item by name and marks it as obtained
     * 
     * @param itemName The name of the item to find
     * @return The obtained item, or null if not found or already obtained
     */
    private ObtainedItem findAndMarkItem(String itemName) {
        for (int i = 0; i < items.size(); i++) {
            BingoItem bingoItem = items.get(i);
            if (bingoItem.isObtained()) {
                continue;
            }

            if (bingoItem.getName().equalsIgnoreCase(itemName)) {
                bingoItem.setObtained(true);
                log.debug("Item obtained: {} at index {}", itemName, i);

                if (bingoItem.getItemId() == -1) {
                    lookupItemId(bingoItem);
                }
                
                saveObtainedItems();
                SwingUtilities.invokeLater(() -> panel.updateItems(items));
                
                return new ObtainedItem(bingoItem, i);
            }
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
        final BingoItem bingoItem = obtainedItem.getBingoItem();
        final String itemName = bingoItem.getName();

        executor.schedule(() -> {
            clientThread.invoke(() -> {
                if (client.getLocalPlayer() != null) {
                    String chatMessage = "<col=00ff00>BINGO!</col> <col=ffffff>You've obtained:</col> <col=ffff00>" + itemName + "</col>";
                    client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", chatMessage, "");

                    executor.execute(() -> {
                        BufferedImage screenshot = null;
                        if (config.sendScreenshot()) {
                            screenshot = captureScreenshot();
                            if (screenshot == null) {
                                log.warn("Failed to capture screenshot for item: {}, sending text-only notification", itemName);
                            }
                        } else {
                            log.debug("Screenshots disabled, not capturing for item: {}", itemName);
                        }

                        String message = client.getLocalPlayer().getName() + " has obtained: " + bingoItem.getName();
                        sendDiscordNotification(message, screenshot, false);
                    });
                }
            });
        }, 300, TimeUnit.MILLISECONDS);
    }
    
    /**
     * Checks for completions after an item has been obtained
     * 
     * @param obtainedItemIndex The index of the obtained item
     */
    private void checkForCompletions(int obtainedItemIndex) {
        boolean fullBoardComplete = true;
        for (BingoItem item : items) {
            if (!item.isObtained()) {
                fullBoardComplete = false;
                break;
            }
        }
        
        if (fullBoardComplete) {
            notifyBoardCompletion("FULL BOARD COMPLETE! Congratulations!");
            return;
        }

        int row = obtainedItemIndex / GRID_SIZE;
        int col = obtainedItemIndex % GRID_SIZE;

        boolean rowComplete = true;
        boolean colComplete = true;

        for (int c = 0; c < GRID_SIZE; c++) {
            int index = row * GRID_SIZE + c;
            if (index >= items.size() || !items.get(index).isObtained()) {
                rowComplete = false;
                break;
            }
        }

        for (int r = 0; r < GRID_SIZE; r++) {
            int index = r * GRID_SIZE + col;
            if (index >= items.size() || !items.get(index).isObtained()) {
                colComplete = false;
                break;
            }
        }

        boolean diag1Complete = false;
        if (row == col) {
            diag1Complete = true;
            for (int i = 0; i < GRID_SIZE; i++) {
                int index = i * GRID_SIZE + i;
                if (index >= items.size() || !items.get(index).isObtained()) {
                    diag1Complete = false;
                    break;
                }
            }
        }

        boolean diag2Complete = false;
        if (row + col == GRID_SIZE - 1) {
            diag2Complete = true;
            for (int i = 0; i < GRID_SIZE; i++) {
                int index = i * GRID_SIZE + (GRID_SIZE - 1 - i);
                if (index >= items.size() || !items.get(index).isObtained()) {
                    diag2Complete = false;
                    break;
                }
            }
        }

        final boolean finalRowComplete = rowComplete;
        final boolean finalColComplete = colComplete;
        final boolean finalDiag1Complete = diag1Complete;
        final boolean finalDiag2Complete = diag2Complete;
        final int finalRow = row;
        final int finalCol = col;

        executor.schedule(() -> {
            if (finalRowComplete) {
                notifyBoardCompletion("ROW " + (finalRow + 1) + " COMPLETE! Bingo!");
            }
            
            if (finalColComplete) {
                notifyBoardCompletion("COLUMN " + (finalCol + 1) + " COMPLETE! Bingo!");
            }
            
            if (finalDiag1Complete) {
                notifyBoardCompletion("DIAGONAL 1 COMPLETE! Bingo!");
            }
            
            if (finalDiag2Complete) {
                notifyBoardCompletion("DIAGONAL 2 COMPLETE! Bingo!");
            }
        }, 500, TimeUnit.MILLISECONDS);

        if (!rowComplete && !colComplete && !diag1Complete && !diag2Complete && config.completionNotifications()) {
            executor.schedule(() -> checkForCompletionsGeneral(), 1000, TimeUnit.MILLISECONDS);
        }
    }
    
    /**
     * Checks for completed rows, columns, and diagonals across the entire board
     */
    private void checkForCompletionsGeneral() {
        if (items.size() <= 0) {
            return;
        }

        boolean fullBoardComplete = true;
        for (BingoItem item : items) {
            if (!item.isObtained()) {
                fullBoardComplete = false;
                break;
            }
        }
        
        if (fullBoardComplete) {
            notifyBoardCompletion("FULL BOARD COMPLETE! Congratulations!");
            return;
        }

        for (int row = 0; row < GRID_SIZE; row++) {
            boolean rowComplete = true;
            for (int col = 0; col < GRID_SIZE; col++) {
                int index = row * GRID_SIZE + col;
                if (index >= items.size() || !items.get(index).isObtained()) {
                    rowComplete = false;
                    break;
                }
            }
            
            if (rowComplete) {
                notifyBoardCompletion("ROW " + (row + 1) + " COMPLETE! Bingo!");
            }
        }

        for (int col = 0; col < GRID_SIZE; col++) {
            boolean colComplete = true;
            for (int row = 0; row < GRID_SIZE; row++) {
                int index = row * GRID_SIZE + col;
                if (index >= items.size() || !items.get(index).isObtained()) {
                    colComplete = false;
                    break;
                }
            }
            
            if (colComplete) {
                notifyBoardCompletion("COLUMN " + (col + 1) + " COMPLETE! Bingo!");
            }
        }

        boolean diag1Complete = true;
        for (int i = 0; i < GRID_SIZE; i++) {
            int index = i * GRID_SIZE + i;
            if (index >= items.size() || !items.get(index).isObtained()) {
                diag1Complete = false;
                break;
            }
        }
        
        if (diag1Complete) {
            notifyBoardCompletion("DIAGONAL 1 COMPLETE! Bingo!");
        }

        boolean diag2Complete = true;
        for (int i = 0; i < GRID_SIZE; i++) {
            int index = i * GRID_SIZE + (GRID_SIZE - 1 - i);
            if (index >= items.size() || !items.get(index).isObtained()) {
                diag2Complete = false;
                break;
            }
        }
        
        if (diag2Complete) {
            notifyBoardCompletion("DIAGONAL 2 COMPLETE! Bingo!");
        }
    }

    /**
     * Sends a special notification for bingo completions
     *
     * @param message The completion message
     */
    private void notifyBoardCompletion(String message) {
        log.debug("Sending board completion notification: {}", message);

        final String formattedMessage = getMessageType(message);

        clientThread.invoke(() -> {
            if (client.getLocalPlayer() != null) {
                log.debug("Sending in-game completion notification");
                client.addChatMessage(ChatMessageType.BROADCAST, "", formattedMessage, "");

                executor.execute(() -> {
                    BufferedImage screenshot = null;
                    if (config.sendScreenshot()) {
                        log.debug("Capturing screenshot for completion notification");
                        screenshot = captureScreenshot();
                        if (screenshot == null) {
                            log.warn("Failed to capture screenshot for completion, sending text-only notification");
                        }
                    } else {
                        log.debug("Screenshots disabled, not capturing for completion");
                    }

                    String discordMessage = client.getLocalPlayer().getName() + " - " + message;
                    
                    log.debug("Sending Discord completion notification: {}", discordMessage);
                    sendCompletionDiscordNotification(discordMessage, screenshot);
                });
            } else {
                log.debug("Player is null, not sending completion notification");
            }
        });
    }

    private static String getMessageType(String message) {
        String formattedMessage;

        if (message.contains("ROW")) {
            formattedMessage = "<col=00ffff>BINGO! </col> <col=ffffff>You've completed</col> <col=ffff00>ROW " +
                message.substring(message.indexOf("ROW") + 4, message.indexOf("COMPLETE") - 1) +
                "</col> <col=ffffff>!</col>";
        } else if (message.contains("COLUMN")) {
            formattedMessage = "<col=00ffff>BINGO! </col> <col=ffffff>You've completed</col> <col=ffff00>COLUMN " +
                message.substring(message.indexOf("COLUMN") + 7, message.indexOf("COMPLETE") - 1) +
                "</col> <col=ffffff>!</col>";
        } else if (message.contains("DIAGONAL")) {
            formattedMessage = "<col=00ffff>BINGO! </col> <col=ffffff>You've completed a</col> <col=ffff00>DIAGONAL LINE" + "</col> <col=ffffff>!</col>";
        } else if (message.contains("FULL BOARD")) {
            formattedMessage = "<col=ff00ff>CONGRATULATIONS! </col> <col=ffffff>You've completed the</col> <col=ffff00>ENTIRE BOARD</col> <col=ffffff>!</col>";
        } else {
            formattedMessage = "<col=00ffff>BINGO!</col> " + message;
        }
        return formattedMessage;
    }

    /**
     * Represents a Discord notification with optional screenshot
     */
    private static class DiscordNotification {
        private final String message;
        private final BufferedImage screenshot;
        private final boolean isCompletion;
        
        public DiscordNotification(String message, BufferedImage screenshot, boolean isCompletion) {
            this.message = message;
            this.screenshot = screenshot;
            this.isCompletion = isCompletion;
        }
        
        public String getMessage() {
            return message;
        }
        
        public BufferedImage getScreenshot() {
            return screenshot;
        }
        
        public boolean hasScreenshot() {
            return screenshot != null;
        }
        
        public boolean isCompletion() {
            return isCompletion;
        }
    }
    
    /**
     * Sends a Discord notification for an item or completion
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
        if (webhookUrl == null || webhookUrl.trim().isEmpty()) {
            log.warn("Discord webhook URL is not configured");
            return;
        }
        
        // Basic validation of webhook URL format
        if (!webhookUrl.startsWith("https://discord.com/api/webhooks/") && 
            !webhookUrl.startsWith("https://discordapp.com/api/webhooks/")) {
            log.warn("Invalid Discord webhook URL format: {}", webhookUrl);
            return;
        }
        
        // Validate screenshot before attempting to use it
        boolean shouldSendScreenshot = config.sendScreenshot() && 
                                      screenshot != null && 
                                      screenshot.getWidth() > 0 && 
                                      screenshot.getHeight() > 0;
        
        if (config.sendScreenshot() && screenshot == null) {
            log.warn("Screenshot capture failed, sending notification without screenshot");
        }
        
        log.debug("Sending Discord notification: {} (with screenshot: {}, completion: {})", 
                message, shouldSendScreenshot, isCompletion);

        DiscordNotification notification = new DiscordNotification(message, 
                shouldSendScreenshot ? screenshot : null, isCompletion);
        
        executor.execute(() -> sendDiscordNotificationAsync(notification, webhookUrl.trim()));
    }
    
    /**
     * Sends a Discord notification for an item
     * 
     * @param message The notification message
     * @param screenshot The screenshot to include, or null for text-only
     */
    private void sendDiscordNotification(String message, BufferedImage screenshot) {
        sendDiscordNotification(message, screenshot, false);
    }
    
    /**
     * Sends a Discord notification for a completion
     * 
     * @param message The completion message
     * @param screenshot The screenshot to include, or null for text-only
     */
    private void sendCompletionDiscordNotification(String message, BufferedImage screenshot) {
        sendDiscordNotification(message, screenshot, true);
    }
    
    /**
     * Asynchronously sends a Discord notification
     * 
     * @param notification The notification to send
     * @param webhookUrl The Discord webhook URL
     */
    private void sendDiscordNotificationAsync(DiscordNotification notification, String webhookUrl) {
        if (webhookUrl == null || webhookUrl.isEmpty()) {
            log.warn("Cannot send Discord notification: webhook URL is null or empty");
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
            // Try to send text-only as fallback if we were trying to send with screenshot
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
        if (webhookUrl == null || webhookUrl.isEmpty()) {
            log.warn("Cannot send Discord message: webhook URL is null or empty");
            return;
        }
        
        try {
            // Create the simplest possible JSON payload
            JsonObject json = new JsonObject();
            
            // Set the content based on notification type
            if (notification.isCompletion()) {
                json.addProperty("content", "🎉 **" + notification.getMessage() + "** 🎉");
            } else {
                json.addProperty("content", notification.getMessage());
            }
            
            // Send the request
            RequestBody body = RequestBody.create(JSON, json.toString());
            Request request = new Request.Builder()
                    .url(webhookUrl)
                    .post(body)
                    .build();
    
            try (Response response = okHttpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String responseBody = "";
                    try {
                        if (response.body() != null) {
                            responseBody = response.body().string();
                        }
                    } catch (Exception e) {
                        // Ignore error reading response body
                    }
                    
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
     * Sends a Discord message with a screenshot
     * 
     * @param notification The notification to send
     * @param webhookUrl The Discord webhook URL
     */
    private void sendDiscordMessageWithScreenshot(DiscordNotification notification, String webhookUrl) throws IOException {
        try {
            // Validate webhook URL
            if (webhookUrl == null || webhookUrl.trim().isEmpty()) {
                log.warn("Cannot send Discord message: webhook URL is null or empty");
                return;
            }
            
            // Validate screenshot before processing
            BufferedImage screenshot = notification.getScreenshot();
            if (screenshot == null) {
                log.warn("Cannot send Discord message with null screenshot, falling back to text-only");
                sendTextOnlyDiscordMessage(notification, webhookUrl);
                return;
            }
            
            // Validate image dimensions
            if (screenshot.getWidth() <= 0 || screenshot.getHeight() <= 0) {
                log.warn("Invalid screenshot dimensions: {}x{}, falling back to text-only", 
                        screenshot.getWidth(), screenshot.getHeight());
                sendTextOnlyDiscordMessage(notification, webhookUrl);
                return;
            }
            
            // Convert screenshot to bytes
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try {
                boolean success = ImageIO.write(screenshot, "png", baos);
                if (!success) {
                    log.warn("Failed to write screenshot to PNG format, falling back to text-only");
                    sendTextOnlyDiscordMessage(notification, webhookUrl);
                    return;
                }
            } catch (Exception e) {
                log.warn("Error writing screenshot to PNG format", e);
                sendTextOnlyDiscordMessage(notification, webhookUrl);
                return;
            }
            
            byte[] imageBytes = baos.toByteArray();
            if (imageBytes.length == 0) {
                log.warn("Screenshot converted to empty byte array, falling back to text-only");
                sendTextOnlyDiscordMessage(notification, webhookUrl);
                return;
            }
            
            // Check if image is too large (Discord limit is 8MB)
            if (imageBytes.length > 8 * 1024 * 1024) {
                log.warn("Screenshot is too large for Discord ({} bytes), falling back to text-only", 
                        imageBytes.length);
                sendTextOnlyDiscordMessage(notification, webhookUrl);
                return;
            }

            // Create a simpler JSON payload without attachment references
            JsonObject json = new JsonObject();
            
            // Set the content based on notification type
            if (notification.isCompletion()) {
                json.addProperty("content", "🎉 **" + notification.getMessage() + "** 🎉");
            } else {
                json.addProperty("content", notification.getMessage());
            }
            
            // Build the multipart request with the file
            MultipartBody.Builder requestBuilder = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM);
            
            // Add the JSON payload
            requestBuilder.addFormDataPart("payload_json", json.toString());
            
            // Add the file with a simple name
            requestBuilder.addFormDataPart("file", "screenshot.png", 
                    RequestBody.create(MEDIA_TYPE_PNG, imageBytes));
            
            // Build and execute the request
            Request request = new Request.Builder()
                    .url(webhookUrl)
                    .post(requestBuilder.build())
                    .build();
            
            try (Response response = okHttpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String responseBody = "";
                    try {
                        if (response.body() != null) {
                            responseBody = response.body().string();
                        }
                    } catch (Exception e) {
                        // Ignore error reading response body
                    }
                    
                    log.warn("Error sending Discord message with screenshot: {} ({}), response: {}", 
                            response.code(), response.message(), responseBody);
                    
                    // If we get any error, try sending without screenshot
                    log.info("Discord rejected the screenshot, falling back to text-only");
                    sendTextOnlyDiscordMessage(notification, webhookUrl);
                } else {
                    log.debug("Successfully sent Discord message with screenshot");
                }
            }
        } catch (Exception e) {
            log.warn("Error sending Discord message with screenshot", e);
            // Try to send text-only as fallback
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
                                    items.add(new BingoItem(match.getName(), match.getId()));
                                } else {
                                    items.add(new BingoItem(properName));
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
                .forEach(name -> items.stream()
                        .filter(item -> item.getName().equals(name))
                        .findFirst()
                        .ifPresent(item -> item.setObtained(true)));

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
        Map<String, Boolean> obtainedStatus = items.stream()
                .collect(Collectors.toMap(
                        BingoItem::getName,
                        BingoItem::isObtained,
                        (a, b) -> a
                ));

        items.clear();
        remoteItems.forEach(name -> {
            String properName = name.replace("_", " ");
            BingoItem item;

            List<ItemPrice> searchResults = itemManager.search(properName);
            if (!searchResults.isEmpty()) {
                ItemPrice match = searchResults.get(0);
                item = new BingoItem(match.getName(), match.getId());
            } else {
                item = new BingoItem(properName);
            }

            if (obtainedStatus.containsKey(item.getName())) {
                item.setObtained(obtainedStatus.get(item.getName()));
            }

            items.add(item);
        });

        SwingUtilities.invokeLater(() -> panel.updateItems(items));

        final int itemCount = remoteItems.size();
        clientThread.invoke(() -> {
            if (client.getLocalPlayer() != null) {
                client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                        "Bingo items updated (" + itemCount + " items)", "");
            }
        });

        log.debug("Successfully loaded {} items from remote URL", remoteItems.size());
    }

    /**
     * Creates a Discord payload JSON with embedded image using a specific filename
     *
     * @param message The message to send
     * @param filename The filename to use in the attachment URL
     * @return The JSON object for the payload
     */
    private JsonObject createDiscordPayloadWithFilename(String message, String filename) {
        JsonObject json = new JsonObject();
        json.addProperty("content", message);

        // Create attachment object first
        com.google.gson.JsonArray attachmentsArray = new com.google.gson.JsonArray();
        JsonObject attachmentObj = new JsonObject();
        attachmentObj.addProperty("id", 0);
        attachmentObj.addProperty("filename", filename);
        attachmentObj.addProperty("description", "Screenshot file");
        attachmentsArray.add(attachmentObj);
        json.add("attachments", attachmentsArray);

        // Create embed that references the attachment
        JsonObject embedObj = new JsonObject();
        JsonObject imageObj = new JsonObject();
        imageObj.addProperty("url", "attachment://" + filename);
        embedObj.add("image", imageObj);

        com.google.gson.JsonArray embedsArray = new com.google.gson.JsonArray();
        embedsArray.add(embedObj);
        json.add("embeds", embedsArray);

        return json;
    }

    /**
     * Creates a Discord payload JSON with embedded image for completion notifications using a specific filename
     *
     * @param message The completion message
     * @param filename The filename to use in the attachment URL
     * @return The JSON object for the payload
     */
    private JsonObject createCompletionDiscordPayloadWithFilename(String message, String filename) {
        JsonObject json = new JsonObject();
        json.addProperty("content", "🎉 **" + message + "** 🎉");

        // Create attachment object first
        com.google.gson.JsonArray attachmentsArray = new com.google.gson.JsonArray();
        JsonObject attachmentObj = new JsonObject();
        attachmentObj.addProperty("id", 0);
        attachmentObj.addProperty("filename", filename);
        attachmentObj.addProperty("description", "Bingo completion screenshot");
        attachmentsArray.add(attachmentObj);
        json.add("attachments", attachmentsArray);

        // Create embed with completion info
        JsonObject embedObj = new JsonObject();
        embedObj.addProperty("title", "BINGO COMPLETION!");
        embedObj.addProperty("description", message);
        embedObj.addProperty("color", 16776960); // Gold color for celebration

        // Add image that references the attachment
        JsonObject imageObj = new JsonObject();
        imageObj.addProperty("url", "attachment://" + filename);
        embedObj.add("image", imageObj);

        com.google.gson.JsonArray embedsArray = new com.google.gson.JsonArray();
        embedsArray.add(embedObj);
        json.add("embeds", embedsArray);

        return json;
    }

    /**
     * Creates a Discord payload JSON for text-only messages without any attachment references
     *
     * @param message The message to send
     * @param isCompletion Whether this is a completion notification
     * @return The JSON object for the payload
     */
    private JsonObject createTextOnlyDiscordPayload(String message, boolean isCompletion) {
        JsonObject json = new JsonObject();
        
        if (isCompletion) {
            json.addProperty("content", "🎉 **" + message + "** 🎉");
            
            JsonObject embedObj = new JsonObject();
            embedObj.addProperty("title", "BINGO COMPLETION!");
            embedObj.addProperty("description", message);
            embedObj.addProperty("color", 16776960); // Gold color for celebration
            
            com.google.gson.JsonArray embedsArray = new com.google.gson.JsonArray();
            embedsArray.add(embedObj);
            json.add("embeds", embedsArray);
        } else {
            // Simple text message for regular notifications
            json.addProperty("content", message);
        }
        
        return json;
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

    /**
     * Creates a Discord payload JSON with embedded image
     *
     * @param message The message content
     * @return The JSON object for the payload
     */
    private JsonObject createDiscordPayloadWithEmbed(String message) {
        // Generate a unique filename
        String filename = "screenshot_" + System.currentTimeMillis() + ".png";
        return createDiscordPayloadWithFilename(message, filename);
    }

    /**
     * Creates a Discord payload JSON with embedded image for completion notifications
     *
     * @param message The completion message
     * @return The JSON object for the payload
     */
    private JsonObject createCompletionDiscordPayloadWithEmbed(String message) {
        // Generate a unique filename
        String filename = "screenshot_" + System.currentTimeMillis() + ".png";
        return createCompletionDiscordPayloadWithFilename(message, filename);
    }
}