package com.betterbingo;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents a group of items where obtaining any one item marks the entire group as obtained.
 */
@Data
@Slf4j
public class BingoItemGroup {
    private final String displayName;
    private final List<String> itemNames;
    private boolean obtained;
    
    /**
     * Creates a new BingoItemGroup from a colon-separated string of item names.
     * 
     * @param itemGroupString The colon-separated string of item names
     * @return A new BingoItemGroup
     */
    public static BingoItemGroup fromString(String itemGroupString) {
        List<String> items = new ArrayList<>();
        
        // Split by colon and trim each item
        String[] parts = itemGroupString.split(":");
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                items.add(trimmed);
            }
        }
        
        if (items.isEmpty()) {
            // If no valid items were found, use the original string as a single item
            items.add(itemGroupString.trim());
        }
        
        // Create a display name from the items
        String display = String.join(" / ", items);
        
        return new BingoItemGroup(display, items);
    }
    
    /**
     * Creates a new BingoItemGroup.
     * 
     * @param displayName The display name for the group
     * @param itemNames The list of item names in the group
     */
    public BingoItemGroup(String displayName, List<String> itemNames) {
        this.displayName = displayName;
        this.itemNames = itemNames;
        this.obtained = false;
    }
} 