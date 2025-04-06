package com.betterbingo;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.Iterator;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import com.google.gson.stream.JsonReader;
import java.io.StringReader;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Service for handling team-based Bingo functionality
 * Uses a storage strategy pattern for team synchronization
 */
@Slf4j
@Singleton
public class BingoTeamService {

    // Default to 15 minutes (900 seconds) - increased significantly to reduce API requests
    private static final int DEFAULT_SYNC_INTERVAL_SECONDS = 900;
    
    // Fixed cache expiration time of 48 hours (2 days)
    private static final int CACHE_EXPIRATION_HOURS = 48;

    private final OkHttpClient httpClient;
    private final Gson gson;
    private final ScheduledExecutorService executorService;
    private final Map<String, List<BingoItem>> teamItemsCache = new HashMap<>();
    private final Map<String, Instant> teamCacheLastAccessed = new HashMap<>();
    private final Map<String, Consumer<List<BingoItem>>> teamListeners = new HashMap<>();
    private final Map<String, CompletableFuture<List<BingoItem>>> activeRemoteUrlRequests = new ConcurrentHashMap<>();
    private final net.runelite.client.game.ItemManager itemManager;
    private final TeamStorageFactory storageFactory;
    private final FirebaseTeamStorage teamStorage;
    private final net.runelite.client.config.ConfigManager configManager;
    
    // Flag to prevent Firebase updates during initial load
    private final Map<String, Boolean> initialLoadingTeams = new ConcurrentHashMap<>();

    @Inject
    private BingoConfig config;

    @Inject
    public BingoTeamService(
            OkHttpClient httpClient,
            Gson gson,
            ScheduledExecutorService executorService,
            net.runelite.client.game.ItemManager itemManager,
            TeamStorageFactory storageFactory,
            FirebaseTeamStorage teamStorage,
            net.runelite.client.config.ConfigManager configManager) {
        this.httpClient = httpClient;
        this.gson = gson;
        this.executorService = executorService;
        this.itemManager = itemManager;
        this.storageFactory = storageFactory;
        this.teamStorage = teamStorage;
        this.configManager = configManager;

        // Schedule cache cleanup task to run every hour
        this.executorService.scheduleAtFixedRate(
            this::cleanupOldCaches,
            1,
            60,
            TimeUnit.MINUTES
        );
    }

