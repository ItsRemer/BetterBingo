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
    private final Client client;
    private final BingoConfig config;
    private final ItemManager itemManager;
    
    @Inject
    private ConfigManager configManager;
    
    @Inject
    private Gson gson;

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
            log.debug("Attempted to record an acquisition with null or empty item name");
            return;
        }

        String details = String.format(
                "%s | World: %d (%s) | Player: %s",
                sourceDetails,
                getCurrentWorldNumber(),
                getCurrentWorldType(),
                getCurrentPlayerName()
        );

        ItemAcquisitionRecord record = new ItemAcquisitionRecord(itemName, method, details, location);
        acquisitionRecords.put(itemName.toLowerCase(), record);

        log.info("Item acquisition recorded: {} via {} from {} at {}",
                itemName, method, details, location
        );
    }

    /**
     * Validates an item's acquisition. Returns false if no record exists,
     * or if it fails any anti-cheat checks (timing, location, etc.).
     */
    public boolean validateItemAcquisition(String itemName)
    {
        if (itemName == null || itemName.isEmpty())
        {
            log.warn("Cannot validate acquisition for a null or empty item name");
            return false;
        }

        ItemAcquisitionRecord record = acquisitionRecords.get(itemName.toLowerCase());
        if (record == null)
        {
            log.warn("No acquisition record found for item: {}", itemName);
            return false;
        }

        if (!isValidLocationForItem(record.getItemName(), record.getPlayerLocation()))
        {
            log.warn("Rejected {} - invalid location {}", itemName, record.getPlayerLocation());
            return false;
        }

        return true;
    }

    /**
     * Checks if the given location is valid for obtaining the specified item.
     * In a full implementation, this might check a data structure or config.
     * Doubt this will be used ever in the future but w.e
     */
    private boolean isValidLocationForItem(String itemName, String location)
    {
        // Placeholder: assume all locations are valid for now, might never be used though
        return true;
    }

    /**
     * Builds a formatted message containing the acquisition log filtered to only include bingo items.
     * 
     * @param bingoItems Set of item names that are on the bingo board
     * @return Formatted message with acquisition details for bingo items only
     * Makes sure that we are not adding any acquisition items that are needed
     */
    public String buildBingoItemsAcquisitionLogMessage(Set<String> bingoItems)
    {
        if (acquisitionRecords.isEmpty())
        {
            return "No item acquisitions recorded.";
        }

        StringBuilder sb = new StringBuilder("**Bingo Item Acquisition Log**\n\n");
        
        boolean hasRecords = false;
        for (ItemAcquisitionRecord record : acquisitionRecords.values())
        {
            if (bingoItems.contains(record.getItemName().toLowerCase()))
            {
                sb.append(String.format(
                        "- **%s** via %s\n  %s\n  at %s (%s)\n\n",
                        record.getItemName(),
                        record.getMethod(),
                        record.getSourceDetails(),
                        record.getPlayerLocation(),
                        record.getTimestamp()
                ));
                hasRecords = true;
            }
        }

        if (!hasRecords)
        {
            return "No bingo item acquisitions recorded.";
        }

        return sb.toString();
    }

    /**
     * Clears the acquisition log.
     */
    public void clearAcquisitionLog()
    {
        acquisitionRecords.clear();
        log.debug("Acquisition log cleared");
    }

    /**
     * Saves the acquisition log to the configuration.
     * 
     * @param profileKey The profile-specific key to save under
     */
    public void saveAcquisitionLog(String profileKey)
    {
        if (acquisitionRecords.isEmpty())
        {
            return;
        }
        
        try
        {
            List<Map<String, String>> serializedRecords = new ArrayList<>();
            for (ItemAcquisitionRecord record : acquisitionRecords.values())
            {
                Map<String, String> serialized = new HashMap<>();
                serialized.put("itemName", record.getItemName());
                serialized.put("method", record.getMethod().toString());
                serialized.put("timestamp", record.getTimestamp().toString());
                serialized.put("sourceDetails", record.getSourceDetails());
                serialized.put("playerLocation", record.getPlayerLocation());
                serializedRecords.add(serialized);
            }
            
            String json = gson.toJson(serializedRecords);
            configManager.setConfiguration("bingo", profileKey, json);
            
            log.debug("Saved acquisition log with {} records", serializedRecords.size());
        }
        catch (Exception e)
        {
            log.error("Failed to save acquisition log", e);
        }
    }

    /**
     * Loads the acquisition log from the configuration.
     * 
     * @param profileKey The profile-specific key to load from
     */
    public void loadAcquisitionLog(String profileKey)
    {
        acquisitionRecords.clear();
        
        String json = configManager.getConfiguration("bingo", profileKey);
        if (json == null || json.isEmpty())
        {
            return;
        }
        
        try
        {
            Type type = new TypeToken<List<Map<String, String>>>(){}.getType();
            List<Map<String, String>> serializedRecords = gson.fromJson(json, type);
            
            for (Map<String, String> serialized : serializedRecords)
            {
                String itemName = serialized.get("itemName");
                AcquisitionMethod method = AcquisitionMethod.valueOf(serialized.get("method"));
                String sourceDetails = serialized.get("sourceDetails");
                String playerLocation = serialized.get("playerLocation");
                
                ItemAcquisitionRecord record = new ItemAcquisitionRecord(
                    itemName, method, sourceDetails, playerLocation);
                acquisitionRecords.put(itemName.toLowerCase(), record);
            }
            
            log.debug("Loaded acquisition log with {} records", serializedRecords.size());
        }
        catch (Exception e)
        {
            log.error("Failed to load acquisition log", e);
        }
    }

    /**
     * Gets the player's current location name, e.g., a region label.
     */
    public String getPlayerLocationName()
    {
        if (client.getLocalPlayer() == null)
        {
            return "Unknown";
        }

        LocalPoint localPoint = client.getLocalPlayer().getLocalLocation();
        if (localPoint != null)
        {
            WorldPoint worldPoint = WorldPoint.fromLocal(client, localPoint);
            return "Region " + worldPoint.getRegionID();
        }

        return "Region " + client.getLocalPlayer().getWorldLocation().getRegionID();
    }

    private String getCurrentPlayerName()
    {
        return client.getLocalPlayer() != null
                ? client.getLocalPlayer().getName()
                : "Unknown";
    }

    private int getCurrentWorldNumber()
    {
        return client.getWorld();
    }

    private String getCurrentWorldType()
    {
        return client.getWorldType().toString();
    }
}
