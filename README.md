# BetterBingo Plugin for RuneLite

A RuneLite plugin for tracking items in bingo events with anti-cheat features, Discord integration, and profile management.

## Recent Updates

### Team System (New!)

We've added a team system that allows players to collaborate on bingo events:

- **Team Creation**: Create a team and share the team code with other players
- **Team Joining**: Join an existing team using a team code
- **Shared Progress**: See items obtained by team members on your bingo board
- **Item Sharing**: Automatically share items you obtain with your team
- **Item Importing**: Automatically import the team leader's bingo items when joining a team
- **Profile Integration**: Team settings are stored with each profile, allowing participation in multiple teams

### Profile System Improvements

We've made several important improvements to the profile system:

- **Fixed Item Persistence**: Corrected issues with saving and loading items when switching profiles
- **Enhanced Profile Switching**: Items are now properly saved before switching to another profile
- **Improved Profile Deletion**: Added ability to delete the current profile by automatically switching to another profile first
- **Better Error Handling**: Added proper validation to prevent deleting the last remaining profile

## Features

- **Bingo Board**: Track up to 25 items in a 5x5 grid
- **Item Source Options**: Load items manually or from a remote URL (Pastebin)
- **Progress Tracking**: Save obtained items between sessions
- **Discord Integration**: Send notifications and screenshots to Discord
- **Completion Notifications**: Special notifications for completing rows, columns, or the entire board
- **Anti-Cheat System**: Robust verification of item acquisitions
- **Profile System**: Create and manage multiple bingo profiles for different events
- **Team System**: Collaborate with other players on bingo events

## Profile System

The plugin includes a comprehensive profile management system that allows players to participate in multiple bingo events simultaneously:

### Key Features

- **Multiple Profiles**: Create named profiles for different bingo events or challenges
- **Easy Switching**: Quickly switch between profiles from the main panel
- **Independent Settings**: Each profile maintains its own:
    - Item list
    - Obtained items
    - Remote URL configuration
    - Refresh interval
    - Persistence settings
- **Profile Management**: Create new profiles, delete existing ones, and customize settings per profile
- **Reset Option**: Reset a profile's progress without affecting other profiles

### Using Profiles

The profile panel at the top of the bingo board allows you to:
- Select a profile from the dropdown menu
- Create a new profile with the "New" button
- Delete the selected profile with the "Delete" button
- Configure profile-specific settings with the "Settings" button

Each profile maintains its own item list, obtained items, and configuration settings.

#### Creating a Team Profile

When creating a new profile, you can set it up as a team profile:
1. Click the "New" button next to the profile dropdown
2. Enter a name for your profile
3. Check "This is a team profile"
4. Select "Team Leader" if you're creating a new team, or "Team Member" if you're joining an existing team
5. For team leaders:
    - Enter a name for your team
    - Optionally, enter a Discord webhook URL for notifications
    - Click "OK" to create the profile and generate a team code
    - Share the generated team code with your team members
6. For team members:
    - Enter the team code provided by your team leader
    - Optionally, enter a Discord webhook URL for notifications
    - Click "OK" to create the profile and join the team

### Using Teams

Items obtained by team members will be displayed in green on your bingo board.

## Anti-Cheat System

The plugin includes a comprehensive anti-cheat system to ensure fair play in bingo events:

### Key Features

- **Always Active**: Item verification is always enabled and cannot be disabled
- **Acquisition Tracking**: Records detailed information about how items are obtained
- **Verification Checks**: Validates item acquisitions based on timing, location, and source
- **Discord Verification**: Sends acquisition logs to Discord for event organizers to verify
- **Screenshot Capture**: Takes screenshots when items are obtained for verification

### Recent Refactoring

The plugin has undergone significant refactoring to improve code organization and maintainability:

1. **Separated Anti-Cheat Logic**: Moved all anti-cheat functionality to a dedicated `BingoAntiCheat` class
2. **Discord Notification System**: Extracted Discord webhook functionality to a `BingoDiscordNotifier` class
3. **Improved Configuration**: Removed toggles for anti-cheat features, making verification mandatory
4. **Enhanced Validation**: Strengthened verification checks for item acquisitions
5. **Better Documentation**: Added comprehensive documentation for all methods
6. **Profile Management System**: Added support for multiple bingo profiles with independent settings
7. **Fixed Item Persistence**: Corrected issues with saving and loading items when switching profiles
8. **Improved Profile Deletion**: Enhanced the profile deletion process with better error handling
9. **Team System**: Added support for team-based bingo events with shared progress

## Configuration Options

- **Item Source**: Choose between manual input or remote URL
- **Bingo Items**: List of items to track (when using manual input)
- **Remote URL**: URL to fetch bingo items from (supports Pastebin)
- **Refresh Interval**: How often to refresh remote items
- **Save Progress**: Save obtained items between sessions
- **Send Screenshots**: Send screenshots to Discord when obtaining items
- **Discord Webhook URL**: Discord integration for notifications
- **Completion Notifications**: Special notifications for completing rows, columns, or the board
- **Verification Timeout**: Maximum time between item acquisition and marking
- **Current Profile**: Select which profile to use
- **Profiles**: Create, delete, and manage multiple bingo profiles
- **Team Features**: Enable team collaboration for bingo events
- **Team Code**: Enter a team code to join an existing team
- **Team Name**: Set a name for your team
- **Share Items with Team**: When enabled, items you obtain will be shared with your team
- **Import Leader's Items**: When joining a team, import the team leader's bingo items
- **Event Discord Webhook**: Discord webhook for team notifications (optional)

