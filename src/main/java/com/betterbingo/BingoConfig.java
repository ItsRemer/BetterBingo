package com.betterbingo;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ConfigGroup("betterbingo")
public interface BingoConfig extends Config
{
    @ConfigSection(
            name = "Display Settings",
            description = "Settings for UI display",
            position = 0
    )
    String displaySection = "displaySection";

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
    
    enum BingoMode
    {
        SOLO("Solo Bingo"),
        TEAM("Team Bingo");

        private final String name;

        BingoMode(String name)
        {
            this.name = name;
        }

        @Override
        public String toString()
        {
            return name;
        }
    }

    // Hidden settings used internally - not displayed in the RuneLite UI
    @ConfigItem(
            keyName = "bingoMode",
            name = "Bingo Mode",
            description = "Choose between Solo or Team Bingo",
            hidden = true
    )
    default BingoMode bingoMode()
    {
        return BingoMode.SOLO;
    }

    @ConfigItem(
            keyName = "teamCode",
            name = "Team Code",
            description = "The code for your BetterBingo team",
            hidden = true
    )
    default String teamCode()
    {
        return "";
    }

    @ConfigItem(
            keyName = "teamCode",
            name = "Team Code",
            description = "The code for your BetterBingo team",
            hidden = true
    )
    void setTeamCode(String teamCode);

    @ConfigItem(
            keyName = "createTeam",
            name = "Create New Team",
            description = "Create a new BetterBingo team",
            hidden = true
    )
    default boolean createTeam()
    {
        return false;
    }

    @ConfigItem(
            keyName = "createTeam",
            name = "Create New Team",
            description = "Create a new BetterBingo team",
            hidden = true
    )
    void setCreateTeam(boolean createTeam);

    @ConfigItem(
            keyName = "teamName",
            name = "Team Name",
            description = "The name for your new team",
            hidden = true
    )
    default String teamName()
    {
        return "";
    }

    @ConfigItem(
            keyName = "teamName",
            name = "Team Name",
            description = "The name for your new team",
            hidden = true
    )
    void setTeamName(String teamName);

    @ConfigItem(
            keyName = "discordWebhook",
            name = "Discord Webhook",
            description = "Discord webhook URL for team notifications",
            hidden = true
    )
    default String discordWebhook()
    {
        return "";
    }

    @ConfigItem(
            keyName = "discordWebhook",
            name = "Discord Webhook",
            description = "Discord webhook URL for team notifications",
            hidden = true
    )
    void setDiscordWebhook(String discordWebhook);

    @ConfigItem(
            keyName = "itemSourceType",
            name = "Item Source Type",
            description = "How to get the list of items for the bingo board",
            hidden = true
    )
    default ItemSourceType itemSourceType()
    {
        return ItemSourceType.MANUAL;
    }

    @ConfigItem(
            keyName = "itemSourceType",
            name = "Item Source Type",
            description = "How to get the list of items for the bingo board",
            hidden = true
    )
    void setItemSourceType(ItemSourceType itemSourceType);

    @ConfigItem(
            keyName = "remoteUrl",
            name = "Remote URL",
            description = "URL to fetch items from (for REMOTE source type)",
            hidden = true
    )
    default String remoteUrl()
    {
        return "";
    }

    @ConfigItem(
            keyName = "remoteUrl",
            name = "Remote URL",
            description = "URL to fetch items from (for REMOTE source type)",
            hidden = true
    )
    void setRemoteUrl(String remoteUrl);

    @ConfigItem(
            keyName = "manualItems",
            name = "Manual Items",
            description = "Comma-separated list of items (for MANUAL source type)",
            hidden = true
    )
    default String manualItems()
    {
        return "";
    }

    @ConfigItem(
            keyName = "manualItems",
            name = "Manual Items",
            description = "Comma-separated list of items (for MANUAL source type)",
            hidden = true
    )
    void setManualItems(String manualItems);

