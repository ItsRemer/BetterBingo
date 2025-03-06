package com.betterbingo;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("bingo")
public interface BingoConfig extends Config
{
    enum ItemSourceType
    {
        MANUAL("Manual Input"),
        REMOTE("Remote URL");

        private final String name;

        ItemSourceType(String name)
        {
            this.name = name;
        }

        @Override
        public String toString()
        {
            return name;
        }
    }

    @ConfigItem(
            keyName = "itemSourceType",
            name = "Item Source",
            description = "Choose how to provide bingo items",
            position = 0,
            hidden = true
    )
    default ItemSourceType itemSourceType()
    {
        return ItemSourceType.MANUAL;
    }

    @ConfigItem(
            keyName = "itemList",
            name = "Bingo Items",
            description = "List of items for bingo (one per line)",
            position = 1,
            hidden = true
    )
    default String itemList()
    {
        return "Dragon Scimitar\nAbyssal Whip\nFire Cape\nBarrows Gloves\nDragon Boots";
    }

    @ConfigItem(
            keyName = "remoteUrl",
            name = "Remote URL",
            description = "URL to fetch bingo items from (supports Pastebin links)",
            position = 2,
            hidden = true
    )
    default String remoteUrl()
    {
        return "";
    }

    @ConfigItem(
            keyName = "refreshInterval",
            name = "Refresh Interval",
            description = "How often to refresh remote items (in minutes)",
            position = 3,
            hidden = true
    )
    default int refreshInterval()
    {
        return 5;
    }

    @ConfigItem(
            keyName = "persistObtained",
            name = "Save Progress",
            description = "When disabled, obtained items will be saved between sessions. When enabled, obtained items will NOT be saved between sessions.",
            position = 4,
            hidden = true
    )
    default boolean persistObtained()
    {
        return false;
    }

    @ConfigItem(
            keyName = "sendScreenshot",
            name = "Send Screenshots",
            description = "Send screenshots to Discord when obtaining items",
            position = 5
    )
    default boolean sendScreenshot()
    {
        return true;
    }

    @ConfigItem(
            keyName = "discordWebhookUrl",
            name = "Discord webhook URL",
            description = "Discord webhook integration",
            position = 6
    )
    default String discordWebhookUrl()
    {
        return "";
    }

    @ConfigItem(
            keyName = "completionNotifications",
            name = "Completion Notifications",
            description = "Send special notifications when completing a row, column, or the entire board",
            position = 7
    )
    default boolean completionNotifications()
    {
        return true;
    }

    @ConfigItem(
            keyName = "currentProfile",
            name = "Current Profile",
            description = "The currently active bingo profile",
            position = 8,
            hidden = true
    )
    default String currentProfile()
    {
        return "default";
    }

    @ConfigItem(
            keyName = "currentProfile",
            name = "Current Profile",
            description = "The currently active bingo profile",
            hidden = true
    )
    void setCurrentProfile(String currentProfile);

    @ConfigItem(
            keyName = "profiles",
            name = "Bingo Profiles",
            description = "List of available bingo profiles",
            hidden = true
    )
    default String profiles()
    {
        return "default";
    }

    @ConfigItem(
            keyName = "profiles",
            name = "Bingo Profiles",
            description = "List of available bingo profiles",
            hidden = true
    )
    void setProfiles(String profiles);
} 