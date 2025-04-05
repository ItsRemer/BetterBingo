package com.betterbingo;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

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
        String display = items.stream().collect(Collectors.joining(" / "));
        
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
    
    /**
     * Creates a new BingoItemGroup with a single item.
     * 
     * @param itemName The name of the single item
     */
    public BingoItemGroup(String itemName) {
        this.displayName = itemName;
        this.itemNames = Arrays.asList(itemName);
        this.obtained = false;
    }
    
    /**
     * Checks if the given item name matches any item in this group.
     * 
     * @param itemName The item name to check
     * @return True if the item name matches any item in this group
     */
    public boolean containsItem(String itemName) {
        String lowerItemName = itemName.toLowerCase();
        for (String name : itemNames) {
            if (name.toLowerCase().equals(lowerItemName)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Gets the primary item name (first in the list).
     * 
     * @return The primary item name
     */
    public String getPrimaryItemName() {
        return itemNames.isEmpty() ? displayName : itemNames.get(0);
    }
    
    /**
     * Converts this group to a string representation for storage.
     * 
     * @return A colon-separated string of item names
     */
    public String toStorageString() {
        return itemNames.stream().collect(Collectors.joining(":"));
    }
} 