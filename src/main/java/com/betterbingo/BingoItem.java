package com.betterbingo;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class BingoItem
{
    @EqualsAndHashCode.Include
    private final String name;
    private boolean obtained;
    private int itemId;
    private List<String> alternativeNames;
    private boolean isGroup;

    public BingoItem(String name)
    {
        this.name = name;
        this.obtained = false;
        this.itemId = -1;
        this.alternativeNames = new ArrayList<>();
        this.isGroup = false;
    }

    public BingoItem(String name, int itemId)
    {
        this.name = name;
        this.obtained = false;
        this.itemId = itemId;
        this.alternativeNames = new ArrayList<>();
        this.isGroup = false;
    }
    
    /**
     * Copy constructor for BingoItem.
     * Creates a deep copy of the given BingoItem.
     * 
     * @param other The BingoItem to copy
     */
    public BingoItem(BingoItem other) {
        this.name = other.name;
        this.obtained = other.obtained;
        this.itemId = other.itemId;
        this.isGroup = other.isGroup;
        
        // Deep copy of alternative names
        if (other.alternativeNames != null) {
            this.alternativeNames = new ArrayList<>(other.alternativeNames);
        } else {
            this.alternativeNames = new ArrayList<>();
        }
    }
    
    /**
     * Creates a BingoItem from a BingoItemGroup.
     * 
     * @param group The BingoItemGroup to create the item from
     * @param itemManager The ItemManager to use for looking up item IDs
     * @return A new BingoItem representing the group
     */
    public static BingoItem fromItemGroup(BingoItemGroup group, net.runelite.client.game.ItemManager itemManager) {
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
        if (!group.getItemNames().isEmpty()) {
            String firstItemName = group.getItemNames().get(0);
            net.runelite.http.api.item.ItemPrice itemPrice = lookupItemByName(firstItemName, itemManager);
            if (itemPrice != null) {
                item.setItemId(itemPrice.getId());
            }
        }
        
        return item;
    }
    
    /**
     * Looks up an item by name using the ItemManager.
     * 
     * @param itemName The name of the item to look up
     * @param itemManager The ItemManager to use for looking up items
     * @return The ItemPrice for the item, or null if not found
     */
    private static net.runelite.http.api.item.ItemPrice lookupItemByName(String itemName, net.runelite.client.game.ItemManager itemManager) {
        List<net.runelite.http.api.item.ItemPrice> results = itemManager.search(itemName);
        if (!results.isEmpty()) {
            return results.get(0);
        }
        return null;
    }
    
    /**
     * Checks if this item matches the given name, including alternative names.
     * 
     * @param itemName The item name to check
     * @return True if this item matches the given name
     */
    public boolean matchesName(String itemName) {
        if (name.equalsIgnoreCase(itemName)) {
            return true;
        }
        
        if (alternativeNames != null) {
            for (String altName : alternativeNames) {
                if (altName.equalsIgnoreCase(itemName)) {
                    return true;
                }
            }
        }
        
        return false;
    }
} 