    /**
     * Cleans up caches that haven't been accessed for a long time
     */
    private void cleanupOldCaches() {
        try {
            log.debug("Running cache cleanup task");
            int expiredCacheCount = 0;

            // Get current time
            Instant now = Instant.now();

            synchronized (teamItemsCache) {
                Iterator<Map.Entry<String, Instant>> it = teamCacheLastAccessed.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, Instant> entry = it.next();
                    String teamCode = entry.getKey();
                    Instant lastAccessed = entry.getValue();

                    // If this team has a listener, we never expire it
                    if (teamListeners.containsKey(teamCode)) {
                        continue;
                    }

                    // Check if last access time exceeds our fixed 48-hour threshold
                    if (lastAccessed.isBefore(now.minus(CACHE_EXPIRATION_HOURS, ChronoUnit.HOURS))) {
                        log.debug("Removing expired cache for team {} (last accessed: {})", teamCode, lastAccessed);
                        teamItemsCache.remove(teamCode);
                        it.remove();
                        expiredCacheCount++;
                    }
                }
            }

            if (expiredCacheCount > 0) {
                log.info("Cleaned up {} expired team caches", expiredCacheCount);
            }
        } catch (Exception e) {
            log.error("Error during cache cleanup", e);
        }
    }

    /**
     * Updates the last accessed timestamp for a team's cache
     *
     * @param teamCode The team code
     */
    private void updateCacheAccessTime(String teamCode) {
        if (teamCode != null) {
            synchronized (teamCacheLastAccessed) {
                teamCacheLastAccessed.put(teamCode, Instant.now());
            }
        }
    }

    /**
     * Creates a new team
     *
     * @param teamName       The name of the team
     * @param discordWebhook The Discord webhook URL
     * @param itemSourceType The item source type
     * @param remoteUrl      The remote URL for items
     * @param manualItems    The manual items list
     * @param refreshInterval The refresh interval
     * @param persistObtained Whether to persist obtained items
     * @return A CompletableFuture that resolves to the team code
     */
    public CompletableFuture<String> createTeam(String teamName, String discordWebhook,
                                               BingoConfig.ItemSourceType itemSourceType,
                                               String remoteUrl, String manualItems,
                                               int refreshInterval, boolean persistObtained) {
        return teamStorage.createTeam(teamName, discordWebhook, itemSourceType, remoteUrl, manualItems, refreshInterval, persistObtained)
            .thenApply(teamCode -> {
                log.info("Team created successfully with code: {}", teamCode);
                return teamCode;
            })
            .exceptionally(e -> {
                log.error("Failed to create team", e);
                return null;
            });
    }

    /**
     * Joins an existing team
     *
     * @param teamCode The team code
     * @return A CompletableFuture that resolves to a boolean indicating success
     */
    public CompletableFuture<Boolean> joinTeam(String teamCode) {
        return teamStorage.joinTeam(teamCode)
            .thenApply(success -> {
                if (success) {
                    log.info("Successfully joined team with code: {}", teamCode);
                } else {
                    log.warn("Failed to join team with code: {}", teamCode);
                }
                return success;
            })
            .exceptionally(e -> {
                log.error("Error joining team with code: {}", teamCode, e);
                return false;
            });
    }

    /**
     * Updates the obtained status of an item for a team
     *
     * @param teamCode The team code
     * @param itemName The item name
     * @param obtained Whether the item is obtained
     * @return A CompletableFuture that resolves to a boolean indicating success
     */
    public CompletableFuture<Boolean> updateItemObtained(String teamCode, String itemName, boolean obtained) {
        // Skip updating Firebase during initial loading to prevent overwriting remote state
        if (initialLoadingTeams.getOrDefault(teamCode, false)) {
            log.info("Skipping Firebase update for {} during initial load", itemName);
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            future.complete(true); // Pretend it succeeded
            return future;
        }
        
        // CRITICAL FIX: Never allow "false" updates to Firebase for obtained items
        // This ensures that once an item is obtained, it can never be unmarked accidentally
        if (!obtained) {
            log.info("Blocking attempt to set item {} to obtained=false in Firebase", itemName);
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            future.complete(true); // Pretend it succeeded
            return future;
        }
        
        return teamStorage.updateItemObtained(teamCode, itemName, obtained)
            .thenApply(success -> {
                if (success) {
                    log.info("Successfully updated item {} for team {}: obtained = {}", itemName, teamCode, obtained);
                } else {
                    log.warn("Failed to update item {} for team {}: obtained = {}", itemName, teamCode, obtained);
                }
                return success;
            })
            .exceptionally(e -> {
                log.error("Error updating item {} for team {}: obtained = {}", itemName, teamCode, obtained, e);
                return false;
            });
    }

    /**
     * Retrieves team data for a given team code
     *
     * @param teamCode The code of the team
     * @return A CompletableFuture containing a map of team data
     */
    public CompletableFuture<Map<String, Object>> getTeamData(String teamCode) {
        return teamStorage.getTeamData(teamCode)
            .thenApply(teamData -> {
                log.info("Successfully retrieved team data for team {}", teamCode);
                return teamData;
            })
            .exceptionally(e -> {
                log.error("Error retrieving team data for team {}", teamCode, e);
                return null;
            });
    }

    /**
     * Updates the items for a team
     *
     * @param teamCode The code of the team
     * @param items The list of items to update
     * @return A CompletableFuture containing a boolean indicating success
     */
    public CompletableFuture<Boolean> updateTeamItems(String teamCode, List<BingoItem> items) {
        // Skip massive updates during initial loading
        if (initialLoadingTeams.getOrDefault(teamCode, false)) {
            log.info("Skipping team items update for {} during initial load (protecting {} items)", teamCode, items.size());
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            future.complete(true); // Pretend it succeeded
            return future;
        }
        
        // CRITICAL: We need to first fetch the current items to prevent overwriting obtained status
        // This is necessary because updateTeamItems replaces ALL items
        return teamStorage.getTeamData(teamCode)
            .thenCompose(teamData -> {
                if (teamData != null) {
                    TeamData team = TeamData.fromMap(teamData);
                    if (team != null && team.getItems() != null && !team.getItems().isEmpty()) {
                        // Create a map of existing item obtained status
                        Map<String, Boolean> existingObtainedStatus = new HashMap<>();
                        for (BingoItem existingItem : team.getItems()) {
                            if (existingItem.isObtained()) {
                                existingObtainedStatus.put(existingItem.getName().toLowerCase(), true);
                            }
                        }
                        
                        // Check if any items would have their obtained status overwritten
                        boolean foundChanges = false;
                        for (BingoItem item : items) {
                            Boolean wasObtained = existingObtainedStatus.get(item.getName().toLowerCase());
                            if (wasObtained != null && wasObtained && !item.isObtained()) {
                                // This item was previously obtained but is now being set to not obtained
                                // Preserve the obtained status
                                log.info("Preserving obtained status for {} during team items update", item.getName());
                                item.setObtained(true);
                                foundChanges = true;
                            }
                        }
                        
                        if (foundChanges) {
                            log.info("Protected obtained status for some items during team update");
                        }
                    }
                }
                
                // Now proceed with the update
                return teamStorage.updateTeamItems(teamCode, items);
            })
            .thenApply(success -> {
                if (success) {
                    log.info("Successfully updated items for team {}", teamCode);
                } else {
                    log.warn("Failed to update items for team {}", teamCode);
                }
                return success;
            })
            .exceptionally(e -> {
                log.error("Error updating items for team {}", teamCode, e);
                return false;
            });
    }

    /**
     * Registers a team listener for a specific team.
     *
     * @param teamCode The team code to listen for updates
     * @param listener The listener to be notified
     * @return A CompletableFuture that completes when initial data is fetched
     */
    public CompletableFuture<Void> registerTeamListener(String teamCode, Consumer<List<BingoItem>> listener) {
        log.info("Registering listener for team: {}", teamCode);

        // Important: If we're switching profiles rapidly, unregister any previous listener with this code
        if (teamListeners.containsKey(teamCode)) {
            unregisterTeamListener(teamCode);
        }

        // Mark this team as in initial loading state to prevent Firebase updates
        initialLoadingTeams.put(teamCode, true);
        log.debug("Team {} marked as in initial loading state", teamCode);

        teamListeners.put(teamCode, listener);
        CompletableFuture<Void> future = new CompletableFuture<>();

        // First check if we already have items in our cache for faster response
        if (teamItemsCache.containsKey(teamCode)) {
            List<BingoItem> cachedItems = teamItemsCache.get(teamCode);
            if (cachedItems != null && !cachedItems.isEmpty()) {
                listener.accept(new ArrayList<>(cachedItems));
                // We'll still fetch fresh data below, but at least we've shown something immediately
            }
        }

        // Fetch team data and set up the listener
        getTeamData(teamCode)
            .thenAccept(teamData -> {
                if (teamData != null) {
                    // Check again if listener is still registered (in case of rapid profile switches)
                    if (!teamListeners.containsKey(teamCode)) {
                        initialLoadingTeams.remove(teamCode); // Clear the loading flag
                        future.complete(null);
                        return;
                    }

                    // Update local cache with team data - convert Map to JsonObject
                    JsonObject jsonObject = new JsonObject();
                    for (Map.Entry<String, Object> entry : teamData.entrySet()) {
                        if (entry.getValue() != null) {
                            if (entry.getValue() instanceof String) {
                                jsonObject.addProperty(entry.getKey(), (String)entry.getValue());
                            } else if (entry.getValue() instanceof Number) {
                                jsonObject.addProperty(entry.getKey(), (Number)entry.getValue());
                            } else if (entry.getValue() instanceof Boolean) {
                                jsonObject.addProperty(entry.getKey(), (Boolean)entry.getValue());
                            } else {
                                // For complex objects, we'd convert to JsonElement
                                // but for simplicity we'll just store as string
                                jsonObject.addProperty(entry.getKey(), entry.getValue().toString());
                            }
                        }
                    }
                    updateLocalCache(teamCode, jsonObject);
                    
                    // Notify listener with initial items - only use if we have items
                    if (teamItemsCache.containsKey(teamCode)) {
                        List<BingoItem> items = teamItemsCache.get(teamCode);
                        // If items is empty but we had cached items before, use those instead
                        if (items.isEmpty()) {
                            items = safelyUpdateCache(teamCode, items);
                        }

                        if (!items.isEmpty()) {
                            log.info("Initial fetch for listener on team {} completed with {} items.", teamCode, items.size());

                            // IMPORTANT: Only notify if the listener is still registered
                            if (teamListeners.containsKey(teamCode) && teamListeners.get(teamCode) == listener) {
                                listener.accept(new ArrayList<>(items));
                            }
                        }
                    }

                    // Check if the team is configured to use a remote URL
                    String remoteUrl = (String) teamData.get("remoteUrl");
                    BingoConfig.ItemSourceType itemSourceType = BingoConfig.ItemSourceType.valueOf((String) teamData.get("itemSourceType"));

                    if (itemSourceType == BingoConfig.ItemSourceType.REMOTE && remoteUrl != null && !remoteUrl.isEmpty()) {
                        log.info("Team {} is configured to use Remote URL ({}). Fetching remote items after initial load.", teamCode, remoteUrl);

                        // Then fetch items from remote URL
                        fetchItemsFromRemoteUrl(remoteUrl, teamCode)
                            .thenAccept(remoteItems -> {
                                try {
                                    // Only proceed if listener is still registered and hasn't changed
                                    if (!teamListeners.containsKey(teamCode) || teamListeners.get(teamCode) != listener) {
                                        initialLoadingTeams.remove(teamCode); // Clear the loading flag
                                        return;
                                    }

                                    if (remoteItems != null && !remoteItems.isEmpty()) {
                                        log.info("Initial remote URL fetch completed for team {} with {} items", teamCode, remoteItems.size());

                                        // Update the cache - safely
                                        List<BingoItem> updatedItems = safelyUpdateCache(teamCode, remoteItems);

                                        // Notify the listener with the items that were actually stored
                                        listener.accept(new ArrayList<>(updatedItems));

                                        // Update the storage
                                        updateTeamItems(teamCode, updatedItems)
                                            .thenAccept(success -> {
                                                if (success) {
                                                    log.info("Successfully updated items for team {}", teamCode);
                                                } else {
                                                    log.warn("Failed to update items for team {}", teamCode);
                                                }
                                                // Clear the initial loading flag now that everything is complete
                                                initialLoadingTeams.remove(teamCode);
                                                log.debug("Team {} initial loading completed after URL fetch", teamCode);
                                            })
                                            .exceptionally(ex -> {
                                                log.error("Error updating items for team {}: {}", teamCode, ex.getMessage(), ex);
                                                initialLoadingTeams.remove(teamCode); // Clear the loading flag even on error
                                                return null;
                                            });
                                    } else {
                                        log.warn("Remote URL fetch completed but no items were found for team {}", teamCode);
                                        // Check if we have cached items we can use instead
                                        List<BingoItem> existingItems = teamItemsCache.get(teamCode);
                                        if (existingItems != null && !existingItems.isEmpty()) {
                                            listener.accept(new ArrayList<>(existingItems));
                                        }
                                        // Clear the initial loading flag
                                        initialLoadingTeams.remove(teamCode);
                                        log.debug("Team {} initial loading completed with empty URL result", teamCode);
                                    }
                                } catch (Exception e) {
                                    log.error("Error during remote URL item processing", e);
                                    initialLoadingTeams.remove(teamCode); // Make sure flag is cleared even on exception
                                }
                            })
                            .exceptionally(ex -> {
                                log.error("Error fetching items from remote URL for team {}: {}", teamCode, ex.getMessage(), ex);
                                initialLoadingTeams.remove(teamCode); // Clear the loading flag on error
                                return null;
                            });
                    } else {
                        // No remote URL, so we're done with initial loading
                        initialLoadingTeams.remove(teamCode);
                        log.debug("Team {} initial loading completed (no remote URL)", teamCode);
                    }
                    
                    future.complete(null);
                } else {
                    log.error("Failed to get team data for team {}", teamCode);
                    initialLoadingTeams.remove(teamCode); // Clear the loading flag on error
                    future.completeExceptionally(new RuntimeException("Failed to get team data for team " + teamCode));
                }
            })
            .exceptionally(ex -> {
                log.error("Error registering team listener: {}", ex.getMessage(), ex);
                teamListeners.remove(teamCode);
                initialLoadingTeams.remove(teamCode); // Clear the loading flag on error
                future.completeExceptionally(ex);
                return null;
            });

        return future;
    }

    /**
     * Unregisters a listener for team item updates
     *
     * @param teamCode The team code
     */
    public void unregisterTeamListener(String teamCode) {
        if (teamListeners.containsKey(teamCode)) {
            log.info("Unregistering listener for team: {}", teamCode);
 
            // Remove the listener but KEEP the cache
            teamListeners.remove(teamCode);
            
            // Also clear the initial loading flag
            initialLoadingTeams.remove(teamCode);
            
            // Update last access time when unregistering to track when it was last used
            updateCacheAccessTime(teamCode);
 
            // Cancel any active requests for this team's URLs
            for (Iterator<Map.Entry<String, CompletableFuture<List<BingoItem>>>> it = activeRemoteUrlRequests.entrySet().iterator(); it.hasNext();) {
                Map.Entry<String, CompletableFuture<List<BingoItem>>> entry = it.next();
                if (entry.getKey().startsWith(teamCode + ":")) {
                    log.info("Cancelling active request for URL associated with team: {}", teamCode);
                    entry.getValue().complete(new ArrayList<>());  // Complete with empty list to avoid hanging
                    it.remove();
                }
            }
        }
    }
    
    /**
     * Clears the initial loading flag for a team.
     * Used when we want to allow Firebase updates again.
     *
     * @param teamCode The team code
     */
    public void clearInitialLoadingFlag(String teamCode) {
        if (initialLoadingTeams.containsKey(teamCode) && initialLoadingTeams.get(teamCode)) {
            log.debug("Manually clearing initial loading flag for team: {}", teamCode);
            initialLoadingTeams.remove(teamCode);
        }
    }

    /**
     * Deletes a team from the backend storage.
     *
     * @param teamCode The code of the team to delete.
     * @return A CompletableFuture indicating success or failure.
     */
    public CompletableFuture<Boolean> deleteTeam(String teamCode) {
        log.info("Requesting deletion of team: {}", teamCode);
        // TODO: Stop any active listeners or sync tasks for this team if necessary before deleting.
        return teamStorage.deleteTeam(teamCode)
            .thenApply(success -> {
                if (success) {
                    log.info("Successfully deleted team {} from storage.", teamCode);
                } else {
                    log.warn("Failed to delete team {} from storage.", teamCode);
                }
                // Clean up local cache regardless of backend success
                teamItemsCache.remove(teamCode);
                teamListeners.remove(teamCode);
                return success;
            })
            .exceptionally(e -> {
                log.error("Error deleting team {}: {}", teamCode, e.getMessage(), e);
                 // Clean up local cache even on exception
                teamItemsCache.remove(teamCode);
                teamListeners.remove(teamCode);
                return false; // Indicate failure
            });
    }

    /**
     * Converts the raw item list map from storage/API into BingoItem objects.
     *
     * @param itemMaps List of maps, each representing an item's data.
     * @return A list of BingoItem objects.
     */
    private List<BingoItem> convertMapToBingoItems(List<Map<String, Object>> itemMaps) {
        if (itemMaps == null) {
            return new ArrayList<>();
        }

        List<BingoItem> bingoItems = new ArrayList<>();
        for (Map<String, Object> itemMap : itemMaps) {
            String name = (String) itemMap.get("name");
            if (name == null || name.isEmpty()) continue;

            BingoItem item = new BingoItem(name);
            item.setObtained((Boolean) itemMap.getOrDefault("obtained", false));

            // Lookup Item ID using ItemManager
            Object itemIdObj = itemMap.get("itemId"); // Check if ID was already provided (e.g., from MANUAL source)
            int itemId = -1; // Default
            if (itemIdObj instanceof Number) {
                 itemId = ((Number) itemIdObj).intValue();
            }

            // If ID is still default (-1), try to look it up by name
            if (itemId == -1) {
                List<net.runelite.http.api.item.ItemPrice> results = itemManager.search(name);
                if (!results.isEmpty()) {
                    itemId = results.get(0).getId();
                    log.debug("Looked up itemId for '{}': {}", name, itemId);
                } else {
                     log.warn("Could not find itemId for item name: {}", name);
                }
            }
            item.setItemId(itemId);

            if ((Boolean) itemMap.getOrDefault("isGroup", false)) {
                 item.setGroup(true);
                 Object altNamesObj = itemMap.get("alternativeNames");
                 if (altNamesObj instanceof List) {
                     // Ensure elements are Strings
                     List<String> altNames = ((List<?>) altNamesObj).stream()
                                                       .filter(obj -> obj instanceof String)
                                                       .map(obj -> (String) obj)
                                                       .collect(Collectors.toList());
                    item.setAlternativeNames(altNames);
                 }
            }
            bingoItems.add(item);
        }
        return bingoItems;
    }

    /**
     * Updates the local cache with team data
     *
     * @param teamCode The team code
     * @param teamData The team data
     */
    private void updateLocalCache(String teamCode, TeamData teamData) {
        try {
            log.debug("Updating local cache for team {}", teamCode);

            // Check if we need to fetch items from a remote URL
            if (teamData.getItemSourceType() == BingoConfig.ItemSourceType.REMOTE &&
                teamData.getRemoteUrl() != null && !teamData.getRemoteUrl().isEmpty()) {

                log.info("Team {} is configured to use Remote URL. Fetching items from {}", teamCode, teamData.getRemoteUrl());

                // Fetch items from the remote URL
                fetchItemsFromRemoteUrl(teamData.getRemoteUrl(), teamCode).thenAccept(remoteItems -> {
                    if (remoteItems != null && !remoteItems.isEmpty()) {
                        log.info("Successfully fetched {} items from remote URL for team {}", remoteItems.size(), teamCode);

                        // Create a new list merging remote items with obtained status
                        List<BingoItem> mergedItems = new ArrayList<>(remoteItems);

                        // If we have items in the team data, update the obtained status
                        if (teamData.getItems() != null && !teamData.getItems().isEmpty()) {
                            // Map item name to obtained status
                            Map<String, Boolean> obtainedStatus = new HashMap<>();
                            for (BingoItem item : teamData.getItems()) {
                                if (item.isObtained()) {
                                    obtainedStatus.put(item.getName().toLowerCase(), true);
                                }
                            }

                            // Update obtained status for new items
                            for (BingoItem item : mergedItems) {
                                Boolean status = obtainedStatus.get(item.getName().toLowerCase());
                                if (status != null && status) {
                                    item.setObtained(true);
                                }
                            }
                        }

                        // Update the cache
                        teamItemsCache.put(teamCode, mergedItems);

                        // Notify listeners
                        Consumer<List<BingoItem>> listener = teamListeners.get(teamCode);
                        if (listener != null) {
                            listener.accept(mergedItems);
                        }

                        // Update the items in the storage
                        TeamStorageStrategy storage = storageFactory.getStorageStrategyForTeam(teamCode);
                        storage.updateTeamItems(teamCode, mergedItems);
                    } else {
                        log.warn("No items found in remote URL for team {}. Using empty list.", teamCode);

                        // Update with empty list to ensure we don't show manual items
                        teamItemsCache.put(teamCode, new ArrayList<>());

                        // Notify listeners
                        Consumer<List<BingoItem>> listener = teamListeners.get(teamCode);
                        if (listener != null) {
                            listener.accept(new ArrayList<>());
                        }
                    }
                });

                // Return early - the async remote URL fetch will handle the rest
                return;
            } else if (teamData.getManualItems() != null && !teamData.getManualItems().isEmpty()) {
                // Parse manual items asynchronously
                parseManualItemsAsync(teamData.getManualItems()).thenAccept(manualItems -> {
                    // If we have items from the team data, merge them with the manual items
                    if (teamData.getItems() != null && !teamData.getItems().isEmpty()) {
                        // Update manual items with the obtained status from the team data
                        // Use a more flexible approach that supports duplicate item names
                        for (BingoItem manualItem : manualItems) {
                            for (BingoItem teamItem : teamData.getItems()) {
                                if (manualItem.getName().equals(teamItem.getName()) &&
                                    manualItem.getItemId() == teamItem.getItemId()) {
                                    manualItem.setObtained(teamItem.isObtained());
                                    break;
                                }
                            }
                        }
                    }

                    // Update the cache
                    teamItemsCache.put(teamCode, manualItems);

                    // Notify listeners
                    Consumer<List<BingoItem>> listener = teamListeners.get(teamCode);
                    if (listener != null) {
                        listener.accept(manualItems);
                    }

                    log.info("Updated local cache for team {} with {} manual items",
                            teamCode, manualItems.size());

                    // Update the items in the storage
                    TeamStorageStrategy storage = storageFactory.getStorageStrategyForTeam(teamCode);
                    storage.updateTeamItems(teamCode, manualItems);
                });
            } else if (teamData.getItems() != null && !teamData.getItems().isEmpty()) {
                // Use the items from the team data
                List<BingoItem> items = teamData.getItems();

                // Update the cache
                teamItemsCache.put(teamCode, items);

                // Notify listeners
                Consumer<List<BingoItem>> listener = teamListeners.get(teamCode);
                if (listener != null) {
                    listener.accept(items);
                }

                log.info("Updated local cache for team {} with {} items from storage",
                        teamCode, items.size());
            }
        } catch (Exception e) {
            log.error("Error updating local cache", e);
        }
    }

    /**
     * Updates the local cache with team data from a JsonObject
     *
     * @param teamCode The team code
     * @param teamData The team data as a JsonObject
     */
    private void updateLocalCache(String teamCode, JsonObject teamData) {
        try {
            // Convert the JsonObject to a Map
            Map<String, Object> teamDataMap = gson.fromJson(teamData, Map.class);

            // Convert the Map to a TeamData object
            TeamData team = TeamData.fromMap(teamDataMap);

            // Update the local cache
            updateLocalCache(teamCode, team);
        } catch (Exception e) {
            log.error("Error updating local cache from JsonObject", e);
        }
    }

    /**
     * Fetches items from a remote URL
     *
     * @param remoteUrl The remote URL
     * @param teamCode The team code associated with this request
     * @return A CompletableFuture that resolves to a list of BingoItem objects
     */
    private CompletableFuture<List<BingoItem>> fetchItemsFromRemoteUrl(String remoteUrl, String teamCode) {
        if (remoteUrl == null || remoteUrl.trim().isEmpty()) {
            log.warn("Remote URL is null or empty");
            return CompletableFuture.completedFuture(new ArrayList<>());
        }

        // Use the provided teamCode
        final String requestTeamCode = teamCode != null ? teamCode : "unknown";

        // Normalize the URL to handle Pastebin URLs consistently
        String normalizedUrl = normalizeRemoteUrl(remoteUrl, requestTeamCode);

        // Check if there's already an active request for this URL
        final String requestKey = requestTeamCode + ":" + normalizedUrl;
        CompletableFuture<List<BingoItem>> existingFuture = activeRemoteUrlRequests.get(requestKey);

        if (existingFuture != null && !existingFuture.isDone()) {
            log.info("Reusing existing request for URL: {}", normalizedUrl);
            return existingFuture;
        }

        // Create a new future
        CompletableFuture<List<BingoItem>> future = new CompletableFuture<>();

        // Register it in our tracking map
        activeRemoteUrlRequests.put(requestKey, future);

        // Make sure to remove it when done
        future.whenComplete((result, ex) -> {
            activeRemoteUrlRequests.remove(requestKey);
            log.debug("Removed URL request from tracking map: {}", normalizedUrl);
        });

        log.info("Fetching items from remote URL: {}", normalizedUrl);

        // Execute the request
        executeRemoteUrlRequest(normalizedUrl, future);

        return future;
    }

    // Overload for backward compatibility
    private CompletableFuture<List<BingoItem>> fetchItemsFromRemoteUrl(String remoteUrl) {
        return fetchItemsFromRemoteUrl(remoteUrl, "unknown");
    }

    /**
     * Normalizes a remote URL, particularly for Pastebin URLs
     */
    private String normalizeRemoteUrl(String url, String teamCode) {
        if (url == null || url.isEmpty()) {
            return "";
        }

        // Handle Pastebin URLs
        if (url.contains("pastebin.com")) {
            // Ensure we're using the raw URL format for Pastebin
            if (!url.contains("/raw/")) {
                // If it's just the ID, convert to full raw URL
                if (url.matches("https?://pastebin\\.com/[a-zA-Z0-9]+$")) {
                    url = url.replaceAll("(https?://pastebin\\.com/)([a-zA-Z0-9]+)$", "$1raw/$2");
                    log.info("Converted Pastebin URL to raw format: {} -> {}", url, url);
                }
                // If it's a full URL without /raw/, insert it
                else if (url.matches("https?://pastebin\\.com/[^/]+$")) {
                    url = url.replace("pastebin.com/", "pastebin.com/raw/");
                    log.info("Converted Pastebin URL to raw format: {} -> {}", url, url);
                }
                // If it's just the ID without URL, prepend the full raw URL
                else if (url.matches("[a-zA-Z0-9]+")) {
                    url = "https://pastebin.com/raw/" + url;
                    log.info("Converted Pastebin ID to full raw URL: {} -> {}", url, url);
                }
            }

            // Add a cache-busting parameter and team-specific parameter to ensure we get fresh data
            // If URL already has a query parameter, append to it
            String cacheParam = "_nocache=" + System.currentTimeMillis();
            String teamParam = "_team=" + (teamCode != null ? teamCode : "unknown");

            if (url.contains("?")) {
                url += "&" + cacheParam + "&" + teamParam;
            } else {
                url += "?" + cacheParam + "&" + teamParam;
            }
            log.debug("Added cache-busting to URL for team {}: {}", teamCode, url);
        }

        return url;
    }

    /**
     * Executes the actual request to the remote URL
     */
    private void executeRemoteUrlRequest(String url, CompletableFuture<List<BingoItem>> future) {
        // Handle empty URLs
        if (url == null || url.isEmpty()) {
            log.warn("Cannot fetch from empty URL");
            future.complete(new ArrayList<>());
            return;
        }

        // Send the request
        Request request = new Request.Builder()
            .url(url)
            .get()
            .build();

        log.debug("Sending HTTP request to: {}", url);

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.error("Failed to fetch items from remote URL: {}", e.getMessage(), e);
                future.complete(new ArrayList<>());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                log.debug("Received HTTP response with code: {}", response.code());

                try (ResponseBody responseBody = response.body()) {
                    if (response.isSuccessful() && responseBody != null) {
                        String responseStr = responseBody.string();
                        log.info("Received response from remote URL (length: {})", responseStr.length());

                        if (responseStr.length() < 100) {
                            log.info("Content preview: {}", responseStr);
                        } else {
                            log.debug("Content preview (first 100 chars): {}", responseStr.substring(0, 100));
                        }

                        // Process the data in a background thread to avoid blocking the OkHttp callback thread
                        executorService.submit(() -> {
                            try {
                                // Parse the response
                                List<BingoItem> items = parseRemoteItems(responseStr);

                                log.info("Fetched {} items from remote URL", items.size());

                                if (!items.isEmpty()) {
                                    String itemNames = items.stream()
                                        .limit(5)
                                        .map(BingoItem::getName)
                                        .collect(Collectors.joining(", "));
                                    log.info("First few items: {}", itemNames);
                                }

                                future.complete(items);
                            } catch (Exception e) {
                                log.error("Error parsing remote response: {}", e.getMessage(), e);
                                future.complete(new ArrayList<>());
                            }
                        });
                    } else {
                        log.error("Failed to fetch items from remote URL: HTTP {}", response.code());
                        if (responseBody != null) {
                            log.error("Response body: {}", responseBody.string());
                        }
                        future.complete(new ArrayList<>());
                    }
                } catch (Exception e) {
                    log.error("Error processing remote URL response: {}", e.getMessage(), e);
                    future.complete(new ArrayList<>());
                }
            }
        });
    }

    /**
     * Parses items from a remote response
     *
     * @param response The response string
     * @return A list of BingoItem objects
     */
    private List<BingoItem> parseRemoteItems(String response) {
        List<BingoItem> items = new ArrayList<>();
        final int MAX_ITEMS = 500; // Limit to 500 items to prevent excessive processing

        if (response == null || response.isEmpty()) {
            log.warn("Remote response is null or empty");
            return items;
        }

        // Add safety check for extremely large responses
        if (response.length() > 1000000) { // 1MB limit
            log.warn("Remote response exceeds 1MB (size: {}), truncating to prevent excessive processing", response.length());
            response = response.substring(0, 1000000);
        }

        log.debug("Attempting to parse remote response (length: {}) as JSON or plain text", response.length());

        try {
            // Try to parse as JSON first with lenient parsing
            JsonReader reader = new JsonReader(new StringReader(response));
            reader.setLenient(true);
            JsonElement jsonElement = gson.fromJson(reader, JsonElement.class);

            if (jsonElement.isJsonArray()) {
                // Parse as a JSON array of items
                JsonArray jsonArray = jsonElement.getAsJsonArray();
                log.debug("Parsed response as JSON array with {} elements", jsonArray.size());

                int count = 0;
                for (JsonElement element : jsonArray) {
                    if (count >= MAX_ITEMS) {
                        log.warn("Maximum item limit ({}) reached, truncating additional items", MAX_ITEMS);
                        break;
                    }

                    if (element.isJsonPrimitive()) {
                        // Simple string item
                        String itemName = element.getAsString();

                        // Check if this is a group item (contains colons)
                        if (itemName.contains(":")) {
                            // This is an item group
                            BingoItemGroup itemGroup = BingoItemGroup.fromString(itemName);
                            BingoItem groupItem = createGroupItem(itemGroup);
                            items.add(groupItem);
                        } else {
                            BingoItem item = new BingoItem(itemName);
                            items.add(item);
                        }
                        count++;
                    } else if (element.isJsonObject()) {
                        // Complex item object
                        JsonObject itemObj = element.getAsJsonObject();
                        if (itemObj.has("name")) {
                            String itemName = itemObj.get("name").getAsString();
                            BingoItem item = new BingoItem(itemName);

                            // Check for obtained status
                            if (itemObj.has("obtained")) {
                                item.setObtained(itemObj.get("obtained").getAsBoolean());
                            }

                            // Check for item ID
                            if (itemObj.has("itemId")) {
                                item.setItemId(itemObj.get("itemId").getAsInt());
                            } else {
                                // Try to look up the item ID
                                net.runelite.http.api.item.ItemPrice itemPrice = lookupItemByName(itemName);
                                if (itemPrice != null) {
                                    item.setItemId(itemPrice.getId());
                                }
                            }

                            // Check for group info
                            if (itemObj.has("isGroup") && itemObj.get("isGroup").getAsBoolean()) {
                                item.setGroup(true);

                                // Check for alternative names
                                if (itemObj.has("alternativeNames") && itemObj.get("alternativeNames").isJsonArray()) {
                                    JsonArray altNames = itemObj.get("alternativeNames").getAsJsonArray();
                                    List<String> alternativeNames = new ArrayList<>();
                                    for (JsonElement altName : altNames) {
                                        alternativeNames.add(altName.getAsString());
                                    }
                                    item.setAlternativeNames(alternativeNames);
                                }
                            }

                            items.add(item);
                            count++;
                        }
                    }
                }
            } else if (jsonElement.isJsonObject()) {
                // Parse as a JSON object with item names as keys
                JsonObject jsonObject = jsonElement.getAsJsonObject();
                int count = 0;
                for (Map.Entry<String, JsonElement> entry : jsonObject.entrySet()) {
                    if (count >= MAX_ITEMS) {
                        log.warn("Maximum item limit ({}) reached, truncating additional items", MAX_ITEMS);
                        break;
                    }

                    String itemName = entry.getKey();
                    JsonElement itemElement = entry.getValue();

                    BingoItem item = new BingoItem(itemName);

                    if (itemElement.isJsonObject()) {
                        JsonObject itemObj = itemElement.getAsJsonObject();

                        // Check for obtained status
                        if (itemObj.has("obtained")) {
                            item.setObtained(itemObj.get("obtained").getAsBoolean());
                        }

                        // Check for item ID
                        if (itemObj.has("itemId")) {
                            item.setItemId(itemObj.get("itemId").getAsInt());
                        } else {
                            // Try to look up the item ID
                            net.runelite.http.api.item.ItemPrice itemPrice = lookupItemByName(itemName);
                            if (itemPrice != null) {
                                item.setItemId(itemPrice.getId());
                            }
                        }

                        // Check for group info
                        if (itemObj.has("isGroup") && itemObj.get("isGroup").getAsBoolean()) {
                            item.setGroup(true);

                            // Check for alternative names
                            if (itemObj.has("alternativeNames") && itemObj.get("alternativeNames").isJsonArray()) {
                                JsonArray altNames = itemObj.get("alternativeNames").getAsJsonArray();
                                List<String> alternativeNames = new ArrayList<>();
                                for (JsonElement altName : altNames) {
                                    alternativeNames.add(altName.getAsString());
                                }
                                item.setAlternativeNames(alternativeNames);
                            }
                        }
                    }

                    items.add(item);
                    count++;
                }
            } else {
                // Fall back to parsing as a plain text list
                items = parseManualItems(response);

                // Also limit items from manual parsing
                if (items.size() > MAX_ITEMS) {
                    log.warn("Maximum item limit ({}) reached, truncating {} additional items",
                        MAX_ITEMS, items.size() - MAX_ITEMS);
                    items = items.subList(0, MAX_ITEMS);
                }
            }
        } catch (Exception e) {
            // If JSON parsing fails, try parsing as a plain text list
            log.warn("Failed to parse remote items as JSON, falling back to plain text parsing", e);
            items = parseManualItems(response);

            // Also limit items from manual parsing
            if (items.size() > MAX_ITEMS) {
                log.warn("Maximum item limit ({}) reached, truncating {} additional items",
                    MAX_ITEMS, items.size() - MAX_ITEMS);
                items = items.subList(0, MAX_ITEMS);
            }
        }

        return items;
    }

    /**
     * Creates a BingoItem from a BingoItemGroup
     *
     * @param group The BingoItemGroup to create the item from
     * @return A new BingoItem representing the group
     */
    private BingoItem createGroupItem(BingoItemGroup group) {
        BingoItem item = new BingoItem(group.getDisplayName());
        item.setObtained(group.isObtained());
        item.setGroup(true);

        // Add all item names except the first one (which is the display name) as alternative names
        List<String> alternatives = new ArrayList<>();
        for (String itemName : group.getItemNames()) {
            if (!itemName.equals(group.getDisplayName())) {
                alternatives.add(itemName);
            }
        }
        item.setAlternativeNames(alternatives);

        // Try to set the item ID from the first item in the group
        if (group.getItemNames().size() > 0) {
            String firstItemName = group.getItemNames().get(0);
            net.runelite.http.api.item.ItemPrice itemPrice = lookupItemByName(firstItemName);
            if (itemPrice != null) {
                item.setItemId(itemPrice.getId());
            }
        }

        return item;
    }

    /**
     * Parses manual items from a string
     *
     * @param manualItems The manual items string
     * @return A list of BingoItem objects
     */
    private List<BingoItem> parseManualItems(String manualItems) {
        List<BingoItem> items = new ArrayList<>();

        if (manualItems == null || manualItems.isEmpty()) {
            log.warn("Manual items string is null or empty");
            return items;
        }

        log.debug("Parsing manual items string: '{}' (length: {})",
            manualItems.length() > 100 ? manualItems.substring(0, 100) + "..." : manualItems,
            manualItems.length());

        // Split by newlines or commas
        String[] itemNames = manualItems.split("[\\r\\n,]+");

        log.debug("Split into {} item names", itemNames.length);

        for (int i = 0; i < itemNames.length; i++) {
            String itemName = itemNames[i].trim();
            if (!itemName.isEmpty()) {
                try {
                    log.debug("Processing item #{}: '{}'", i+1, itemName);

                    // Check if this is a group item (contains colons)
                    if (itemName.contains(":")) {
                        // This is an item group
                        log.debug("Processing as group item: {}", itemName);
                        BingoItemGroup itemGroup = BingoItemGroup.fromString(itemName);
                        BingoItem groupItem = createGroupItem(itemGroup);
                        items.add(groupItem);
                        log.debug("Added group item: {} with {} alternative names",
                            groupItem.getName(),
                            groupItem.getAlternativeNames() != null ? groupItem.getAlternativeNames().size() : 0);
                    } else {
                        log.debug("Processing as regular item: {}", itemName);
                        BingoItem item = new BingoItem(itemName);

                        // Try to look up the item ID
                        net.runelite.http.api.item.ItemPrice itemPrice = lookupItemByName(itemName);
                        if (itemPrice != null) {
                            item.setItemId(itemPrice.getId());
                            log.debug("Found item ID {} for {}", itemPrice.getId(), itemName);
                        } else {
                            log.debug("No item ID found for {}", itemName);
                        }

                        items.add(item);
                        log.debug("Added item: {}", item.getName());
                    }
                } catch (Exception e) {
                    log.error("Error processing manual item '{}': {}", itemName, e.getMessage(), e);
                }
            }
        }

        log.info("Parsed {} items from manual items string (original string length: {})",
            items.size(), manualItems.length());

        // Debug first few items
        if (!items.isEmpty()) {
            StringBuilder itemsPreview = new StringBuilder();
            for (int i = 0; i < Math.min(5, items.size()); i++) {
                if (i > 0) itemsPreview.append(", ");
                itemsPreview.append(items.get(i).getName());
            }
            if (items.size() > 5) itemsPreview.append(", ...");
            log.debug("First few items: {}", itemsPreview.toString());
        }

        return items;
    }

    /**
     * Parses manual items from a string asynchronously.
     * Performs the parsing on a background thread to prevent blocking.
     *
     * @param manualItems The manual items string
     * @return A CompletableFuture that resolves to a list of BingoItem objects
     */
    private CompletableFuture<List<BingoItem>> parseManualItemsAsync(String manualItems) {
        CompletableFuture<List<BingoItem>> future = new CompletableFuture<>();

        // Handle empty input immediately without creating a thread
        if (manualItems == null || manualItems.isEmpty()) {
            log.warn("Manual items string is null or empty");
            future.complete(new ArrayList<>());
            return future;
        }

        // Process on background thread
        executorService.submit(() -> {
            try {
                List<BingoItem> items = parseManualItems(manualItems);
                future.complete(items);
            } catch (Exception e) {
                log.error("Error parsing manual items", e);
                future.complete(new ArrayList<>());
            }
        });

        return future;
    }

    /**
     * Looks up an item by name using the ItemManager
     *
     * @param itemName The item name
     * @return The ItemPrice for the item, or null if not found
     */
    private net.runelite.http.api.item.ItemPrice lookupItemByName(String itemName) {
        List<net.runelite.http.api.item.ItemPrice> results = itemManager.search(itemName);
        if (!results.isEmpty()) {
            return results.get(0);
        }
        return null;
    }

    /**
     * Cleans up resources used by this service
     */
    public void cleanup() {
        // Clear caches
        teamItemsCache.clear();
        teamListeners.clear();

        // Clean up storage strategies
        for (TeamStorageStrategy storage : new TeamStorageStrategy[] {
                storageFactory.getStorageStrategy()
        }) {
            storage.cleanup();
        }
    }

    /**
     * Manually triggers a refresh of team items from the remote URL
     *
     * @param teamCode The team code
     * @return A CompletableFuture that resolves to a boolean indicating success
     */
    public CompletableFuture<Boolean> refreshTeamItems(String teamCode) {
        log.info("Manually refreshing team items for team: {}", teamCode);
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        // Get the appropriate storage strategy for this team
        TeamStorageStrategy storage = storageFactory.getStorageStrategyForTeam(teamCode);

        // Fetch the team data
        storage.getTeamData(teamCode).thenAccept(teamData -> {
            TeamData team = TeamData.fromMap(teamData);
            if (team != null) {
                log.info("Found team: code={}, name={}, itemSourceType={}, remoteUrl={}",
                        teamCode, team.getTeamName(), team.getItemSourceType(), team.getRemoteUrl());

                // Check if we need to fetch items from a remote URL
                if (team.getItemSourceType() == BingoConfig.ItemSourceType.REMOTE &&
                    team.getRemoteUrl() != null && !team.getRemoteUrl().isEmpty()) {

                    // Fetch items from the remote URL
                    fetchItemsFromRemoteUrl(team.getRemoteUrl(), teamCode).thenAccept(remoteItems -> {
                        if (remoteItems != null && !remoteItems.isEmpty()) {
                            log.info("Fetched {} items from remote URL", remoteItems.size());

                            // Update the cache safely
                            List<BingoItem> items = safelyUpdateCache(teamCode, remoteItems);

                            // Notify listeners
                            Consumer<List<BingoItem>> listener = teamListeners.get(teamCode);
                            if (listener != null) {
                                listener.accept(new ArrayList<>(items));
                            }

                            // Update the items in the storage
                            storage.updateTeamItems(teamCode, items)
                                .thenAccept(success -> {
                                    future.complete(success);
                                });
                        } else {
                            log.warn("No items fetched from remote URL");

                            // Check if we have cached items we can use instead
                            List<BingoItem> existingItems = teamItemsCache.get(teamCode);
                            if (existingItems != null && !existingItems.isEmpty()) {
                                // Notify listeners with the existing items
                                Consumer<List<BingoItem>> listener = teamListeners.get(teamCode);
                                if (listener != null) {
                                    listener.accept(new ArrayList<>(existingItems));
                                }
                                future.complete(true);
                            } else {
                                future.complete(false);
                            }
                        }
                    });
                } else {
                    log.warn("Team is not configured to use a remote URL");
                    future.complete(false);
                }
            } else {
                log.error("Team not found: {}", teamCode);
                future.complete(false);
            }
        }).exceptionally(e -> {
            log.error("Error refreshing team items", e);
            future.complete(false);
            return null;
        });

        return future;
    }

    /**
     * Gets the cached items for a team
     *
     * @param teamCode The team code
     * @return The cached items for the team, or null if no cached items exist
     */
    public List<BingoItem> getTeamCachedItems(String teamCode) {
        List<BingoItem> cachedItems;
        synchronized (teamItemsCache) {
            cachedItems = teamItemsCache.get(teamCode);
            if (cachedItems != null && !cachedItems.isEmpty()) {
                // Update last access time
                updateCacheAccessTime(teamCode);

                // Return a defensive copy to avoid modifying the cache
                List<BingoItem> copy = new ArrayList<>();
                for (BingoItem item : cachedItems) {
                    copy.add(new BingoItem(item));
                }
                return copy;
            }
        }
        return new ArrayList<>();
    }

    /**
     * Safely updates the team items cache, ensuring we never replace existing items with an empty list
     *
     * @param teamCode The team code
     * @param items The new items to store
     * @return The items that were stored (either the new items or the existing ones if the new ones were empty)
     */
    private List<BingoItem> safelyUpdateCache(String teamCode, List<BingoItem> items) {
        synchronized (teamItemsCache) {
            if (items == null || items.isEmpty()) {
                // If we're trying to update with empty items, check if we already have items
                List<BingoItem> existingItems = teamItemsCache.get(teamCode);
                if (existingItems != null && !existingItems.isEmpty()) {
                    // We're keeping existing items, but still update the access time
                    updateCacheAccessTime(teamCode);
                    return existingItems;
                }
                // Otherwise, just store the empty list
                teamItemsCache.put(teamCode, new ArrayList<>());
                updateCacheAccessTime(teamCode);
                return new ArrayList<>();
            }

            // Normal case - update with non-empty items
            List<BingoItem> itemsCopy = new ArrayList<>(items);
            teamItemsCache.put(teamCode, itemsCopy);
            updateCacheAccessTime(teamCode);
            return itemsCopy;
        }
    }

    /**
     * Manually triggers the cache cleanup process
     * This can be useful for testing or freeing up memory immediately
     */
    public void triggerCacheCleanup() {
        log.info("Manually triggering cache cleanup");
        cleanupOldCaches();
    }
} 
