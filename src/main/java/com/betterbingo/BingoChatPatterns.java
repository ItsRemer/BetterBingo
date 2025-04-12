package com.betterbingo;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.Getter;
import net.runelite.api.MessageNode;
import net.runelite.api.ChatMessageType;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.Constants;

/**
 * Class containing patterns for matching chat messages related to item drops
 * Used by the Bingo plugin to detect when items are obtained
 */
public class BingoChatPatterns {

    // Constants for commonly used chat patterns
    public static final String VALUABLE_DROP_PREFIX = "Valuable drop:";
    public static final String COLLECTION_LOG_PREFIX = "New item added to your collection log:";
    public static final String PET_DROP_PREFIX = "You have a funny feeling like";
    public static final String CHEST_FIND_PREFIX = "You open the chest and find:";
    public static final String LOOT_PREFIX = "You find some loot:";
    
    // Constants for raid names
    public static final String CHAMBERS_OF_XERIC = "Chambers of Xeric";
    public static final String THEATRE_OF_BLOOD = "Theatre of Blood";
    public static final String TOMBS_OF_AMASCUT = "Tombs of Amascut";

    // Precompiled regex patterns for item name cleaning
    private static final Pattern QUANTITY_X_PATTERN = Pattern.compile("\\d+\\s*x\\s+(.+)");
    private static final Pattern X_QUANTITY_PATTERN = Pattern.compile("(.+?)\\s+x\\s+\\d+");
    private static final Pattern NUMBERED_PATTERN = Pattern.compile("^[\\d,]+\\s+(.+)");
    private static final Pattern PARENTHESIS_PATTERN = Pattern.compile("(.+?)\\s*\\(\\d+\\)$");
    private static final Pattern COMBINED_QUANTITY_PATTERN = Pattern.compile("^(?:\\d+(?:,\\d+)*\\s+|\\d+x\\s+)(.*)", Pattern.CASE_INSENSITIVE);

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
            new ChatMessagePattern(VALUABLE_DROP_PREFIX,
                    message -> message.substring(message.indexOf(":") + 2), false),
            new ChatMessagePattern("You receive a drop:",
                    message -> message.substring(message.indexOf(":") + 2), false),

            // Pet drops
            new ChatMessagePattern(PET_DROP_PREFIX,
                    message -> message.substring(0, message.indexOf("like you") - 1)
                            .replace("You have a funny feeling ", ""), false),

            // Collection log
            new ChatMessagePattern(COLLECTION_LOG_PREFIX,
                    message -> message.substring(message.indexOf(":") + 2), false),

            // Raids drops with specific format
            new ChatMessagePattern("- ",
                    message -> {
                        if (message.contains(CHAMBERS_OF_XERIC) ||
                                message.contains(THEATRE_OF_BLOOD) ||
                                message.contains(TOMBS_OF_AMASCUT)) {
                            return message.substring(message.indexOf("- ") + 2);
                        }
                        return null;
                    }, false),

            // Special raid loot messages
            new ChatMessagePattern("Special loot:",
                    message -> message.substring(message.indexOf(":") + 2), true),

            // Chest opening messages
            new ChatMessagePattern(CHEST_FIND_PREFIX,
                    message -> message.substring(message.indexOf("find:") + 6), true),
            new ChatMessagePattern(LOOT_PREFIX,
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

        // Single optimized pattern to handle common quantity formats
        Matcher combinedMatcher = COMBINED_QUANTITY_PATTERN.matcher(itemName);
        if (combinedMatcher.matches()) {
            return combinedMatcher.group(1);
        }

        // Handle "x quantity" format (e.g., "5 x Bones")
        Matcher xQuantityMatcher = X_QUANTITY_PATTERN.matcher(itemName);
        if (xQuantityMatcher.matches()) {
            return xQuantityMatcher.group(1);
        }

        // Handle "Item (quantity)" format (e.g., "Bones (5)")
        Matcher parenthesisMatcher = PARENTHESIS_PATTERN.matcher(itemName);
        if (parenthesisMatcher.matches()) {
            return parenthesisMatcher.group(1);
        }

        return itemName;
    }
} 