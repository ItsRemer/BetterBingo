package com.betterbingo;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import javax.inject.Singleton;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

/**
 * Handles anti-cheat verification for the Bingo plugin
 * Probably not needed, but who knows.
 */
@Slf4j
@Singleton
public class BingoAntiCheat
{
    private static final String CONFIG_GROUP = "bingo";
    private static final String CONFIG_KEY_ACQUISITION_LOG = "acquisitionLog";
    
    private final Client client;
    private final BingoConfig config;
    private final ItemManager itemManager;
    
    @Inject
    private ConfigManager configManager;
    
    @Inject
    private Gson gson;
    
    @Inject
    private BingoProfileManager profileManager;

    // Thread-safe log of how items were acquired
    private final Map<String, ItemAcquisitionRecord> acquisitionRecords = new ConcurrentHashMap<>();

    /**
     * Represents how an item was acquired for anti-cheat verification
     */
    public enum AcquisitionMethod
    {
        NPC_DROP,         // From an NPC drop
        GROUND_SPAWN,     // Found on the ground
        CHEST_LOOT,       // From a chest/reward
        COLLECTION_LOG,   // Added to collection log
        CHAT_MESSAGE,     // Detected via chat message
        SIMULATED,        // Test mode simulated drop
        UNKNOWN           // Unknown source
    }

    /**
     * Records information about how an item was acquired for verification
     */
    @Getter
    public static class ItemAcquisitionRecord
    {
        private final String itemName;
        private final AcquisitionMethod method;
        private final Instant timestamp;
        private final String sourceDetails;  // NPC name, chest type, etc.
        private final String playerLocation; // Name or region where acquired

        public ItemAcquisitionRecord(
                String itemName,
                AcquisitionMethod method,
                String sourceDetails,
                String playerLocation
        )
        {
            this.itemName = itemName;
            this.method = method;
            this.timestamp = Instant.now();
            this.sourceDetails = sourceDetails;
            this.playerLocation = playerLocation;
        }
    }

    @Inject
    public BingoAntiCheat(Client client, BingoConfig config, ItemManager itemManager)
    {
        this.client = client;
        this.config = config;
        this.itemManager = itemManager;
    }

    /**
     * Records information about how an item was acquired.
     * Enhanced source details include world number, world type, and player name.
     */
    public void recordItemAcquisition(String itemName, AcquisitionMethod method, String sourceDetails, String location)
    {
        if (itemName == null || itemName.isEmpty())
        {
            return;
        }

        // Normalize item name
        itemName = itemName.trim().toLowerCase();

        // Create enhanced source details
        String enhancedSourceDetails = String.format(
                "%s (Player: %s, World: %d, Type: %s)",
                sourceDetails,
                getCurrentPlayerName(),
                getCurrentWorldNumber(),
                getCurrentWorldType()
        );

        // Record the acquisition
        ItemAcquisitionRecord record = new ItemAcquisitionRecord(
                itemName,
                method,
                enhancedSourceDetails,
                location
        );

        acquisitionRecords.put(itemName, record);
        log.debug("Recorded acquisition of {} via {}: {}", itemName, method, enhancedSourceDetails);
    }

    /**
     * Validates that an item acquisition is legitimate based on recorded data
     */
    public boolean validateItemAcquisition(String itemName)
    {
        if (itemName == null || itemName.isEmpty())
        {
            return false;
        }

        // Normalize item name
        itemName = itemName.trim().toLowerCase();

        // Check if we have a record for this item
        ItemAcquisitionRecord record = acquisitionRecords.get(itemName);
        if (record == null)
        {
            log.debug("No acquisition record found for item: {}", itemName);
            return false;
        }

        // Check if the acquisition method is valid
        if (record.getMethod() == AcquisitionMethod.UNKNOWN)
        {
            log.debug("Unknown acquisition method for item: {}", itemName);
            return false;
        }

        // Check if the location is valid for this item
        if (!isValidLocationForItem(itemName, record.getPlayerLocation()))
        {
            log.debug("Invalid location for item: {}", itemName);
            return false;
        }

        return true;
    }

    /**
     * Checks if the location is valid for the given item.
     * This is currently a placeholder that always returns true.
     * 
     * TODO: Implement proper location validation when we have a database of valid locations for items.
     * 
     * @param itemName The name of the item to check
     * @param location The location where the item was acquired
     * @return Currently always returns true
     */
    private boolean isValidLocationForItem(String itemName, String location)
    {
        // Always returns true for now
        // In the future, this could check against a database of valid locations for each item
        return true;
    }

