package com.betterbingo;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import lombok.Getter;

/**
 * Class containing patterns for matching chat messages related to item drops
 * Used by the Bingo plugin to detect when items are obtained
 */
public class BingoChatPatterns {

    /**
     * Represents a pattern for matching chat messages related to item drops
     */
    public static class ChatMessagePattern {
        private final String pattern;
        private final Function<String, String> itemExtractor;
        @Getter
        private final boolean multiItem;

        public ChatMessagePattern(String pattern, Function<String, String> itemExtractor, boolean multiItem) {
            this.pattern = pattern;
            this.itemExtractor = itemExtractor;
            this.multiItem = multiItem;
        }

        public boolean matches(String message) {
            return message.contains(pattern);
        }

        public String extractItem(String message) {
            return itemExtractor.apply(message);
        }
    }

    /**
     * List of all chat patterns for detecting items
     */
    public static final List<ChatMessagePattern> CHAT_PATTERNS = Arrays.asList(
            // Regular drops
            new ChatMessagePattern("Valuable drop:",
                    message -> message.substring(message.indexOf(":") + 2), false),
            new ChatMessagePattern("You receive a drop:",
                    message -> message.substring(message.indexOf(":") + 2), false),

            // Pet drops
            new ChatMessagePattern("You have a funny feeling like",
                    message -> message.substring(0, message.indexOf("like you") - 1)
                            .replace("You have a funny feeling ", ""), false),

            // Collection log
            new ChatMessagePattern("New item added to your collection log:",
                    message -> message.substring(message.indexOf(":") + 2), false),

            // Raids drops with specific format
            new ChatMessagePattern("- ",
                    message -> {
                        if (message.contains("Chambers of Xeric") ||
                                message.contains("Theatre of Blood") ||
                                message.contains("Tombs of Amascut")) {
                            return message.substring(message.indexOf("- ") + 2);
                        }
                        return null;
                    }, false),

            // Special raid loot messages
            new ChatMessagePattern("Special loot:",
                    message -> message.substring(message.indexOf(":") + 2), true),

            // Chest opening messages
            new ChatMessagePattern("You open the chest and find:",
                    message -> message.substring(message.indexOf("find:") + 6), true),
            new ChatMessagePattern("You find some loot:",
                    message -> message.substring(message.indexOf("loot:") + 6), true),
            new ChatMessagePattern("Your loot is:",
                    message -> message.substring(message.indexOf("is:") + 4), true),

            // Special drop notifications
            new ChatMessagePattern("received a special drop:",
                    message -> message.substring(message.indexOf("drop:") + 6), false),
            new ChatMessagePattern("received unique loot:",
                    message -> message.substring(message.indexOf("loot:") + 6), false),
                    
            // Fortis Colosseum rewards
            new ChatMessagePattern("The Fortis Colosseum rewards you with:",
                    message -> message.substring(message.indexOf("with:") + 6), true),
            new ChatMessagePattern("You open the champion's chest and find:",
                    message -> message.substring(message.indexOf("find:") + 6), true),
                    
            // The Gauntlet rewards
            new ChatMessagePattern("You open the crystal chest and find:",
                    message -> message.substring(message.indexOf("find:") + 6), true),
            new ChatMessagePattern("The corrupted chest contains:",
                    message -> message.substring(message.indexOf("contains:") + 10), true),
                    
            // Barrows rewards
            new ChatMessagePattern("Your Barrows chest contains:",
                    message -> message.substring(message.indexOf("contains:") + 10), true),
                    
            // Moons of Peril rewards
            new ChatMessagePattern("The shadow chest contains:",
                    message -> message.substring(message.indexOf("contains:") + 10), true),
            new ChatMessagePattern("You have completed the Moon of Peril! You find:",
                    message -> message.substring(message.indexOf("find:") + 6), true),
                    
            // Wintertodt rewards
            new ChatMessagePattern("You open the supply crate and find:",
                    message -> message.substring(message.indexOf("find:") + 6), true),
            new ChatMessagePattern("You open the supply crate and find some",
                    message -> message.substring(message.indexOf("some") + 5), true),
                    
            // Tempoross rewards
            new ChatMessagePattern("You search the reward pool and find:",
                    message -> message.substring(message.indexOf("find:") + 6), true),
            new ChatMessagePattern("You search the reward pool and find some",
                    message -> message.substring(message.indexOf("some") + 5), true)
    );

    /**
     * Extracts an item name from a string, handling quantity indicators and special cases
     *
     * @param rawItemName The raw item name to clean
     * @return The cleaned item name or null if invalid
     */
    public static String cleanItemName(String rawItemName) {
        if (rawItemName == null || rawItemName.trim().isEmpty()) {
            return null;
        }

        String itemName = rawItemName.trim();

        // Handle "x quantity" format (e.g., "5 x Bones")
        if (itemName.contains(" x ")) {
            if (itemName.indexOf("x ") > 0) {
                itemName = itemName.substring(itemName.indexOf("x ") + 2).trim();
            }
        }

        // Handle "quantity x" format (e.g., "5x Bones")
        if (itemName.matches(".*\\d+x .*")) {
            itemName = itemName.replaceAll("\\d+x ", "").trim();
        }

        // Handle "Item (quantity)" format (e.g., "Bones (5)")
        if (itemName.matches(".*\\(\\d+\\)$")) {
            itemName = itemName.replaceAll("\\s*\\(\\d+\\)$", "").trim();
        }

        return itemName;
    }
} 