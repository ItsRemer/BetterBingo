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

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;

import net.runelite.client.util.ImageCapture;

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

    @Override
    protected void startUp() throws Exception {
        panel = injector.getInstance(BingoPanel.class);

        final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "panel_icon.png");

        navButton = NavigationButton.builder()
                .tooltip("Bingo")
                .icon(icon)
                .priority(5)
                .panel(panel)
                .build();

        clientToolbar.addNavigation(navButton);

        loadItems();
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
        if (!event.getGroup().equals("bingo")) {
            return;
        }

        if (event.getKey().equals("itemSourceType") ||
                event.getKey().equals("itemList") ||
                event.getKey().equals("remoteUrl")) {
            loadItems();

            if (config.itemSourceType() == BingoConfig.ItemSourceType.REMOTE) {
                scheduleRemoteUpdate();
            }
        }
        else if (event.getKey().equals("refreshInterval") && config.itemSourceType() == BingoConfig.ItemSourceType.REMOTE) {
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

    @Subscribe
    public void onChatMessage(net.runelite.api.events.ChatMessage event) {
        if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM) {
            return;
        }

        String message = event.getMessage();

        if (message.contains("Valuable drop:") || message.contains("You receive a drop:")) {
            String itemName = message.substring(message.indexOf(":") + 2);
            if (itemName.contains(" x ")) {
                itemName = itemName.substring(itemName.indexOf("x ") + 2);
            }

            checkForItem(itemName);
        } else if (message.contains("You have a funny feeling like")) {
            String itemName = message.substring(0, message.indexOf("like you") - 1)
                    .replace("You have a funny feeling ", "");
            checkForItem(itemName);
        } else if (message.contains("New item added to your collection log:")) {
            String itemName = message.substring(message.indexOf(":") + 2);
            checkForItem(itemName);
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
     * Checks if an item is on the bingo list and marks it as obtained if found
     *
     * @param itemName The name of the item to check
     */
    private void checkForItem(String itemName) {
        boolean updated = false;

        for (BingoItem bingoItem : items) {
            if (bingoItem.isObtained()) {
                continue;
            }

            if (bingoItem.getName().equalsIgnoreCase(itemName)) {
                bingoItem.setObtained(true);
                updated = true;

                if (bingoItem.getItemId() == -1) {
                    List<ItemPrice> searchResults = itemManager.search(itemName);
                    if (!searchResults.isEmpty()) {
                        bingoItem.setItemId(searchResults.get(0).getId());
                    }
                }

                executor.schedule(() -> {
                    if (client.getLocalPlayer() != null) {
                        BufferedImage screenshot = captureScreenshot();
                        if (screenshot != null) {
                            log.debug("Screenshot captured for item: {} ({}x{})",
                                    itemName, screenshot.getWidth(), screenshot.getHeight());
                        } else {
                            log.warn("Failed to capture screenshot for item: {}", itemName);
                        }
                        sendDiscordNotification(client.getLocalPlayer().getName() + " has obtained: " + bingoItem.getName(), screenshot);
                    }
                }, 600, TimeUnit.MILLISECONDS);

                if (client.getLocalPlayer() != null) {
                    client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                            "Bingo item obtained: " + itemName, "");
                }
                break;
            }
        }

        if (updated) {
            saveObtainedItems();
            SwingUtilities.invokeLater(() -> panel.updateItems(items));
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
     * Captures a screenshot of the game
     *
     * @return The captured screenshot or null if failed
     */
    private BufferedImage captureScreenshot() {
        if (!config.sendScreenshot()) {
            log.debug("Screenshot capture disabled by config");
            return null;
        }

        AtomicReference<BufferedImage> imageRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        clientThread.invoke(() -> {
            drawManager.requestNextFrameListener(image -> {
                try {
                    BufferedImage screenshot = new BufferedImage(
                            image.getWidth(null),
                            image.getHeight(null),
                            BufferedImage.TYPE_INT_ARGB
                    );

                    Graphics2D graphics = screenshot.createGraphics();
                    graphics.drawImage(image, 0, 0, null);
                    graphics.dispose();

                    imageRef.set(screenshot);
                } finally {
                    latch.countDown();
                }
            });
        });

        try {
            if (!latch.await(1, TimeUnit.SECONDS)) {
                log.warn("Screenshot capture timed out");
                return null;
            }

            BufferedImage screenshot = imageRef.get();
            if (screenshot != null) {
                log.debug("Screenshot captured successfully: {}x{}",
                        screenshot.getWidth(), screenshot.getHeight());
                return screenshot;
            } else {
                log.warn("Screenshot capture failed - image is null");
                return null;
            }
        } catch (InterruptedException e) {
            log.warn("Screenshot capture interrupted", e);
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * Sends a notification to Discord
     *
     * @param message    The message to send
     * @param screenshot The screenshot to attach (can be null)
     */
    private void sendDiscordNotification(String message, BufferedImage screenshot) {
        if (client.getLocalPlayer() == null) {
            log.debug("Not sending Discord notification because player is not logged in");
            return;
        }

        String webhookUrl = config.discordWebhookUrl();
        if (webhookUrl == null || webhookUrl.isEmpty()) {
            log.warn("Discord webhook URL is not configured");
            return;
        }

        executor.execute(() -> {
            try {
                if (screenshot == null) {
                    sendTextOnlyDiscordMessage(message, webhookUrl);
                    return;
                }

                sendDiscordMessageWithScreenshot(message, screenshot, webhookUrl);
            } catch (Exception e) {
                log.warn("Error sending Discord notification", e);
            }
        });
    }

    /**
     * Sends a text-only message to Discord
     *
     * @param message    The message to send
     * @param webhookUrl The Discord webhook URL
     */
    private void sendTextOnlyDiscordMessage(String message, String webhookUrl) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("content", message);

        Request request = new Request.Builder()
                .url(webhookUrl)
                .post(RequestBody.create(JSON, json.toString()))
                .build();

        try (Response response = okHttpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String responseBody = response.body() != null ? response.body().string() : "null";
                log.warn("Failed to send Discord notification: {} - {}", response.code(), responseBody);
            } else {
                log.debug("Discord notification (text only) sent successfully");
            }
        }
    }

    /**
     * Sends a message with screenshot to Discord
     *
     * @param message    The message to send
     * @param screenshot The screenshot to attach
     * @param webhookUrl The Discord webhook URL
     */
    private void sendDiscordMessageWithScreenshot(String message, BufferedImage screenshot, String webhookUrl) throws IOException {
        log.debug("Preparing to send screenshot: {}x{}", screenshot.getWidth(), screenshot.getHeight());

        byte[] screenshotBytes;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            if (!ImageIO.write(screenshot, "png", baos)) {
                log.warn("Failed to encode screenshot as PNG");
                return;
            }
            screenshotBytes = baos.toByteArray();
            log.debug("Screenshot encoded successfully: {} bytes", screenshotBytes.length);
        }

        JsonObject json = createDiscordPayloadWithEmbed(message);

        MultipartBody.Builder multipartBuilder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("payload_json", json.toString())
                .addFormDataPart(
                        "files[0]",
                        "screenshot.png",
                        RequestBody.create(MEDIA_TYPE_PNG, screenshotBytes)
                );

        Request request = new Request.Builder()
                .url(webhookUrl)
                .post(multipartBuilder.build())
                .build();

        try (Response response = okHttpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String responseBody = response.body() != null ? response.body().string() : "null";
                log.warn("Failed to send Discord notification: {} - {}", response.code(), responseBody);
            } else {
                log.debug("Discord notification (with embedded screenshot) sent successfully");
            }
        }
    }

    /**
     * Creates a Discord payload JSON with embedded image
     *
     * @param message The message content
     * @return The JSON object for the payload
     */
    private JsonObject createDiscordPayloadWithEmbed(String message) {
        JsonObject json = new JsonObject();
        json.addProperty("content", message);

        JsonObject embedObj = new JsonObject();
        JsonObject imageObj = new JsonObject();
        imageObj.addProperty("url", "attachment://screenshot.png");
        embedObj.add("image", imageObj);

        com.google.gson.JsonArray embedsArray = new com.google.gson.JsonArray();
        embedsArray.add(embedObj);
        json.add("embeds", embedsArray);

        com.google.gson.JsonArray attachmentsArray = new com.google.gson.JsonArray();
        JsonObject attachmentObj = new JsonObject();
        attachmentObj.addProperty("id", 0);
        attachmentObj.addProperty("description", "Screenshot file");
        attachmentsArray.add(attachmentObj);
        json.add("attachments", attachmentsArray);

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
        if (config.remoteUrl().isEmpty()) {
            clientThread.invoke(() -> {
                if (client.getLocalPlayer() != null) {
                    client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                            "Remote URL is not configured.", "");
                }
            });
            return;
        }

        // Show confirmation dialog
        int result = javax.swing.JOptionPane.showConfirmDialog(
                panel,
                "Are you sure you want to update items from the remote URL?\n" +
                        "This might change your item list and could reset progress for items that are no longer on the list.",
                "Update From URL",
                javax.swing.JOptionPane.YES_NO_OPTION,
                javax.swing.JOptionPane.WARNING_MESSAGE
        );

        // Only proceed if user confirms
        if (result == javax.swing.JOptionPane.YES_OPTION) {
            clientThread.invoke(() -> {
                if (client.getLocalPlayer() != null) {
                    client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                            "Updating bingo items from remote URL...", "");
                }
            });

            executor.execute(this::updateRemoteItems);
        }
    }
}