    /**
     * Builds a message containing acquisition details for all bingo items
     */
    public String buildBingoItemsAcquisitionLogMessage(Set<String> bingoItems)
    {
        if (bingoItems == null || bingoItems.isEmpty())
        {
            return "No bingo items have been acquired yet.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("**Acquisition Log:**\n");

        int count = 0;
        for (String itemName : bingoItems)
        {
            ItemAcquisitionRecord record = acquisitionRecords.get(itemName);
            if (record != null)
            {
                count++;
                sb.append(String.format(
                        "%d. **%s** - %s via %s at %s\n",
                        count,
                        record.getItemName(),
                        record.getTimestamp(),
                        record.getMethod(),
                        record.getPlayerLocation()
                ));
            }
        }

        if (count == 0)
        {
            sb.append("No acquisition records found for bingo items.");
        }

        return sb.toString();
    }

    /**
     * Clears the acquisition log
     */
    public void clearAcquisitionLog()
    {
        acquisitionRecords.clear();
        log.debug("Cleared acquisition log");
    }

    /**
     * Saves the acquisition log for the current profile
     */
    public void saveAcquisitionLog()
    {
        String profileKey = config.currentProfile() + "." + CONFIG_KEY_ACQUISITION_LOG;
        saveAcquisitionLog(profileKey);
    }

    /**
     * Saves the acquisition log to the config
     */
    public void saveAcquisitionLog(String profileKey)
    {
        if (acquisitionRecords.isEmpty())
        {
            log.debug("No acquisition records to save");
            return;
        }

        try
        {
            // Convert the acquisition records to a serializable format
            List<Map<String, Object>> serializableRecords = new ArrayList<>();
            for (ItemAcquisitionRecord record : acquisitionRecords.values())
            {
                Map<String, Object> recordMap = new HashMap<>();
                recordMap.put("itemName", record.getItemName());
                recordMap.put("method", record.getMethod().name());
                recordMap.put("timestamp", record.getTimestamp().toString());
                recordMap.put("sourceDetails", record.getSourceDetails());
                recordMap.put("playerLocation", record.getPlayerLocation());
                serializableRecords.add(recordMap);
            }

            // Serialize to JSON
            String json = gson.toJson(serializableRecords);

            // Save to config
            configManager.setConfiguration(CONFIG_GROUP, profileKey, json);
            log.debug("Saved {} acquisition records", acquisitionRecords.size());
        }
        catch (Exception e)
        {
            log.error("Failed to save acquisition log", e);
        }
    }

    /**
     * Loads the acquisition log for the current profile
     */
    public void loadAcquisitionLog()
    {
        String profileKey = config.currentProfile() + "." + CONFIG_KEY_ACQUISITION_LOG;
        loadAcquisitionLog(profileKey);
    }

    /**
     * Loads the acquisition log from the config
     */
    public void loadAcquisitionLog(String profileKey)
    {
        try
        {
            // Clear existing records
            acquisitionRecords.clear();

            // Load from config
            String json = configManager.getConfiguration(CONFIG_GROUP, profileKey);
            if (json == null || json.isEmpty())
            {
                log.debug("No acquisition log found to load");
                return;
            }

            // Deserialize from JSON
            Type listType = new TypeToken<List<Map<String, Object>>>(){}.getType();
            List<Map<String, Object>> serializableRecords = gson.fromJson(json, listType);

            // Convert back to acquisition records
            for (Map<String, Object> recordMap : serializableRecords)
            {
                String itemName = (String) recordMap.get("itemName");
                AcquisitionMethod method = AcquisitionMethod.valueOf((String) recordMap.get("method"));
                String sourceDetails = (String) recordMap.get("sourceDetails");
                String playerLocation = (String) recordMap.get("playerLocation");

                ItemAcquisitionRecord record = new ItemAcquisitionRecord(
                        itemName,
                        method,
                        sourceDetails,
                        playerLocation
                );

                acquisitionRecords.put(itemName, record);
            }

            log.debug("Loaded {} acquisition records", acquisitionRecords.size());
        }
        catch (Exception e)
        {
            log.error("Failed to load acquisition log", e);
        }
    }

    /**
     * Gets the player's current location name
     */
    public String getPlayerLocationName()
    {
        if (client.getLocalPlayer() == null)
        {
            return "Unknown";
        }

        LocalPoint localPoint = client.getLocalPlayer().getLocalLocation();
        if (localPoint == null)
        {
            return "Unknown";
        }

        WorldPoint worldPoint = WorldPoint.fromLocal(client, localPoint);
        int regionID = worldPoint.getRegionID();

        // This is a placeholder - in a real implementation, you'd have a mapping of region IDs to location names
        return "Region " + regionID;
    }

    /**
     * Gets the current player's name
     */
    private String getCurrentPlayerName()
    {
        return client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : "Unknown";
    }

    /**
     * Gets the current world number
     */
    private int getCurrentWorldNumber()
    {
        return client.getWorld();
    }

    /**
     * Gets the current world type
     */
    private String getCurrentWorldType()
    {
        return client.getWorldType().toString();
    }
}