    @ConfigItem(
            keyName = "refreshInterval",
            name = "Refresh Interval (seconds)",
            description = "How often to refresh items from remote source (0 to disable)",
            hidden = true
    )
    default int refreshInterval()
    {
        return 0;
    }

    @ConfigItem(
            keyName = "refreshInterval",
            name = "Refresh Interval (seconds)",
            description = "How often to refresh items from remote source (0 to disable)",
            hidden = true
    )
    void setRefreshInterval(int refreshInterval);

    @ConfigItem(
            keyName = "persistObtained",
            name = "Persist Obtained Status",
            description = "Whether to persist obtained status when refreshing items",
            hidden = true
    )
    default boolean persistObtained()
    {
        return true;
    }

    @ConfigItem(
            keyName = "persistObtained",
            name = "Persist Obtained Status",
            description = "Whether to persist obtained status when refreshing items",
            hidden = true
    )
    void setPersistObtained(boolean persistObtained);

    // Display settings - the only ones visible in the RuneLite UI
    @ConfigItem(
            keyName = "showObtainedItems",
            name = "Show Obtained Items",
            description = "Whether to show items that have been obtained",
            section = displaySection,
            position = 0
    )
    default boolean showObtainedItems()
    {
        return true;
    }

    @ConfigItem(
            keyName = "showObtainedItems",
            name = "Show Obtained Items",
            description = "Whether to show items that have been obtained",
            section = displaySection,
            position = 0
    )
    void setShowObtainedItems(boolean showObtainedItems);

    @ConfigItem(
            keyName = "showItemIds",
            name = "Show Item IDs",
            description = "Whether to show item IDs in the bingo board",
            section = displaySection,
            position = 1
    )
    default boolean showItemIds()
    {
        return false;
    }

    @ConfigItem(
            keyName = "showItemIds",
            name = "Show Item IDs",
            description = "Whether to show item IDs in the bingo board",
            section = displaySection,
            position = 1
    )
    void setShowItemIds(boolean showItemIds);

    @ConfigItem(
            keyName = "completionNotifications",
            name = "Completion Notifications",
            description = "Send special notifications when completing a row, column, or the entire board",
            section = displaySection,
            position = 2
    )
    default boolean completionNotifications()
    {
        return true;
    }

    @ConfigItem(
            keyName = "completionNotifications",
            name = "Completion Notifications",
            description = "Send special notifications when completing a row, column, or the entire board",
            section = displaySection,
            position = 2
    )
    void setCompletionNotifications(boolean completionNotifications);

    @ConfigItem(
            keyName = "showChatNotifications",
            name = "Show Chat Notifications",
            description = "Show notifications in the chat window when obtaining items",
            section = displaySection,
            position = 3
    )
    default boolean showChatNotifications()
    {
        return true;
    }

    @ConfigItem(
            keyName = "showChatNotifications",
            name = "Show Chat Notifications",
            description = "Show notifications in the chat window when obtaining items",
            section = displaySection,
            position = 3
    )
    void setShowChatNotifications(boolean showChatNotifications);

    @ConfigItem(
            keyName = "showSidebar",
            name = "Show Sidebar",
            description = "Show the Bingo panel in the sidebar",
            section = displaySection,
            position = 4
    )
    default boolean showSidebar()
    {
        return true;
    }

    @ConfigItem(
            keyName = "showSidebar",
            name = "Show Sidebar",
            description = "Show the Bingo panel in the sidebar",
            section = displaySection,
            position = 4
    )
    void setShowSidebar(boolean showSidebar);

    @ConfigItem(
            keyName = "currentProfile",
            name = "Current Profile",
            description = "The currently active bingo profile",
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
            name = "Profiles",
            description = "Comma-separated list of profile names",
            hidden = true
    )
    default String profiles()
    {
        return "default";
    }

    @ConfigItem(
            keyName = "profiles",
            name = "Profiles",
            description = "Comma-separated list of profile names",
            hidden = true
    )
    void setProfiles(String profiles);

    Optional<String> getRemoteUrl();
} 