# RuneLite Bingo Plugin

A customizable Bingo plugin for RuneLite that allows players to track items for bingo events and competitions.

![Alt text](https://i.imgur.com/7t3LG39.png)![Alt text](https://i.imgur.com/nhL7qer.png)![Alt text](https://i.imgur.com/eLuluyv.png)

## Features

- **Bingo Board**: Track up to 25 items in a 5x5 grid
- **Item Source Options**: Load items manually or from a remote URL (Pastebin)
- **Progress Tracking**: Save obtained items between sessions
- **Discord Integration**: Send notifications and screenshots to Discord
- **Completion Notifications**: Special notifications for completing rows, columns, or the entire board
- **Anti-Cheat System**: Robust verification of item acquisitions
- **Profile System**: Create and manage multiple bingo profiles for different events

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

1. **Create a Profile**: Click the "New" button next to the profile dropdown
2. **Switch Profiles**: Select a different profile from the dropdown menu
3. **Profile Settings**: Click "Settings" to configure profile-specific options
4. **Delete a Profile**: Select a profile and click "Delete" (requires confirmation)

## Anti-Cheat System

The plugin includes a comprehensive anti-cheat system to ensure fair play in bingo events:

### Key Features

- **Always Active**: Item verification is always enabled and cannot be disabled
- **Acquisition Tracking**: Records detailed information about how items are obtained
- **Verification Checks**: Validates item acquisitions based on timing, location, and source
- **Discord Verification**: Sends acquisition logs to Discord for event organizers to verify
- **Screenshot Capture**: Takes screenshots when items are obtained for verification

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

## How It Works

1. The plugin loads the bingo items from the configured source
2. When an item is obtained in-game, the plugin records the acquisition details
3. The anti-cheat system validates the acquisition based on configured rules
4. If valid, the item is marked as obtained on the bingo board
5. Notifications are sent to the player and Discord (if configured)
6. The acquisition log is sent to Discord for verification

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

### Obtaining Items

The plugin automatically detects when you obtain an item on your bingo list through:
- NPC drops
- Collection log entries
- "Valuable drop" messages

When an item is obtained:
- It will be marked on your bingo board
- A game message will appear in your chat
- If configured, a notification with a screenshot will be sent to Discord

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

### Organizing Competitions

For clan events or competitions:
1. Create a shared Discord channel with a webhook
2. Have all participants use the same remote URL and Discord webhook
3. Everyone will see notifications when any participant obtains an item

## Limitations

- Maximum of 25 items can be displayed on the bingo board
- Some complex items or activities may not be automatically detected
- Screenshots may not capture the entire game window in certain configurations

## Support

If you encounter issues or have suggestions for improvements, please report them on the plugin's GitHub repository or contact the developer on Discord "itsremiq"

---

Happy Scaping and good luck with your Bingo events!
