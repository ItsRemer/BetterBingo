package com.betterbingo;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.Gson;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import okhttp3.*;

import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Handles Discord webhook notifications for the Bingo plugin
 */
@Slf4j
@Singleton
public class BingoDiscordNotifier {

    private static final MediaType MEDIA_TYPE_PNG = MediaType.parse("image/png");
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final Client client;
    private final OkHttpClient okHttpClient;
    private final Gson gson;

    /**
     * Represents a notification to be sent to Discord
     */
    public static class DiscordNotification {
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

    @Inject
    public BingoDiscordNotifier(Client client, OkHttpClient okHttpClient, Gson gson) {
        this.client = client;
        this.okHttpClient = okHttpClient;
        this.gson = gson;
    }

    /**
     * Sends a notification to Discord
     *
     * @param message      The message to send
     * @param screenshot   The screenshot to include (can be null)
     * @param isCompletion Whether this is a completion notification
     * @param webhookUrl   The Discord webhook URL
     * @param executor     The executor service to use for async operations
     */
    public void sendNotification(String message, BufferedImage screenshot, boolean isCompletion,
                                 String webhookUrl, ScheduledExecutorService executor) {
        if (webhookUrl == null || webhookUrl.isEmpty()) {
            log.debug("Discord webhook URL not configured, skipping notification");
            return;
        }

        if (!isValidWebhookUrl(webhookUrl)) {
            log.warn("Invalid Discord webhook URL: {}", webhookUrl);
            return;
        }

        DiscordNotification notification = new DiscordNotification(message, screenshot, isCompletion);

        executor.execute(() -> sendDiscordNotificationAsync(notification, webhookUrl));
    }

    /**
     * Sends a notification to Discord asynchronously
     *
     * @param notification The notification to send
     * @param webhookUrl   The Discord webhook URL
     */
    private void sendDiscordNotificationAsync(DiscordNotification notification, String webhookUrl) {
        try {
            if (notification.hasScreenshot()) {
                sendDiscordMessageWithScreenshot(notification, webhookUrl);
            } else {
                sendTextOnlyDiscordMessage(notification, webhookUrl);
            }
        } catch (IOException e) {
            log.warn("Error sending Discord notification", e);
        }
    }

    /**
     * Sends a text-only message to Discord
     *
     * @param notification The notification to send
     * @param webhookUrl   The Discord webhook URL
     */
    private void sendTextOnlyDiscordMessage(DiscordNotification notification, String webhookUrl) throws IOException {
        JsonObject payload = createDiscordPayload(notification);

        RequestBody body = RequestBody.create(
                JSON,
                payload.toString()
        );

        Request request = new Request.Builder()
                .url(webhookUrl)
                .post(body)
                .build();

        try (Response response = okHttpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.warn("Error sending Discord notification: {}", extractResponseBody(response));
            }
        }
    }

    /**
     * Sends a message with a screenshot to Discord
     *
     * @param notification The notification to send
     * @param webhookUrl   The Discord webhook URL
     */
    private void sendDiscordMessageWithScreenshot(DiscordNotification notification, String webhookUrl) throws IOException {
        byte[] imageBytes = imageToByteArray(notification.getScreenshot());

        MultipartBody.Builder requestBodyBuilder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("payload_json", createDiscordPayload(notification).toString());
        requestBodyBuilder.addFormDataPart(
                "file",
                "screenshot.png",
                RequestBody.create(
                        MEDIA_TYPE_PNG,
                        imageBytes
                )
        );

        Request request = new Request.Builder()
                .url(webhookUrl)
                .post(requestBodyBuilder.build())
                .build();

        try (Response response = okHttpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.warn("Error sending Discord notification with screenshot: {}", extractResponseBody(response));
            }
        }
    }

    /**
     * Creates a Discord payload for the notification
     *
     * @param notification The notification to create a payload for
     * @return The Discord payload as a JsonObject
     */
    private JsonObject createDiscordPayload(DiscordNotification notification) {
        JsonObject payload = new JsonObject();

        payload.addProperty("username", notification.isCompletion() ?
                "Bingo Completion" :
                "Bingo Item Obtained");

        String enhancedMessage = notification.getMessage();
        if (client.getLocalPlayer() != null) {
            String playerName = client.getLocalPlayer().getName();
            int worldNumber = client.getWorld();
            enhancedMessage = String.format("%s (Player: %s, World: %d)",
                    enhancedMessage, playerName, worldNumber);
        }

        payload.addProperty("content", enhancedMessage);
        payload.add("embeds", new JsonArray());
        return payload;
    }

    /**
     * Converts a BufferedImage to a byte array
     *
     * @param screenshot The screenshot to convert
     * @return The image as a byte array
     */
    private byte[] imageToByteArray(BufferedImage screenshot) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(screenshot, "png", baos);
        return baos.toByteArray();
    }

    /**
     * Extracts the response body from a Response object
     *
     * @param response The response to extract the body from
     * @return The response body as a string
     */
    private String extractResponseBody(Response response) {
        try {
            return response.body() != null ? response.body().string() : "No response body";
        } catch (IOException e) {
            return "Error reading response body";
        }
    }

    /**
     * Checks if a webhook URL is valid
     *
     * @param webhookUrl The webhook URL to check
     * @return True if the URL is valid
     */
    private boolean isValidWebhookUrl(String webhookUrl) {
        return webhookUrl != null &&
                webhookUrl.startsWith("https://discord.com/api/webhooks/");
    }
} 