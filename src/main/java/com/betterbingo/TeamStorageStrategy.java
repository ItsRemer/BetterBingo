package com.betterbingo;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for team storage strategies.
 * Implementations can store team data in different backends (Firebase, local storage, etc.)
 */
public interface TeamStorageStrategy {
    /**
     * Updates the obtained status of an item for a team
     *
     * @param teamCode The team code
     * @param itemName The item name
     * @param obtained Whether the item is obtained
     * @return A CompletableFuture that completes with true if the update was successful
     */
    CompletableFuture<Boolean> updateItemObtained(String teamCode, String itemName, boolean obtained);
    
    /**
     * Gets the team data for a team
     *
     * @param teamCode The team code
     * @return A CompletableFuture that completes with the team data
     */
    CompletableFuture<Map<String, Object>> getTeamData(String teamCode);
    
    /**
     * Creates a new team
     *
     * @param teamName The name of the team
     * @param discordWebhook The Discord webhook URL
     * @param itemSourceType The item source type
     * @param remoteUrl The remote URL for items
     * @param manualItems The manual items list
     * @param refreshInterval The refresh interval
     * @param persistObtained Whether to persist obtained items
     * @return A CompletableFuture that completes with the team code
     */
    CompletableFuture<String> createTeam(String teamName, String discordWebhook,
                                        BingoConfig.ItemSourceType itemSourceType,
                                        String remoteUrl, String manualItems,
                                        int refreshInterval, boolean persistObtained);
    
    /**
     * Joins a team
     *
     * @param teamCode The team code
     * @return A CompletableFuture that completes with true if the join was successful
     */
    CompletableFuture<Boolean> joinTeam(String teamCode);
    
    /**
     * Updates the items for a team
     *
     * @param teamCode The team code
     * @param items The items to update
     * @return A CompletableFuture that completes with true if the update was successful
     */
    CompletableFuture<Boolean> updateTeamItems(String teamCode, List<BingoItem> items);
    
    /**
     * Cleans up any resources used by the storage strategy
     */
    void cleanup();
    
    /**
     * Checks if the storage strategy is using local storage
     *
     * @return True if using local storage, false otherwise
     */
    boolean isUsingLocalStorage();
} 