## How It Works

1. The plugin loads the bingo items from the configured source
2. When an item is obtained in-game, the plugin records the acquisition details
3. The anti-cheat system validates the acquisition based on configured rules
4. If valid, the item is marked as obtained on the bingo board
5. Notifications are sent to the player and Discord (if configured)
6. The acquisition log is sent to Discord for verification
7. If team features are enabled, team members are notified of the acquisition

## Development

The plugin is organized into several key classes:

- `BingoPlugin.java`: Main plugin class that handles RuneLite integration
- `BingoAntiCheat.java`: Handles anti-cheat verification and acquisition tracking
- `BingoDiscordNotifier.java`: Manages Discord webhook notifications and screenshot sharing
- `BingoConfig.java`: Configuration interface for the plugin
- `BingoPanel.java`: UI panel for displaying the bingo board
- `BingoItem.java`: Represents an item on the bingo board
- `BingoProfileManager.java`: Manages bingo profiles for multiple events
- `BingoTeamManager.java`: Manages team functionality for collaborative bingo events

## Setup Guide

### Basic Setup

1. **Enable the Plugin**: Find "Bingo" in the plugin list and enable it
2. **Access the Panel**: Click the Bingo icon in the side panel to open the bingo board

### Configuration Options

#### Item Source

The plugin offers two ways to input bingo items:

1. **Manual Input**:
    - Select "Manual Input" from the "Item Source" dropdown
    - Enter item names in the "Bingo Items" text area.
    - Example: `Dragon Scimitar;Abyssal whip;Armadyl hilt;"

2. **Remote URL**:
    - Select "Remote URL" from the "Item Source" dropdown
    - Enter a URL in the "Remote URL" field (supports Pastebin links)
    - For Pastebin, you can use either the regular link or the raw link
    - Set a refresh interval (in minutes) to automatically update items

#### Discord Integration

To receive notifications when items are obtained:

1. Create a Discord webhook in your server:
    - Go to Server Settings > Integrations > Webhooks
    - Create a new webhook and copy the URL
2. Paste the webhook URL in the "Discord webhook URL" field
3. Enable "Send Screenshots" to include game screenshots with notifications

#### Additional Settings

- **Save Progress**: Enable to save which items you've obtained between sessions
- **Refresh Interval**: How often to check for updates from the remote URL (in minutes)

## Using the Plugin

### Viewing Your Bingo Board

The bingo board displays up to 25 items in a 5x5 grid. Items you've obtained will be marked with a checkmark and displayed in green.

### Managing Profiles

The profile panel at the top of the bingo board allows you to:
- Select a profile from the dropdown menu
- Create a new profile with the "New" button
- Delete the selected profile with the "Delete" button
- Configure profile-specific settings with the "Settings" button

Each profile maintains its own item list, obtained items, and configuration settings.

### Using Teams

The team panel allows you to:
- Create a new team with the "Create Team" button
- Join an existing team by entering a team code and clicking "Join Team"
- See team progress in the team progress panel

Items obtained by team members will be displayed in blue on your bingo board.

### Obtaining Items

The plugin automatically detects when you obtain an item on your bingo list through:
- NPC drops
- Collection log entries
- "Valuable drop" messages

When an item is obtained:
- It will be marked on your bingo board
- A game message will appear in your chat
- If configured, a notification with a screenshot will be sent to Discord
- If team features are enabled, team members will be notified

### Control Buttons

- **Reset Board**: Clears all progress (requires confirmation)
- **Update from URL**: Manually refreshes items from the remote URL (only visible in Remote URL mode)

## Troubleshooting

### Common Issues

1. **Items Not Being Detected**:
    - Ensure the item name matches exactly (including spaces and capitalization)
    - Some items may need to be detected through the collection log or chat messages

2. **Remote URL Not Working**:
    - Verify the URL is accessible and contains a list of items
    - For Pastebin, ensure the paste is public
    - The plugin supports both regular and raw Pastebin URLs

3. **Discord Notifications Not Sending**:
    - Verify the webhook URL is correct
    - Ensure the webhook has permission to send messages in the channel

4. **Team Features Not Working**:
    - Ensure the team code is entered correctly
    - Verify the event Discord webhook URL is correct
    - Make sure team features are enabled in the plugin settings

### Item Format

For best results, use the exact in-game name of items. The plugin will attempt to match items to their in-game IDs for better display.

## Advanced Usage

### Sharing Bingo Boards

To share a bingo board with others:
1. Create a text file with one item per line
2. Upload it to Pastebin or a similar service
3. Share the URL with other players
4. Each player can enter the URL in their Remote URL field

### Using Multiple Profiles

For players participating in multiple bingo events:
1. Create a separate profile for each event
2. Configure each profile with the appropriate item source
3. Switch between profiles as needed without losing progress
4. Use profile-specific settings to customize each bingo board

### Organizing Team Competitions

For clan events or competitions:
1. Create a shared Discord channel with a webhook
2. Have all participants use the same webhook URL
3. Create teams and share team codes with team members
4. Everyone will see notifications when any team member obtains an item

## Limitations

- Maximum of 25 items can be displayed on the bingo board
- Some complex items or activities may not be automatically detected
- Screenshots may not capture the entire game window in certain configurations
- Team progress is not synchronized automatically when you join a team - only new item acquisitions are shared

## Support

If you encounter issues or have suggestions for improvements, please report them on the plugin's GitHub repository or contact itsremiq on discord

---

Happy Scaping and good luck with your Bingo events!
