package com.betterbingo;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import net.runelite.api.Client;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;
import java.util.concurrent.TimeUnit;

/**
 * Firebase implementation of the TeamStorageStrategy interface without using Firebase SDK.
 */
@Slf4j
@Singleton
public class FirebaseTeamStorage implements TeamStorageStrategy {
    private static final String API_ENDPOINT = "https://betterbingo-api-401942819677.us-central1.run.app";
    private static final String TEAM_CODE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int TEAM_CODE_LENGTH = 8;

    private final OkHttpClient httpClient;
    private final Gson gson;
    private final ScheduledExecutorService executorService;
    private final Random random = new Random();
    private final Client client;

    @Inject
    public FirebaseTeamStorage(OkHttpClient httpClient, Gson gson, ScheduledExecutorService executorService, Client client) {
        // Create a new OkHttpClient with optimized settings
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequests(20);         // Limit total concurrent requests
        dispatcher.setMaxRequestsPerHost(5);   // Limit concurrent requests per host
        
        this.httpClient = new OkHttpClient.Builder()
            .cache(null)                       // Disable caching completely
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .dispatcher(dispatcher)
            .connectionPool(new ConnectionPool(5, 5, TimeUnit.MINUTES)) // Keep up to 5 idle connections for 5 minutes
            .retryOnConnectionFailure(true)    // Retry automatically on connection failures
            .build();
            
        this.gson = gson;
        this.executorService = executorService;
        this.client = client;
        log.info("FirebaseTeamStorage initialized with API endpoint: {}", API_ENDPOINT);
    }
    
    @Override
    public CompletableFuture<Boolean> updateItemObtained(String teamCode, String itemName, boolean obtained) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        
        try {
            log.info("Updating item obtained status for team {}: {} = {}", teamCode, itemName, obtained);
            
            // First, find the target item name (handle groups)
            resolveTargetItemName(teamCode, itemName).thenCompose(targetItemName -> {
                // Update the item via API
                return updateItemViaApi(teamCode, targetItemName, obtained);
            }).thenAccept(success -> {
                future.complete(success);
            }).exceptionally(e -> {
                log.error("Error updating item obtained status", e);
                future.complete(false);
                return null;
            });
        } catch (Exception e) {
            log.error("Error updating item obtained status", e);
            future.complete(false);
        }
        
