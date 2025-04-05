package com.betterbingo;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Domain model for team data.
 */
@Data
@Builder
public class TeamData {
    private String teamCode;
    private String teamName;
    private String discordWebhook;
    private BingoConfig.ItemSourceType itemSourceType;
    private String remoteUrl;
    private String manualItems;
    private int refreshInterval;
    private boolean persistObtained;
    
    @Builder.Default
    private List<BingoItem> items = new ArrayList<>();
    
    /**
     * Creates a TeamData object from a map of data.
     *
     * @param data The map of data
     * @return The TeamData object
     */
    public static TeamData fromMap(java.util.Map<String, Object> data) {
        if (data == null) {
            return null;
        }
        
        TeamDataBuilder builder = TeamData.builder()
            .teamCode((String) data.get("teamCode"))
            .teamName((String) data.get("teamName"))
            .discordWebhook((String) data.get("discordWebhook"));
        
        // Handle item source type
        String itemSourceTypeStr = (String) data.get("itemSourceType");
        if (itemSourceTypeStr != null) {
            try {
                BingoConfig.ItemSourceType itemSourceType = 
                    BingoConfig.ItemSourceType.valueOf(itemSourceTypeStr);
                builder.itemSourceType(itemSourceType);
            } catch (IllegalArgumentException e) {
                // Default to MANUAL if the value is invalid
                builder.itemSourceType(BingoConfig.ItemSourceType.MANUAL);
            }
        } else {
            builder.itemSourceType(BingoConfig.ItemSourceType.MANUAL);
        }
        
        builder.remoteUrl((String) data.get("remoteUrl"))
            .manualItems((String) data.get("manualItems"));
        
        // Handle refresh interval
        Object refreshIntervalObj = data.get("refreshInterval");
        if (refreshIntervalObj instanceof Number) {
            builder.refreshInterval(((Number) refreshIntervalObj).intValue());
        } else if (refreshIntervalObj instanceof String) {
            try {
                builder.refreshInterval(Integer.parseInt((String) refreshIntervalObj));
            } catch (NumberFormatException e) {
                builder.refreshInterval(5); // Default value
            }
        } else {
            builder.refreshInterval(5); // Default value
        }
        
        // Handle persist obtained
        Object persistObtainedObj = data.get("persistObtained");
        if (persistObtainedObj instanceof Boolean) {
            builder.persistObtained((Boolean) persistObtainedObj);
        } else if (persistObtainedObj instanceof String) {
            builder.persistObtained(Boolean.parseBoolean((String) persistObtainedObj));
        } else {
            builder.persistObtained(false); // Default value
        }
        
        // Handle items
        List<BingoItem> items = new ArrayList<>();
        Object itemsObj = data.get("items");
        if (itemsObj instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> itemsList = (List<Object>) itemsObj;
            for (Object itemObj : itemsList) {
                if (itemObj instanceof java.util.Map) {
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> itemMap = (java.util.Map<String, Object>) itemObj;
                    
                    String name = (String) itemMap.get("name");
                    if (name != null) {
                        // Create a new BingoItem with the name
                        BingoItem item = new BingoItem(name);
                        
                        // Handle obtained status
                        Object obtainedObj = itemMap.get("obtained");
                        if (obtainedObj instanceof Boolean) {
                            item.setObtained((Boolean) obtainedObj);
                        } else if (obtainedObj instanceof String) {
                            item.setObtained(Boolean.parseBoolean((String) obtainedObj));
                        }
                        
                        // Handle item ID
                        Object itemIdObj = itemMap.get("itemId");
                        if (itemIdObj instanceof Number) {
                            item.setItemId(((Number) itemIdObj).intValue());
                        } else if (itemIdObj instanceof String) {
                            try {
                                item.setItemId(Integer.parseInt((String) itemIdObj));
                            } catch (NumberFormatException e) {
                                // Keep the default item ID
                            }
                        }
                        
                        // Handle group info
                        Object isGroupObj = itemMap.get("isGroup");
                        if (isGroupObj instanceof Boolean && (Boolean) isGroupObj) {
                            item.setGroup(true);
                            
                            // Handle alternative names
                            Object altNamesObj = itemMap.get("alternativeNames");
                            if (altNamesObj instanceof List) {
                                @SuppressWarnings("unchecked")
                                List<String> altNames = new ArrayList<>((List<String>) altNamesObj);
                                item.setAlternativeNames(altNames);
                            }
                        }
                        
                        items.add(item);
                    }
                }
            }
        }
        builder.items(items);
        
        return builder.build();
    }
    
    /**
     * Converts this TeamData object to a map.
     *
     * @return The map representation of this TeamData object
     */
    public java.util.Map<String, Object> toMap() {
        java.util.Map<String, Object> map = new java.util.HashMap<>();
        map.put("teamCode", teamCode);
        map.put("teamName", teamName);
        map.put("discordWebhook", discordWebhook);
        map.put("itemSourceType", itemSourceType != null ? itemSourceType.toString() : BingoConfig.ItemSourceType.MANUAL.toString());
        map.put("remoteUrl", remoteUrl);
        map.put("manualItems", manualItems);
        map.put("refreshInterval", refreshInterval);
        map.put("persistObtained", persistObtained);
        
        // Convert items to a list of maps
        List<java.util.Map<String, Object>> itemsList = new ArrayList<>();
        for (BingoItem item : items) {
            java.util.Map<String, Object> itemMap = new java.util.HashMap<>();
            itemMap.put("name", item.getName());
            itemMap.put("obtained", item.isObtained());
            itemMap.put("itemId", item.getItemId());
            
            // Add group info if applicable
            if (item.isGroup()) {
                itemMap.put("isGroup", true);
                if (item.getAlternativeNames() != null && !item.getAlternativeNames().isEmpty()) {
                    itemMap.put("alternativeNames", item.getAlternativeNames());
                }
            }
            
            itemsList.add(itemMap);
        }
        map.put("items", itemsList);
        
        return map;
    }
} 