# RuneLite Bingo Plugin

A customizable Bingo plugin for RuneLite that allows players to track items for bingo events and competitions.

![Alt text](https://i.imgur.com/K9gEzpm.png) ![Alt text](https://i.imgur.com/sREZ2Ba.png)

## Features

- **Dual Input Methods**: Choose between manually entering items or loading them from a remote URL (such as Pastebin)
- **Item Recognition**: Automatically detects when you obtain items on your bingo list
- **Visual Grid Display**: Shows your bingo items in a 5x5 grid with visual indicators for obtained items
- **Discord Integration**: Sends notifications with screenshots to a Discord channel when items are obtained
- **Progress Saving**: Optionally saves your progress between sessions
- **Remote Updates**: Automatically refreshes items from remote sources at configurable intervals

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

If you encounter issues or have suggestions for improvements, please report them on the plugin's GitHub repository or contact the developer.

---

Happy Scaping and good luck with your Bingo events!