        return future;
    }

    @Override
    public CompletableFuture<Map<String, Object>> getTeamData(String teamCode) {
        if (teamCode == null || teamCode.isEmpty()) {
            CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalArgumentException("Team code cannot be null or empty"));
            return future;
        }

        final Request request = new Request.Builder()
                .url(API_ENDPOINT + "/teams/" + teamCode)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .get()
                .build();

        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.error("Failed to get team data for team {}: {}", teamCode, e.getMessage());
                future.completeExceptionally(e);
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (response) {
                    if (!response.isSuccessful()) {
                        if (response.code() == 404) {
                            log.error("Team not found in database: {}", teamCode);
                            future.completeExceptionally(new TeamNotFoundException("Team " + teamCode + " not found in database"));
                        } else {
                            log.error("Failed to get team data for team {}: {}", teamCode, response.code());
                            future.completeExceptionally(new IOException("Failed to get team data: " + response.code()));
                        }
                        return;
                    }

                    try {
                        String responseBody = response.body().string();
                        log.debug("Got team data response: {}", responseBody);
                        Map<String, Object> teamData = gson.fromJson(responseBody, Map.class);
                        
                        // Ensure the team data has the teamCode included
                        teamData.put("teamCode", teamCode);
                        
                        // Make sure manualItems exists and is not null
                        if (!teamData.containsKey("manualItems") || teamData.get("manualItems") == null) {
                            log.debug("manualItems field missing or null for team {}, setting empty string", teamCode);
                            teamData.put("manualItems", "");
                        }
                        
                        future.complete(teamData);
                    } catch (Exception e) {
                        log.error("Error parsing team data JSON for team {}", teamCode, e);
                        future.completeExceptionally(e);
                    }
                } catch (Exception e) {
                    log.error("Error processing team data response for team {}", teamCode, e);
                    future.completeExceptionally(e);
                }
            }
        });

        return future;
    }

    @Override
    public CompletableFuture<String> createTeam(String teamName, String discordWebhook,
                                              BingoConfig.ItemSourceType itemSourceType,
                                              String remoteUrl, String manualItems,
                                              int refreshInterval, boolean persistObtained) {
        CompletableFuture<String> future = new CompletableFuture<>();
        
        // Validate required fields
        if (teamName == null || teamName.trim().isEmpty()) {
            future.completeExceptionally(new IllegalArgumentException("Team name cannot be null or empty"));
            return future;
        }
        
        // Create the team data
        JsonObject teamData = new JsonObject();
        teamData.addProperty("teamName", teamName.trim());
        teamData.addProperty("discordWebhook", discordWebhook != null ? discordWebhook : "");
        teamData.addProperty("itemSourceType", itemSourceType != null ? itemSourceType.name() : BingoConfig.ItemSourceType.MANUAL.name());
        teamData.addProperty("remoteUrl", remoteUrl != null ? remoteUrl : "");
        teamData.addProperty("manualItems", manualItems != null ? manualItems : "");
        teamData.addProperty("refreshInterval", refreshInterval);
        teamData.addProperty("persistObtained", persistObtained);
        
        // Store the leader's account hash
        long accountHash = client.getAccountHash();
        if (accountHash != -1) { // Check if logged in
            teamData.addProperty("leaderAccountHash", String.valueOf(accountHash));
            log.info("Storing leader account hash for team creation: {}", accountHash);
        } else {
            log.warn("Could not store leader account hash: Client not logged in or hash unavailable.");
        }
        
        String requestUrl = API_ENDPOINT + "/teams";
        log.info("Creating team via API: URL={}, name={}, itemSourceType={}, remoteUrl={}", 
                requestUrl, teamName, itemSourceType, remoteUrl);
        
        // Send the request to the API
        Request request = new Request.Builder()
            .url(requestUrl)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .post(RequestBody.create(MediaType.parse("application/json"), gson.toJson(teamData)))
            .build();
        
        log.debug("Team creation request headers: {}", request.headers());
        log.debug("Team creation request body: {}", gson.toJson(teamData));
        
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.error("Failed to create team", e);
                future.completeExceptionally(e);
            }
            
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    String responseStr = responseBody != null ? responseBody.string() : "null response body";
                    log.debug("Team creation response: {} with status code: {}", responseStr, response.code());
                    
                    if (response.isSuccessful() && responseBody != null) {
                        if (responseStr != null && !responseStr.isEmpty()) {
                            JsonObject responseObj = gson.fromJson(responseStr, JsonObject.class);
                            String teamCode = null;
                            
                            if (responseObj != null && responseObj.has("teamCode")) {
                                teamCode = responseObj.get("teamCode").getAsString();
                            }
                            
                            if (teamCode != null && !teamCode.isEmpty()) {
                                log.info("Created team with code: {}", teamCode);
                                future.complete(teamCode);
                            } else {
                                String errorMsg = "API returned success but no valid team code in response: " + responseStr;
                                log.error(errorMsg);
                                future.completeExceptionally(new IOException(errorMsg));
                            }
                        } else {
                            String errorMsg = "API returned empty response";
                            log.error(errorMsg);
                            future.completeExceptionally(new IOException(errorMsg));
                        }
                    } else {
                        String errorMsg = String.format("Failed to create team: HTTP %d - %s", response.code(), responseStr);
                        log.error(errorMsg);
                        future.completeExceptionally(new IOException(errorMsg));
                    }
                } catch (Exception e) {
                    String errorMsg = "Error processing team creation response: " + e.getMessage();
                    log.error(errorMsg, e);
                    future.completeExceptionally(new IOException(errorMsg, e));
                }
            }
        });
        
        return future;
    }

    @Override
    public CompletableFuture<Boolean> joinTeam(String teamCode) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        
        // Check if the team exists
        Request request = new Request.Builder()
            .url(API_ENDPOINT + "/teams/" + teamCode + "/exists")
            .get()
            .build();
        
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.error("Failed to join team", e);
                future.completeExceptionally(e);
            }
            
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    if (response.isSuccessful() && responseBody != null) {
                        log.info("Joined team with code: {}", teamCode);
                        future.complete(true);
                    } else {
                        log.error("Team not found: {}", teamCode);
                        future.complete(false);
                    }
                }
            }
        });
        
        return future;
    }

    @Override
    public CompletableFuture<Boolean> updateTeamItems(String teamCode, List<BingoItem> items) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        
        // CRITICAL FIX: First check the current state in Firebase to avoid overwriting obtained=true
        // This is needed because this method replaces ALL items at once
        getTeamData(teamCode).thenCompose(currentData -> {
            try {
                // Extract current items from team data
                Map<String, Boolean> currentObtainedStatus = new HashMap<>();
                if (currentData != null && currentData.containsKey("items")) {
                    // The items might be stored as a Map or as a List, depending on the response format
                    Object itemsObj = currentData.get("items");
                    if (itemsObj instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> itemsMap = (Map<String, Object>) itemsObj;
                        for (Map.Entry<String, Object> entry : itemsMap.entrySet()) {
                            if (entry.getValue() instanceof Map) {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> itemData = (Map<String, Object>) entry.getValue();
                                if (itemData.containsKey("obtained") && Boolean.TRUE.equals(itemData.get("obtained"))) {
                                    // This item was previously obtained
                                    String itemName = (String) itemData.getOrDefault("name", entry.getKey());
                                    currentObtainedStatus.put(itemName.toLowerCase(), true);
                                    log.debug("Found previously obtained item in Firebase: {}", itemName);
                                }
                            }
                        }
                    }
                }
                
                // Create the items data
                JsonObject itemsData = new JsonObject();
                boolean madeChanges = false;
                
                for (BingoItem item : items) {
                    JsonObject itemData = new JsonObject();
                    itemData.addProperty("name", item.getName());
                    itemData.addProperty("itemId", item.getItemId());
                    
                    // CRITICAL: Preserve obtained=true status for any item that was previously obtained
                    boolean wasObtainedInFirebase = currentObtainedStatus.getOrDefault(item.getName().toLowerCase(), false);
                    if (wasObtainedInFirebase && !item.isObtained()) {
                        log.info("PRESERVING obtained status for {} in Firebase during team update", item.getName());
                        itemData.addProperty("obtained", true);
                        madeChanges = true;
                    } else {
                        itemData.addProperty("obtained", item.isObtained());
                    }
                    
                    itemData.addProperty("updatedAt", System.currentTimeMillis());
                    
                    // Add group info if applicable
                    if (item.isGroup()) {
                        itemData.addProperty("isGroup", true);
                        if (item.getAlternativeNames() != null && !item.getAlternativeNames().isEmpty()) {
                            JsonArray alternativeNames = new JsonArray();
                            for (String altName : item.getAlternativeNames()) {
                                alternativeNames.add(altName);
                            }
                            itemData.add("alternativeNames", alternativeNames);
                        }
                    }
                    
                    // Sanitize the item name for use as a key
                    String safeKey = sanitizeKey(item.getName());
                    itemsData.add(safeKey, itemData);
                }
                
                if (madeChanges) {
                    log.info("Updated obtained status for some items before sending to Firebase");
                }
                
                // Send the request to the API
                Request request = new Request.Builder()
                    .url(API_ENDPOINT + "/teams/" + teamCode + "/items")
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .put(RequestBody.create(MediaType.parse("application/json"), gson.toJson(itemsData)))
                    .build();
                
                log.debug("Sending team items update to API: {}", gson.toJson(itemsData));
                
                CompletableFuture<Boolean> putFuture = new CompletableFuture<>();
                
                httpClient.newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(Call call, IOException e) {
                        log.error("Failed to update team items", e);
                        putFuture.complete(false);
                    }
                    
                    @Override
                    public void onResponse(Call call, Response response) throws IOException {
                        try (ResponseBody responseBody = response.body()) {
                            if (response.isSuccessful() && responseBody != null) {
                                log.info("Successfully updated team items for team {}", teamCode);
                                putFuture.complete(true);
                            } else {
                                String errorMessage = responseBody != null ? responseBody.string() : "Unknown error";
                                log.error("Failed to update team items: {}", errorMessage);
                                putFuture.complete(false);
                            }
                        }
                    }
                });
                
                return putFuture;
            } catch (Exception e) {
                log.error("Error preparing team items update", e);
                return CompletableFuture.completedFuture(false);
            }
        }).thenAccept(future::complete)
          .exceptionally(e -> {
              log.error("Exception during getTeamData", e);
              future.complete(false);
              return null;
          });
        
        return future;
    }

    @Override
    public void cleanup() {
        // Nothing to clean up
    }

    @Override
    public boolean isUsingLocalStorage() {
        return false;
    }

    /**
     * Resolves the target item name for an item, handling group items
     *
     * @param teamCode The team code
     * @param itemName The item name
     * @return A CompletableFuture with the resolved target item name
     */
    public CompletableFuture<String> resolveTargetItemName(String teamCode, String itemName) {
        CompletableFuture<String> future = new CompletableFuture<>();
        
        // Add timeout to avoid hanging forever
        CompletableFuture.delayedExecutor(10, TimeUnit.SECONDS).execute(() -> {
            if (!future.isDone()) {
                log.warn("Request to resolve target item name timed out for {}: {}", teamCode, itemName);
                future.complete(itemName); // Return original name on timeout
            }
        });
        
        Request getRequest = new Request.Builder()
            .url(API_ENDPOINT + "/teams/" + teamCode + "/items")
            .get()
            .build();
        
        httpClient.newCall(getRequest).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.error("Error resolving target item name", e);
                future.complete(itemName); // Return original name on failure
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    if (response.isSuccessful() && responseBody != null) {
                        String getResponseStr = responseBody.string();
                        if (!getResponseStr.equals("null")) {
                            JsonObject existingItems = gson.fromJson(getResponseStr, JsonObject.class);
                            
                            // First try to find an exact match with sanitized key
                            String sanitizedItemName = sanitizeKey(itemName);
                            if (existingItems.has(sanitizedItemName)) {
                                future.complete(itemName); // Return original name, not sanitized version
                                return;
                            }
                            
                            // If not found, try to find a match in a group
                            for (Map.Entry<String, JsonElement> entry : existingItems.entrySet()) {
                                JsonObject itemData = entry.getValue().getAsJsonObject();
                                if (itemData.has("isGroup") && itemData.get("isGroup").getAsBoolean() && 
                                    itemData.has("alternativeNames")) {
                                    JsonArray alternativeNames = itemData.getAsJsonArray("alternativeNames");
                                    for (JsonElement altNameElem : alternativeNames) {
                                        String altName = altNameElem.getAsString();
                                        if (altName.equalsIgnoreCase(itemName)) {
                                            // Return the original name from the item data, not the key
                                            String result = itemData.has("name") ? itemData.get("name").getAsString() : itemName;
                                            future.complete(result);
                                            return;
                                        }
                                    }
                                }
                            }
                        }
                    }
                    
                    // If we couldn't find a match, return the original item name
                    future.complete(itemName);
                } catch (Exception e) {
                    log.error("Error processing response for resolving target item name", e);
                    future.complete(itemName); // Return original name on error
                }
            }
        });
        
        return future;
    }

    /**
     * Updates an item via the API
     *
     * @param teamCode The team code
     * @param itemName The item name
     * @param obtained Whether the item is obtained
     * @return A CompletableFuture that resolves to true if the update was successful, false otherwise
     */
    private CompletableFuture<Boolean> updateItemViaApi(String teamCode, String itemName, boolean obtained) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        
        // Add timeout to avoid hanging forever
        CompletableFuture.delayedExecutor(15, TimeUnit.SECONDS).execute(() -> {
            if (!future.isDone()) {
                log.warn("Request to update item timed out for {}: {}", teamCode, itemName);
                future.complete(false); // Return failure on timeout
            }
        });
        
        try {
            // Sanitize the item name for use as a key
            String sanitizedItemName = sanitizeKey(itemName);
            
            // Create update payload
            JsonObject updateData = new JsonObject();
            updateData.addProperty("obtained", obtained);
            updateData.addProperty("updatedAt", System.currentTimeMillis());
            
            // Send request to API
            String url = API_ENDPOINT + "/teams/" + teamCode + "/items/" + sanitizedItemName;
            RequestBody requestBody = RequestBody.create(MediaType.parse("application/json"), gson.toJson(updateData));
            
            Request request = new Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .patch(requestBody)
                .build();
            
            log.debug("Sending item update to API: {} with value: {}", url, gson.toJson(updateData));
            
            // Use the asynchronous API
            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    log.error("Error updating item obtained status via API", e);
                    future.complete(false);
                }
                
                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try (ResponseBody responseBody = response.body()) {
                        if (response.isSuccessful() && responseBody != null) {
                            String responseString = responseBody.string();
                            log.info("Successfully updated obtained status via API for team {}: {} = {} (response: {})", 
                                teamCode, itemName, obtained, responseString);
                            future.complete(true);
                        } else {
                            String errorMessage = responseBody != null ? responseBody.string() : "Unknown error";
                            log.error("Failed to update item obtained status via API: HTTP {} - {}", response.code(), errorMessage);
                            future.complete(false);
                        }
                    } catch (Exception e) {
                        log.error("Error processing response for item update", e);
                        future.complete(false);
                    }
                }
            });
        } catch (Exception e) {
            log.error("Error updating item obtained status via API", e);
            future.complete(false);
        }
        
        return future;
    }

    /**
     * Sanitizes a string for use as a key.
     *
     * @param key The key to sanitize
     * @return A sanitized key safe for use in API requests
     */
    private String sanitizeKey(String key) {
        if (key == null) {
            return "null";
        }
        
        // Replace invalid characters with safe alternatives
        String safeKey = key
            .replace(".", "_dot_")
            .replace("$", "_dollar_")
            .replace("#", "_hash_")
            .replace("[", "_lbracket_")
            .replace("]", "_rbracket_")
            .replace("/", "_slash_")
            .replace(":", "_colon_");  // Also replace colons for safety
        
        // Ensure the key isn't empty
        if (safeKey.isEmpty()) {
            return "_empty_";
        }
        
        return safeKey;
    }

    /**
     * Fetches item names from a remote URL and converts them to the expected Map format.
     *
     * @param remoteUrl The URL to fetch items from.
     * @return A CompletableFuture containing the list of items as Maps.
     */
    private CompletableFuture<List<Map<String, Object>>> fetchItemsFromRemoteUrl(String remoteUrl) {
        CompletableFuture<List<Map<String, Object>>> future = new CompletableFuture<>();

        if (remoteUrl == null || remoteUrl.isEmpty()) {
            log.warn("Remote URL is empty, returning empty item list");
            future.complete(new ArrayList<>());
            return future;
        }

        // Handle Pastebin URLs
        String effectiveUrl = remoteUrl;
        if (effectiveUrl.contains("pastebin.com") && !effectiveUrl.contains("/raw/")) {
            effectiveUrl = effectiveUrl.replace("pastebin.com/", "pastebin.com/raw/");
            log.info("Converted Pastebin URL to raw: {}", effectiveUrl);
        }

        // Create a final variable for use in the callback
        final String finalEffectiveUrl = effectiveUrl;

        Request request = new Request.Builder()
                .url(finalEffectiveUrl)
                .get()
                .build();

        log.info("Fetching items from remote URL for team data: {}", finalEffectiveUrl);
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.error("Failed to fetch remote items for team data from URL: {}", finalEffectiveUrl, e);
                future.completeExceptionally(e); // Propagate error
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    if (response.isSuccessful() && responseBody != null) {
                        String content = responseBody.string();
                        List<String> lines = Arrays.asList(content.split("\\r?\\n"));
                        List<Map<String, Object>> itemsList = new ArrayList<>();

                        lines.stream()
                             .map(String::trim)
                             .filter(s -> !s.isEmpty())
                             .forEach(itemName -> {
                                 Map<String, Object> item = new HashMap<>();
                                 item.put("name", itemName);
                                 item.put("obtained", false); // Default obtained status
                                 item.put("itemId", -1);    // Default itemId
                                 // We don't have group info from simple text lists
                                 itemsList.add(item);
                             });
                             
                        log.info("Successfully fetched {} items from remote URL: {}", itemsList.size(), finalEffectiveUrl);
                        future.complete(itemsList);
                    } else {
                        String errorMsg = responseBody != null ? responseBody.string() : "Unknown error";
                        log.error("Failed to fetch remote items for team data from URL: {} - HTTP {} - {}", finalEffectiveUrl, response.code(), errorMsg);
                        future.completeExceptionally(new IOException("Failed to fetch remote items: HTTP " + response.code()));
                    }
                } catch (Exception e) {
                    log.error("Error processing remote items response from URL: {}", finalEffectiveUrl, e);
                    future.completeExceptionally(e);
                }
            }
        });

        return future;
    }

    /**
     * Deletes a team's data.
     *
     * @param teamCode The code of the team to delete.
     * @return A CompletableFuture that resolves to true if deletion was successful, false otherwise.
     */
    public CompletableFuture<Boolean> deleteTeam(String teamCode) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        String url = API_ENDPOINT + "/teams/" + teamCode;
        
        log.info("Attempting to delete team data: {}", url);

        Request request = new Request.Builder()
                .url(url)
                .delete() // Use DELETE HTTP method
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.error("Failed to send delete request for team {}: {}", teamCode, e.getMessage(), e);
                future.complete(false); // Consider failure if request fails
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    if (response.isSuccessful()) {
                        log.info("Successfully deleted team data for code: {}", teamCode);
                        future.complete(true);
                    } else {
                        String errorMsg = responseBody != null ? responseBody.string() : "Unknown error";
                        log.error("Failed to delete team {} data: HTTP {} - {}", teamCode, response.code(), errorMsg);
                        future.complete(false);
                    }
                } catch (Exception e) {
                    log.error("Error processing delete response for team {}: {}", teamCode, e.getMessage(), e);
                    future.complete(false);
                }
            }
        });

        return future;
    }

    /**
     * Tests connectivity to the API endpoint.
     * 
     * @return A CompletableFuture that completes with a status message
     */
    public CompletableFuture<String> testApiConnectivity() {
        CompletableFuture<String> future = new CompletableFuture<>();
        
        // Create a simple test request
        Request request = new Request.Builder()
            .url(API_ENDPOINT + "/teams/test")
            .get()
            .build();
        
        log.info("Testing API connectivity to endpoint: {}", API_ENDPOINT);
        
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                String message = "API connectivity test failed: " + e.getMessage();
                log.error(message, e);
                future.complete(message);
            }
            
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    String responseStr = responseBody != null ? responseBody.string() : "null response body";
                    String message = String.format("API connectivity test result: HTTP %d - %s", response.code(), responseStr);
                    log.info(message);
                    future.complete(message);
                }
            }
        });
        
        return future;
    }

    /**
     * Exception thrown when a team is not found in the database
     */
    public static class TeamNotFoundException extends Exception {
        public TeamNotFoundException(String message) {
            super(message);
        }
    }
} 