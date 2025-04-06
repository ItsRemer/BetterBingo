package com.betterbingo;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.util.List;
import javax.inject.Inject;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;

import lombok.NonNull;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.PluginErrorPanel;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.http.api.item.ItemPrice;

import javax.swing.JButton;
import javax.swing.Box;
import javax.swing.JComboBox;
import javax.swing.JOptionPane;
import javax.swing.JCheckBox;
import javax.swing.JTextField;
import javax.swing.JTextArea;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.BoxLayout;
import javax.swing.SwingUtilities;
import javax.swing.ButtonGroup;
import javax.swing.JRadioButton;
import javax.swing.JDialog;
import javax.swing.JWindow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import javax.swing.Timer;
import java.awt.AWTEvent;

public class BingoPanel extends PluginPanel {
    private static final ImageIcon CHECK_MARK = new ImageIcon(ImageUtil.loadImageResource(BingoPlugin.class, "/check.png").getScaledInstance(24, 24, Image.SCALE_SMOOTH));
    private static final Dimension ITEM_SIZE = new Dimension(32, 32);
    private static final int GRID_SIZE = 5; // 5x5 bingo board
    private static final int GRID_GAP = 0; // No gap between cells
    private static final int MAX_ITEMS = GRID_SIZE * GRID_SIZE; // Maximum 25 items

    private final JPanel itemsContainer = new JPanel();
    private final PluginErrorPanel errorPanel = new PluginErrorPanel();
    private final ItemManager itemManager;
    private final BingoPlugin plugin;
    private final BingoConfig config;
    private final BingoProfileManager profileManager;

    private final JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
    private final JButton resetButton = new JButton("Reset Board");
    private final JButton remoteUpdateButton = new JButton("Update from URL");
    private final JButton refreshTeamButton = new JButton("Refresh Team Items");
    private final JButton syncFirebaseButton = new JButton("Sync from Firebase");
    private final JLabel sourceWarningLabel = new JLabel();
    private final JLabel itemLimitWarningLabel = new JLabel();
    private final JLabel teamStorageWarningLabel = new JLabel();

    // Profile management components
    private final JPanel profilePanel = new JPanel(new GridBagLayout());
    private final JComboBox<String> profileComboBox = new JComboBox<>();
    private final JButton newProfileButton = new JButton("New");
    private final JButton deleteProfileButton = new JButton("Delete");
    private final JButton profileSettingsButton = new JButton("Settings");

    private JButton convertToSoloButton;

    private static final Logger log = LoggerFactory.getLogger(BingoPanel.class);

    @Inject
    public BingoPanel(ItemManager itemManager, BingoPlugin plugin, BingoConfig config, BingoProfileManager profileManager, BingoTeamService teamService) {
        super(false);
        this.itemManager = itemManager;
        this.plugin = plugin;
        this.config = config;
        this.profileManager = profileManager;

        // Use a slightly smaller font for warnings to save vertical space
        Font smallFont = sourceWarningLabel.getFont().deriveFont(sourceWarningLabel.getFont().getSize() - 2f);

        setLayout(new BorderLayout(0, 0));
        setBorder(new EmptyBorder(5, 0, 5, 0)); // Remove horizontal padding

        // Initialize the error panel
        errorPanel.setContent("Bingo", "No items found. Add some items in the settings.");
        errorPanel.setVisible(false);

        // Initialize the items container with tighter spacing
        itemsContainer.setLayout(new GridLayout(GRID_SIZE, GRID_SIZE, 1, 1));
        itemsContainer.setBackground(Color.GRAY);
        itemsContainer.setBorder(BorderFactory.createEmptyBorder(1, 1, 1, 1));
        itemsContainer.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 5, PluginPanel.PANEL_WIDTH - 5));

        // Configure scroll pane to fill width
        JScrollPane scrollPane = new JScrollPane(itemsContainer);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
        scrollPane.setBorder(null);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);

        // Initialize warning labels
        sourceWarningLabel.setForeground(Color.YELLOW);
        sourceWarningLabel.setHorizontalAlignment(SwingConstants.CENTER);
        sourceWarningLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        sourceWarningLabel.setFont(smallFont);
        sourceWarningLabel.setVisible(false);

        itemLimitWarningLabel.setForeground(Color.YELLOW);
        itemLimitWarningLabel.setHorizontalAlignment(SwingConstants.CENTER);
        itemLimitWarningLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        itemLimitWarningLabel.setFont(smallFont);
        itemLimitWarningLabel.setVisible(false);

        // Add team storage warning label
        teamStorageWarningLabel.setForeground(Color.RED);
        teamStorageWarningLabel.setFont(smallFont);
        teamStorageWarningLabel.setVisible(false);
        teamStorageWarningLabel.setText("<html><b>WARNING:</b> Firebase permission denied. Team features are using local storage.<br>Items obtained by other team members will NOT be synchronized.</html>");
        teamStorageWarningLabel.setHorizontalAlignment(SwingConstants.CENTER);

        // Initialize profile panel
        initProfilePanel();

        // Initialize control panel
        resetButton.setToolTipText("Reset all bingo progress");
        resetButton.setFocusPainted(false);
        resetButton.addActionListener(e -> {
            plugin.resetBingoBoard(true);
        });

        remoteUpdateButton.setToolTipText("Update bingo items from Remote URL (Pastebin)");
        remoteUpdateButton.setFocusPainted(false);
        remoteUpdateButton.addActionListener(e -> {
            plugin.updateRemoteItemsManually();
        });

        refreshTeamButton.setToolTipText("Refresh team items from the remote URL");
        refreshTeamButton.setFocusPainted(false);
        refreshTeamButton.addActionListener(e -> {
            plugin.refreshTeamItems();
        });

        syncFirebaseButton.setToolTipText("Sync from Database");
        syncFirebaseButton.setFocusPainted(false);
        syncFirebaseButton.addActionListener(e -> plugin.refreshUIFromFirebase());

        controlPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        controlPanel.add(resetButton);
        controlPanel.add(syncFirebaseButton);
        controlPanel.add(refreshTeamButton);

        // Create a top panel for warnings and profile
        JPanel topPanel = new JPanel();
        topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));
        topPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

        // Add source warning at the top
        topPanel.add(sourceWarningLabel);
        topPanel.add(Box.createVerticalStrut(5));

        // Add profile panel
        topPanel.add(profilePanel);
        topPanel.add(Box.createVerticalStrut(5));

        // Add other warnings
        topPanel.add(itemLimitWarningLabel);
        topPanel.add(teamStorageWarningLabel);
        topPanel.add(errorPanel);

        // Add components to the main panel
        add(topPanel, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
        add(controlPanel, BorderLayout.SOUTH);

        // Update the UI
        updateButtonVisibility();
        updateSourceWarningLabel();
    }

    private void initProfilePanel() {
        profilePanel.setBorder(BorderFactory.createTitledBorder("Bingo Profiles"));

        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(5, 5, 5, 5);

        // Profile selector
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 3;
        c.weightx = 1.0;
        profilePanel.add(profileComboBox, c);

        // New profile button
        c.gridx = 0;
        c.gridy = 1;
        c.gridwidth = 1;
        c.weightx = 0.33;
        profilePanel.add(newProfileButton, c);

        // Delete profile button
        c.gridx = 1;
        c.gridy = 1;
        profilePanel.add(deleteProfileButton, c);

        // Settings button
        c.gridx = 2;
        c.gridy = 1;
        profilePanel.add(profileSettingsButton, c);

        // Add team storage warning label
        c.gridx = 0;
        c.gridy++;
        c.gridwidth = GridBagConstraints.REMAINDER;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(5, 5, 5, 5);
        profilePanel.add(teamStorageWarningLabel, c);

        final JButton convertToSoloButton = convertToSolo();
        c.gridy++;
        profilePanel.add(convertToSoloButton, c);

        // Store the button as a field so we can update its visibility
        this.convertToSoloButton = convertToSoloButton;

        // Add action listeners
        profileComboBox.addActionListener(e -> {
            // Ignore events that happen during programmatic updates
            if (!profileComboBox.isEnabled()) {
                return;
            }

            if (profileComboBox.getSelectedItem() != null) {
                final String selectedProfile = (String) profileComboBox.getSelectedItem();
                final String currentProfile = config.currentProfile();

                // Only switch if the profile has changed
                if (!selectedProfile.equals(currentProfile)) {
                    // Clear UI immediately for visual feedback
                    itemsContainer.removeAll();
                    JLabel loadingLabel = new JLabel("Loading profile " + selectedProfile + "...");
                    loadingLabel.setHorizontalAlignment(SwingConstants.CENTER);
                    loadingLabel.setForeground(Color.WHITE);
                    loadingLabel.setFont(loadingLabel.getFont().deriveFont(Font.BOLD, 14f));
                    itemsContainer.add(loadingLabel);
                    revalidate();
                    repaint();

                    // Disable UI controls during switch to prevent further actions
                    profileComboBox.setEnabled(false);
                    resetButton.setEnabled(false);
                    remoteUpdateButton.setEnabled(false);
                    refreshTeamButton.setEnabled(false);

                    // Start fresh thread for profile switch
                    new Thread(() -> {
                        try {
                            // Force config manager to use the new profile
                            // This is the critical part that writes to the config system
                            SwingUtilities.invokeAndWait(() -> {
                                try {
                                    // Force the config update with the most aggressive approach
                                    plugin.forceConfigUpdate(selectedProfile);

                                    // Double-check update was successful
                                    String actualProfile = config.currentProfile();
                                    if (!selectedProfile.equals(actualProfile)) {
                                        log.error("CRITICAL ERROR: Profile switch failed despite all attempts. Wanted: {}, Got: {}",
                                                selectedProfile, actualProfile);
                                    } else {
                                        log.info("Profile config successfully updated to: {}", selectedProfile);
                                    }
                                } catch (Exception ex) {
                                    log.error("Error during profile switch config update", ex);
                                }
                            });

                            // Small delay to ensure config is processed
                            Thread.sleep(250);

                            plugin.clearItems();
                            plugin.forceReloadItems();

                            // Update UI on EDT
                            SwingUtilities.invokeLater(() -> {
                                try {
                                    // Verify profile change took effect
                                    String actualProfile = config.currentProfile();
                                    log.info("Profile switch complete. Config now shows: {}", actualProfile);

                                    if (!selectedProfile.equals(actualProfile)) {
                                        log.error("Profile switch failed! Wanted: {}, Got: {}",
                                                selectedProfile, actualProfile);
                                    }

                                    // Force complete UI rebuild
                                    updateGrid();
                                    updateSourceWarningLabel();
                                    updateButtonVisibility();

                                    // Re-enable all controls
                                    profileComboBox.setEnabled(true);
                                    resetButton.setEnabled(true);
                                    remoteUpdateButton.setEnabled(true);
                                    refreshTeamButton.setEnabled(true);
                                } catch (Exception ex) {
                                    log.error("Error during profile switch UI update", ex);
                                }
                            });
                        } catch (Exception ex) {
                            log.error("Error during profile switch", ex);

                            // Re-enable UI on error
                            SwingUtilities.invokeLater(() -> {
                                profileComboBox.setEnabled(true);
                                resetButton.setEnabled(true);
                                remoteUpdateButton.setEnabled(true);
                                refreshTeamButton.setEnabled(true);
                            });
                        }
                    }, "Profile-Switch-Thread").start();
                }
            }
        });

        newProfileButton.addActionListener(e -> {
            showNewProfileDialog();
        });

        deleteProfileButton.addActionListener(e -> {
            String selectedProfile = (String) profileComboBox.getSelectedItem();
            if (selectedProfile == null) {
                return;
            }
            int result = JOptionPane.showConfirmDialog(this,
                    "Are you sure you want to delete the profile '" + selectedProfile + "'?",
                    "Delete Profile",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);

            if (result != JOptionPane.YES_OPTION) {
                return;
            }
            if (selectedProfile.equals(config.currentProfile())) {
                List<String> profiles = profileManager.getProfiles();
                if (profiles.size() <= 1) {
                    JOptionPane.showMessageDialog(this,
                            "You cannot delete the only profile. Create another profile first.",
                            "Delete Profile Failed",
                            JOptionPane.ERROR_MESSAGE);
                    return;
                }
                String newProfile = profiles.stream()
                        .filter(p -> !p.equals(selectedProfile))
                        .findFirst()
                        .orElse(null);

                if (newProfile == null) {
                    return;
                }
                profileManager.switchProfile(newProfile);
                updateProfileComboBox();
            }
            if (profileManager.deleteProfile(selectedProfile)) {
                updateProfileComboBox();
            } else {
                JOptionPane.showMessageDialog(this,
                        "Failed to delete the profile. It may no longer exist.",
                        "Delete Profile Failed",
                        JOptionPane.ERROR_MESSAGE);
            }
        });

        profileSettingsButton.addActionListener(e -> {
            showProfileSettingsDialog();
        });

        // Initial update
        updateProfileComboBox();
    }

    @NonNull
    private JButton convertToSolo() {
        JButton convertToSoloButton = new JButton("Convert to Solo Profile");
        convertToSoloButton.setVisible(false);
        convertToSoloButton.addActionListener(e -> {
            int confirm = JOptionPane.showConfirmDialog(
                    this,
                    "This will convert your team profile to a solo profile.\n" +
                            "You will lose all team functionality, but keep your items and settings.\n" +
                            "Do you want to continue?",
                    "Convert to Solo Profile",
                    JOptionPane.YES_NO_OPTION
            );

            if (confirm == JOptionPane.YES_OPTION) {
                String currentProfile = (String) profileComboBox.getSelectedItem();
                plugin.convertTeamToSoloProfile(currentProfile)
                        .thenAccept(success -> {
                            SwingUtilities.invokeLater(() -> {
                                if (success) {
                                    JOptionPane.showMessageDialog(
                                            this,
                                            "Profile successfully converted to solo mode.",
                                            "Success",
                                            JOptionPane.INFORMATION_MESSAGE
                                    );
                                    plugin.reloadItems();
                                    updateProfileComboBox();
                                } else {
                                    JOptionPane.showMessageDialog(
                                            this,
                                            "Failed to convert profile to solo mode.",
                                            "Error",
                                            JOptionPane.ERROR_MESSAGE
                                    );
                                }
                            });
                        });
            }
        });
        return convertToSoloButton;
    }

    /**
     * Updates the profile combo box with the current list of profiles
     */
    public void updateProfileComboBox() {
        // Save the current selection listener
        ActionListener[] listeners = profileComboBox.getActionListeners();
        for (ActionListener listener : listeners) {
            profileComboBox.removeActionListener(listener);
        }

        profileComboBox.removeAllItems();

        List<String> profiles = profileManager.getProfiles();
        for (String profile : profiles) {
            profileComboBox.addItem(profile);
        }
        String currentProfile = config.currentProfile();
        boolean profileFound = false;

        if (currentProfile != null && !currentProfile.isEmpty()) {
            for (int i = 0; i < profileComboBox.getItemCount(); i++) {
                if (profileComboBox.getItemAt(i).equals(currentProfile)) {
                    profileComboBox.setSelectedIndex(i);
                    profileFound = true;
                    break;
                }
            }

            if (!profileFound && profileComboBox.getItemCount() > 0) {
                profileComboBox.setSelectedIndex(0);
            }
        } else if (profileComboBox.getItemCount() > 0) {
            profileComboBox.setSelectedIndex(0);
        }

        for (ActionListener listener : listeners) {
            profileComboBox.addActionListener(listener);
        }

        updateButtonVisibility();
        updateSourceWarningLabel();

        revalidate();
        repaint();
    }

    /**
     * Updates the source warning label based on the current profile settings
     */
    public void updateSourceWarningLabel() {
        // Make sure we're using the actual current profile, not a cached one
        String currentProfile = config.currentProfile();
        BingoConfig.BingoMode bingoMode = profileManager.getProfileBingoMode(currentProfile);

        // Update the UI based on the bingo mode
        if (bingoMode == BingoConfig.BingoMode.TEAM) {
            // Team mode
            String teamCode = profileManager.getProfileTeamCode(currentProfile);
            String teamName = profileManager.getProfileTeamName(currentProfile);

            if (teamCode != null && !teamCode.isEmpty()) {
                sourceWarningLabel.setText("<html><div style='text-align: center; font-weight: bold;'>Team Bingo: " +
                        (teamName != null && !teamName.isEmpty() ? teamName : teamCode) + "</div></html>");
                sourceWarningLabel.setForeground(Color.GREEN.darker());

                boolean hasItems = plugin != null && !plugin.getItems().isEmpty();
                teamStorageWarningLabel.setVisible(!hasItems);
            } else {
                sourceWarningLabel.setText("<html><div style='text-align: center; font-weight: bold;'>Team Bingo (No Team Code)</div></html>");
                sourceWarningLabel.setForeground(Color.RED);
                teamStorageWarningLabel.setVisible(false);
            }

            itemLimitWarningLabel.setVisible(false);
        } else {
            // Solo mode
            BingoConfig.ItemSourceType itemSourceType = profileManager.getProfileItemSourceType();

            if (itemSourceType == BingoConfig.ItemSourceType.REMOTE) {
                String remoteUrl = profileManager.getProfileRemoteUrl();
                sourceWarningLabel.setText("<html><div style='text-align: center; font-weight: bold;'>Source: Remote URL" +
                        (remoteUrl != null && !remoteUrl.isEmpty() ? " (" + remoteUrl + ")" : "") + "</div></html>");
                sourceWarningLabel.setForeground(Color.BLUE);
            } else {
                sourceWarningLabel.setText("<html><div style='text-align: center; font-weight: bold;'>Source: Manual Input</div></html>");
                sourceWarningLabel.setForeground(Color.BLACK);
            }

            // Check if we have too many items
            int itemCount = plugin.getItems().size();
            if (itemCount > MAX_ITEMS) {
                itemLimitWarningLabel.setText("<html><div style='text-align: center;'>Warning: Only showing first " + MAX_ITEMS + " items.</div></html>");
                itemLimitWarningLabel.setVisible(true);
            } else {
                itemLimitWarningLabel.setVisible(false);
            }

            teamStorageWarningLabel.setVisible(false);
        }
    }

    /**
     * Updates the visibility of buttons based on the current configuration
     */
    public void updateButtonVisibility() {
        updateSourceWarningLabel();
        controlPanel.removeAll();
        BingoConfig.ItemSourceType itemSourceType = profileManager.getProfileItemSourceType();

        controlPanel.add(resetButton);

        // Show sync from Firebase button for team profiles
        BingoConfig.BingoMode bingoMode = profileManager.getProfileBingoMode();
        if (bingoMode == BingoConfig.BingoMode.TEAM) {
            controlPanel.add(syncFirebaseButton);
        }

        controlPanel.add(refreshTeamButton);
        controlPanel.revalidate();
        controlPanel.repaint();
        revalidate();
        repaint();

        // Always hide the remote update button
        remoteUpdateButton.setVisible(false);

        // Show/hide the refresh team button based on the bingo mode and item source type
        refreshTeamButton.setVisible(bingoMode == BingoConfig.BingoMode.TEAM &&
                itemSourceType == BingoConfig.ItemSourceType.REMOTE);

        // Show/hide the convert to solo button based on the bingo mode
        if (convertToSoloButton != null) {
            convertToSoloButton.setVisible(bingoMode == BingoConfig.BingoMode.TEAM);
        }
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(PluginPanel.PANEL_WIDTH, super.getPreferredSize().height);
    }

    /**
     * Updates the items displayed in the panel
     *
     * @param items The items to display
     */
    public void updateItems(List<BingoItem> items) {
        itemsContainer.removeAll();
        if (items == null || items.isEmpty()) {
            errorPanel.setContent("No items found", "Add some items in the settings.");
            errorPanel.setVisible(true);
            itemsContainer.setVisible(false);
            return;
        }
        errorPanel.setVisible(false);
        itemsContainer.setVisible(true);

        if (items.size() > MAX_ITEMS) {
            itemLimitWarningLabel.setText("Too many items (" + items.size() + "). Only showing the first " + MAX_ITEMS + ".");
            itemLimitWarningLabel.setVisible(true);
        } else {
            itemLimitWarningLabel.setVisible(false);
        }
        itemsContainer.setLayout(new GridLayout(GRID_SIZE, GRID_SIZE, GRID_GAP, GRID_GAP));

        List<BingoItem> itemsCopy = new ArrayList<>(items);
        int count = 0;
        for (BingoItem item : itemsCopy) {
            if (count < MAX_ITEMS) {
                itemsContainer.add(createItemTile(item, count));
            }
            count++;
        }
        itemsContainer.revalidate();
        itemsContainer.repaint();
        updateButtonVisibility();
        updateSourceWarningLabel();
    }

    private JPanel createItemTile(BingoItem item, int index) {
        JPanel panel = new JPanel(new BorderLayout(0, 0));
        panel.setBorder(BorderFactory.createLineBorder(ColorScheme.DARKER_GRAY_COLOR.darker(), 1));
        panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        panel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH / 5 - 1, PluginPanel.PANEL_WIDTH / 5));
        panel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH / 5 - 1, PluginPanel.PANEL_WIDTH / 5));
        String displayName = formatItemNameForDisplay(item.getName());
        JLabel nameLabel = new JLabel(displayName);
        nameLabel.setHorizontalAlignment(SwingConstants.CENTER);
        nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD, 9f));
        nameLabel.setForeground(Color.WHITE);

        // Create the image label with fixed size
        JLabel imageLabel = new JLabel();
        imageLabel.setHorizontalAlignment(SwingConstants.CENTER);

        // For group items, try to get the image of the first item in the group
        int itemId = item.getItemId();
        if (item.isGroup() && itemId <= 0) {
            // Try to get the item ID of the first item in the group
            String firstItemName = item.getName();
            if (item.getName().contains("/")) {
                firstItemName = item.getName().split("/")[0].trim();
            }

            // If there are alternative names, try those instead
            List<String> altNames = item.getAlternativeNames();
            if (altNames != null && !altNames.isEmpty()) {
                firstItemName = altNames.get(0);
            }

            itemId = lookupItemIdByName(firstItemName);
        }

        if (itemId > 0) {
            AsyncBufferedImage itemImage = itemManager.getImage(itemId);
            imageLabel.setIcon(new ImageIcon(itemImage));

            if (item.isGroup()) {
                imageLabel.addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseClicked(MouseEvent e) {
                        if (e.getButton() == MouseEvent.BUTTON3) {
                            showGroupPopup(item, e);
                        }
                    }
                });
            }
        } else {
            // No image found, just show the name
            imageLabel.setText("?");
            imageLabel.setFont(imageLabel.getFont().deriveFont(Font.BOLD, 16f));
            imageLabel.setForeground(Color.WHITE);

            // Add right-click handler for group items only
            if (item.isGroup()) {
                imageLabel.addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseClicked(MouseEvent e) {
                        if (e.getButton() == MouseEvent.BUTTON3) {
                            // Right click on group item shows popup with all items in the group
                            showGroupPopup(item, e);
                        }
                    }
                });
            }
        }

        // Check if the item is obtained
        if (item.isObtained()) {
            // Add a check mark to the top right corner
            JLabel checkLabel = new JLabel(CHECK_MARK);
            checkLabel.setVerticalAlignment(SwingConstants.TOP);
            checkLabel.setHorizontalAlignment(SwingConstants.RIGHT);
            panel.add(checkLabel, BorderLayout.NORTH);
        }

        // Add components to panel
        panel.add(imageLabel, BorderLayout.CENTER);
        panel.add(nameLabel, BorderLayout.SOUTH);

        return panel;
    }

    /**
     * Shows a dialog for editing profile-specific settings.
     */
    private void showProfileSettingsDialog() {
        JPanel settingsPanel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 5, 5, 5);
        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 0.3;
        c.fill = GridBagConstraints.HORIZONTAL;
        settingsPanel.add(new JLabel("Item Source:"), c);

        c.gridx = 1;
        c.weightx = 0.7;
        JComboBox<BingoConfig.ItemSourceType> itemSourceCombo = new JComboBox<>(BingoConfig.ItemSourceType.values());
        itemSourceCombo.setSelectedItem(profileManager.getProfileItemSourceType());
        settingsPanel.add(itemSourceCombo, c);

        c.gridx = 0;
        c.gridy++;
        c.gridwidth = 2;
        c.gridheight = 3;
        c.weightx = 1.0;
        c.weighty = 1.0;
        c.fill = GridBagConstraints.BOTH;
        settingsPanel.add(new JLabel("Bingo Items (one per line):"), c);

        c.gridy++;
        javax.swing.JTextArea itemListArea = new javax.swing.JTextArea(profileManager.getProfileItemList(), 10, 30);
        itemListArea.setLineWrap(true);
        JScrollPane itemListScroll = new JScrollPane(itemListArea);
        settingsPanel.add(itemListScroll, c);

        c.gridx = 0;
        c.gridy += 3;
        c.gridheight = 1;
        c.weightx = 0.3;
        settingsPanel.add(new JLabel("Remote URL:"), c);

        c.gridx = 1;
        c.weightx = 0.7;
        javax.swing.JTextField remoteUrlField = new javax.swing.JTextField(profileManager.getProfileRemoteUrl());
        settingsPanel.add(remoteUrlField, c);

        // Add listener to enable/disable fields based on item source type
        itemSourceCombo.addActionListener(e -> {
            BingoConfig.ItemSourceType selectedType = (BingoConfig.ItemSourceType) itemSourceCombo.getSelectedItem();
            if (selectedType == BingoConfig.ItemSourceType.MANUAL) {
                remoteUrlField.setEnabled(false);
                itemListArea.setEnabled(true);
            } else {
                remoteUrlField.setEnabled(true);
                itemListArea.setEnabled(false);
            }
        });

        // Set initial state based on selected item source type
        BingoConfig.ItemSourceType initialType = (BingoConfig.ItemSourceType) itemSourceCombo.getSelectedItem();
        if (initialType == BingoConfig.ItemSourceType.MANUAL) {
            remoteUrlField.setEnabled(false);
            itemListArea.setEnabled(true);
        } else {
            remoteUrlField.setEnabled(true);
            itemListArea.setEnabled(false);
        }

        c.gridx = 0;
        c.gridy++;
        c.weightx = 0.3;
        settingsPanel.add(new JLabel("Refresh Interval (min):"), c);

        c.gridx = 1;
        c.weightx = 0.7;
        int refreshInterval = Math.max(1, profileManager.getProfileRefreshInterval());
        JSpinner refreshIntervalSpinner = new JSpinner(new SpinnerNumberModel(refreshInterval, 1, 60, 1));
        settingsPanel.add(refreshIntervalSpinner, c);

        c.gridx = 0;
        c.gridy++;
        c.weightx = 0.3;
        settingsPanel.add(new JLabel("Save Progress:"), c);

        c.gridx = 1;
        c.weightx = 0.7;
        JCheckBox persistObtainedCheckbox = new JCheckBox("Save Progress",
                profileManager.getProfilePersistObtained());
        settingsPanel.add(persistObtainedCheckbox, c);

        int result = JOptionPane.showConfirmDialog(this,
                settingsPanel,
                "Profile Settings: " + config.currentProfile(),
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);

        if (result == JOptionPane.OK_OPTION) {
            // Get the new item source type
            BingoConfig.ItemSourceType newSourceType = (BingoConfig.ItemSourceType) itemSourceCombo.getSelectedItem();

            // Save all other settings first
            profileManager.setProfileItemList(itemListArea.getText());
            profileManager.setProfileRemoteUrl(remoteUrlField.getText());
            profileManager.setProfileRefreshInterval((Integer) refreshIntervalSpinner.getValue());
            profileManager.setProfilePersistObtained(persistObtainedCheckbox.isSelected());

            // Handle item source type changes last (will trigger reloadItems)
            handleItemSourceChange(newSourceType);
        }
    }

    /**
     * Shows a dialog to create a new profile
     */
    private void showNewProfileDialog() {
        // Create the dialog panel with more space
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(5, 5, 5, 5);

        // Profile name field
        JTextField profileNameField = new JTextField(20);
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 1;
        panel.add(new JLabel("Profile Name:"), c);
        c.gridx = 1;
        panel.add(profileNameField, c);

        // Profile type selection
        JPanel typePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        ButtonGroup typeGroup = new ButtonGroup();
        JRadioButton soloRadio = new JRadioButton("Solo", true);
        JRadioButton createTeamRadio = new JRadioButton("Create Team", false);
        JRadioButton joinTeamRadio = new JRadioButton("Join Team", false);
        typeGroup.add(soloRadio);
        typeGroup.add(createTeamRadio);
        typeGroup.add(joinTeamRadio);
        typePanel.add(soloRadio);
        typePanel.add(createTeamRadio);
        typePanel.add(joinTeamRadio);

        c.gridx = 0;
        c.gridy = 1;
        c.gridwidth = 2;
        panel.add(typePanel, c);

        // Create team panel
        JPanel createTeamPanel = new JPanel(new GridBagLayout());
        GridBagConstraints tc = new GridBagConstraints();
        tc.fill = GridBagConstraints.HORIZONTAL;
        tc.insets = new Insets(2, 2, 2, 2);

        // Team name field
        JTextField teamNameField = new JTextField(20);
        tc.gridx = 0;
        tc.gridy = 0;
        tc.gridwidth = 1;
        createTeamPanel.add(new JLabel("Team Name:"), tc);
        tc.gridx = 1;
        createTeamPanel.add(teamNameField, tc);

        // Discord webhook field
        JTextField webhookField = new JTextField(config.discordWebhook(), 20);
        tc.gridx = 0;
        tc.gridy = 1;
        createTeamPanel.add(new JLabel("Discord Webhook:"), tc);
        tc.gridx = 1;
        createTeamPanel.add(webhookField, tc);

        // Item source type combo box
        JComboBox<BingoConfig.ItemSourceType> itemSourceTypeComboBox = new JComboBox<>(BingoConfig.ItemSourceType.values());
        itemSourceTypeComboBox.setSelectedItem(profileManager.getProfileItemSourceType());
        tc.gridx = 0;
        tc.gridy = 2;
        createTeamPanel.add(new JLabel("Item Source:"), tc);
        tc.gridx = 1;
        createTeamPanel.add(itemSourceTypeComboBox, tc);

        // Remote URL field
        JTextField remoteUrlField = new JTextField(profileManager.getProfileRemoteUrl(), 20);
        tc.gridx = 0;
        tc.gridy = 3;
        createTeamPanel.add(new JLabel("Remote URL:"), tc);
        tc.gridx = 1;
        createTeamPanel.add(remoteUrlField, tc);

        // Manual items field
        JTextArea manualItemsArea = new JTextArea(profileManager.getProfileItemList(), 5, 20);
        JScrollPane manualItemsScrollPane = new JScrollPane(manualItemsArea);
        tc.gridx = 0;
        tc.gridy = 4;
        tc.gridwidth = 2;
        createTeamPanel.add(new JLabel("Manual Items (one per line):"), tc);
        tc.gridy = 5;
        createTeamPanel.add(manualItemsScrollPane, tc);

        // Add listener to enable/disable fields based on item source type
        itemSourceTypeComboBox.addActionListener(e -> {
            BingoConfig.ItemSourceType selectedType = (BingoConfig.ItemSourceType) itemSourceTypeComboBox.getSelectedItem();
            if (selectedType == BingoConfig.ItemSourceType.MANUAL) {
                remoteUrlField.setEnabled(false);
                manualItemsArea.setEnabled(true);
            } else {
                remoteUrlField.setEnabled(true);
                manualItemsArea.setEnabled(false);
            }
        });

        // Set initial state based on selected item source type
        BingoConfig.ItemSourceType initialType = (BingoConfig.ItemSourceType) itemSourceTypeComboBox.getSelectedItem();
        if (initialType == BingoConfig.ItemSourceType.MANUAL) {
            remoteUrlField.setEnabled(false);
            manualItemsArea.setEnabled(true);
        } else {
            remoteUrlField.setEnabled(true);
            manualItemsArea.setEnabled(false);
        }

        // Refresh interval field
        int refreshInterval = Math.max(1, profileManager.getProfileRefreshInterval());
        JSpinner refreshIntervalSpinner = new JSpinner(new SpinnerNumberModel(refreshInterval, 1, 60, 1));
        tc.gridx = 0;
        tc.gridy = 6;
        c.gridwidth = 1;
        createTeamPanel.add(new JLabel("Refresh Interval (minutes):"), tc);
        tc.gridx = 1;
        createTeamPanel.add(refreshIntervalSpinner, tc);

        // Persist obtained checkbox
        JCheckBox persistObtainedCheckBox = new JCheckBox("Save Progress", profileManager.getProfilePersistObtained());
        tc.gridx = 0;
        tc.gridy = 7;
        c.gridwidth = 2;
        createTeamPanel.add(persistObtainedCheckBox, tc);

        // Join team panel
        JPanel joinTeamPanel = new JPanel(new GridBagLayout());
        GridBagConstraints jc = new GridBagConstraints();
        jc.fill = GridBagConstraints.HORIZONTAL;
        jc.insets = new Insets(2, 2, 2, 2);

        // Team code field
        JTextField teamCodeField = new JTextField(20);
        jc.gridx = 0;
        jc.gridy = 0;
        joinTeamPanel.add(new JLabel("Team Code:"), jc);
        jc.gridx = 1;
        joinTeamPanel.add(teamCodeField, jc);

        // Create a solo panel with the same options as team panel
        JPanel soloPanel = new JPanel(new GridBagLayout());
        GridBagConstraints sc = new GridBagConstraints();
        sc.fill = GridBagConstraints.HORIZONTAL;
        sc.insets = new Insets(2, 2, 2, 2);

        // Discord webhook field for solo
        JTextField soloWebhookField = new JTextField(config.discordWebhook(), 20);
        sc.gridx = 0;
        sc.gridy = 0;
        soloPanel.add(new JLabel("Discord Webhook:"), sc);
        sc.gridx = 1;
        soloPanel.add(soloWebhookField, sc);

        // Item source type combo box for solo
        JComboBox<BingoConfig.ItemSourceType> soloItemSourceTypeComboBox = new JComboBox<>(BingoConfig.ItemSourceType.values());
        soloItemSourceTypeComboBox.setSelectedItem(profileManager.getProfileItemSourceType());
        sc.gridx = 0;
        sc.gridy = 1;
        soloPanel.add(new JLabel("Item Source:"), sc);
        sc.gridx = 1;
        soloPanel.add(soloItemSourceTypeComboBox, sc);

        // Remote URL field for solo
        JTextField soloRemoteUrlField = new JTextField(profileManager.getProfileRemoteUrl(), 20);
        sc.gridx = 0;
        sc.gridy = 2;
        soloPanel.add(new JLabel("Remote URL:"), sc);
        sc.gridx = 1;
        soloPanel.add(soloRemoteUrlField, sc);

        // Manual items field for solo
        JTextArea soloManualItemsArea = new JTextArea(profileManager.getProfileItemList(), 5, 20);
        JScrollPane soloManualItemsScrollPane = new JScrollPane(soloManualItemsArea);
        sc.gridx = 0;
        sc.gridy = 3;
        sc.gridwidth = 2;
        soloPanel.add(new JLabel("Manual Items (one per line):"), sc);
        sc.gridy = 4;
        soloPanel.add(soloManualItemsScrollPane, sc);

        // Add listener to enable/disable fields based on item source type for solo
        soloItemSourceTypeComboBox.addActionListener(e -> {
            BingoConfig.ItemSourceType selectedType = (BingoConfig.ItemSourceType) soloItemSourceTypeComboBox.getSelectedItem();
            if (selectedType == BingoConfig.ItemSourceType.MANUAL) {
                soloRemoteUrlField.setEnabled(false);
                soloManualItemsArea.setEnabled(true);
            } else {
                soloRemoteUrlField.setEnabled(true);
                soloManualItemsArea.setEnabled(false);
            }
        });

        // Set initial state based on selected item source type for solo
        BingoConfig.ItemSourceType soloInitialType = (BingoConfig.ItemSourceType) soloItemSourceTypeComboBox.getSelectedItem();
        if (soloInitialType == BingoConfig.ItemSourceType.MANUAL) {
            soloRemoteUrlField.setEnabled(false);
            soloManualItemsArea.setEnabled(true);
        } else {
            soloRemoteUrlField.setEnabled(true);
            soloManualItemsArea.setEnabled(false);
        }

        // Refresh interval field for solo
        int refreshIntervalSolo = Math.max(1, profileManager.getProfileRefreshInterval());
        JSpinner soloRefreshIntervalSpinner = new JSpinner(new SpinnerNumberModel(refreshIntervalSolo, 1, 60, 1));
        sc.gridx = 0;
        sc.gridy = 5;
        sc.gridwidth = 1;
        soloPanel.add(new JLabel("Refresh Interval (minutes):"), sc);
        sc.gridx = 1;
        soloPanel.add(soloRefreshIntervalSpinner, sc);

        // Persist obtained checkbox for solo
        JCheckBox soloPersistObtainedCheckBox = new JCheckBox("Save Progress", profileManager.getProfilePersistObtained());
        sc.gridx = 0;
        sc.gridy = 6;
        sc.gridwidth = 2;
        soloPanel.add(soloPersistObtainedCheckBox, sc);

        // Add panels to main panel (initially hidden)
        createTeamPanel.setVisible(false);
        joinTeamPanel.setVisible(false);
        soloPanel.setVisible(true); // Solo panel is visible by default as solo is the default option

        c.gridx = 0;
        c.gridy = 2;
        c.gridwidth = 2;
        panel.add(soloPanel, c);

        c.gridy = 3;
        panel.add(createTeamPanel, c);

        c.gridy = 4;
        panel.add(joinTeamPanel, c);

        // Create a custom dialog with OK and Cancel buttons
        JDialog dialog = new JDialog((Frame) SwingUtilities.getWindowAncestor(this), "Create New Profile", true);
        dialog.setLayout(new BorderLayout());
        dialog.add(panel, BorderLayout.CENTER);

        // Button panel
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton okButton = new JButton("OK");
        JButton cancelButton = new JButton("Cancel");
        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);
        dialog.add(buttonPanel, BorderLayout.SOUTH);

        // Add listeners to show/hide panels based on selection
        soloRadio.addActionListener(e -> {
            soloPanel.setVisible(true);
            createTeamPanel.setVisible(false);
            joinTeamPanel.setVisible(false);
            dialog.pack();
            dialog.revalidate();
            dialog.repaint();
        });

        createTeamRadio.addActionListener(e -> {
            soloPanel.setVisible(false);
            createTeamPanel.setVisible(true);
            joinTeamPanel.setVisible(false);
            dialog.pack();
            dialog.revalidate();
            dialog.repaint();
        });

        joinTeamRadio.addActionListener(e -> {
            soloPanel.setVisible(false);
            createTeamPanel.setVisible(false);
            joinTeamPanel.setVisible(true);
            dialog.pack();
            dialog.revalidate();
            dialog.repaint();
        });

        // Set button actions
        final boolean[] result = {false};

        okButton.addActionListener(e -> {
            result[0] = true;
            dialog.dispose();
        });

        cancelButton.addActionListener(e -> {
            dialog.dispose();
        });

        // Set dialog properties
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setResizable(true);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setVisible(true);

        // Process the result after dialog closes
        if (result[0]) {
            // Get the profile name
            String profileName = profileNameField.getText().trim();

            // Validate the profile name
            if (profileName.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Profile name cannot be empty", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            // Handle based on selected type
            if (soloRadio.isSelected()) {
                // Create a solo profile with settings
                String webhook = soloWebhookField.getText().trim();
                BingoConfig.ItemSourceType itemSourceType = (BingoConfig.ItemSourceType) soloItemSourceTypeComboBox.getSelectedItem();
                String remoteUrl = soloRemoteUrlField.getText().trim();
                String manualItems = soloManualItemsArea.getText().trim();
                boolean persistObtained = soloPersistObtainedCheckBox.isSelected();
                // Get the refresh interval directly from the spinner without re-declaring the variable

                if (profileManager.createProfile(profileName)) {
                    // Add a small delay to ensure the profile creation completes
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }

                    // Switch to the selected profile
                    profileManager.switchProfile(profileName);

                    // Add a small delay to ensure the profile switch completes
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }

                    // Then set the additional settings
                    profileManager.setProfileDiscordWebhook(webhook);
                    profileManager.setProfileItemSourceType(itemSourceType);
                    profileManager.setProfileRemoteUrl(remoteUrl);
                    profileManager.setProfileItemList(manualItems);
                    profileManager.setProfileRefreshInterval((Integer) soloRefreshIntervalSpinner.getValue());
                    profileManager.setProfilePersistObtained(persistObtained);

                    // Force UI update to show the new profile in dropdown
                    updateProfileComboBox();

                    // Force reload of items to reflect the new profile
                    plugin.forceReloadItems();
                } else {
                    JOptionPane.showMessageDialog(this, "Failed to create profile: Profile already exists", "Error", JOptionPane.ERROR_MESSAGE);
                }
            } else if (createTeamRadio.isSelected() || joinTeamRadio.isSelected()) {
                // Check maximum number of team profiles
                long teamProfileCount = profileManager.getProfiles().stream()
                        .map(profileManager::getProfileBingoMode)
                        .filter(mode -> mode == BingoConfig.BingoMode.TEAM)
                        .count();

                if (teamProfileCount >= 2) {
                    JOptionPane.showMessageDialog(this,
                            "You can only have a maximum of two team profiles (created or joined).\n" +
                                    "Please delete an existing team profile before creating/joining another.",
                            "Team Profile Limit Reached",
                            JOptionPane.ERROR_MESSAGE);
                    return; // Prevent creation/joining
                }

                // Proceed with Create Team or Join Team logic
                if (createTeamRadio.isSelected()) {
                    // Create a team profile
                    String teamName = teamNameField.getText().trim();
                    String webhook = webhookField.getText().trim();
                    BingoConfig.ItemSourceType itemSourceType = (BingoConfig.ItemSourceType) itemSourceTypeComboBox.getSelectedItem();
                    String remoteUrl = remoteUrlField.getText().trim();
                    String manualItems = manualItemsArea.getText().trim();
                    boolean persistObtained = persistObtainedCheckBox.isSelected();

                    // Validate team name
                    if (teamName.isEmpty()) {
                        teamName = "Team " + profileName; // Auto-generate team name if empty
                    }

                    // Create the team profile and handle async results properly
                    profileManager.createTeamProfile(profileName, teamName, webhook, itemSourceType, remoteUrl, manualItems, (int) refreshIntervalSpinner.getValue(), persistObtained)
                            .thenAccept(teamCode -> {
                                SwingUtilities.invokeLater(() -> {
                                    updateProfileComboBox();
                                    JOptionPane.showMessageDialog(this,
                                            "Team created successfully!\nTeam Code: " + teamCode + "\n\nShare this code with your teammates.",
                                            "Team Created", JOptionPane.INFORMATION_MESSAGE);
                                });
                            })
                            .exceptionally(ex -> {
                                SwingUtilities.invokeLater(() -> {
                                    updateProfileComboBox();
                                    String errorMsg = ex.getCause().getMessage();
                                    if (errorMsg.startsWith("Failed to create profile:")) {
                                        JOptionPane.showMessageDialog(this,
                                                "The profile '" + profileName + "' already exists but may not be visible in the dropdown.\n" +
                                                        "The UI will now be refreshed to show all profiles.",
                                                "Profile Already Exists",
                                                JOptionPane.WARNING_MESSAGE);
                                    } else {
                                        JOptionPane.showMessageDialog(this, "Failed to create team profile: " + errorMsg, "Error", JOptionPane.ERROR_MESSAGE);
                                    }
                                });
                                return null;
                            });
                } else { // Must be joinTeamRadio
                    // Join a team
                    String teamCode = teamCodeField.getText().trim();

                    // Validate the team code
                    if (teamCode.isEmpty()) {
                        JOptionPane.showMessageDialog(this, "Team code cannot be empty", "Error", JOptionPane.ERROR_MESSAGE);
                        return;
                    }

                    // Join the team and handle async results properly
                    profileManager.joinTeamProfile(profileName, teamCode)
                            .thenAccept(success -> {
                                if (success) {
                                    // Both join and profile switch succeeded within joinTeamProfile
                                    SwingUtilities.invokeLater(() -> {
                                        updateProfileComboBox();
                                        // Show success message
                                        JOptionPane.showMessageDialog(this, "Joined team successfully!", "Success", JOptionPane.INFORMATION_MESSAGE);
                                    });
                                } else {
                                    // joinTeamProfile handles profile deletion on join failure
                                    SwingUtilities.invokeLater(() -> {
                                        updateProfileComboBox(); // Update UI to remove the failed profile attempt
                                        JOptionPane.showMessageDialog(this, "Failed to join team: Team not found or other error.", "Error", JOptionPane.ERROR_MESSAGE);
                                    });
                                }
                            })
                            .exceptionally(ex -> {
                                // Handle errors from joinTeamProfile chain
                                SwingUtilities.invokeLater(() -> {
                                    updateProfileComboBox();
                                    JOptionPane.showMessageDialog(this, "Failed to join team profile: " + ex.getCause().getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                                });
                                return null;
                            });
                }
            }
        }
    }

    /**
     * Creates a popup window showing all items in a group with their images
     *
     * @param groupItem The group item
     * @return A JWindow containing all items in the group
     */
    private JWindow createGroupPopup(BingoItem groupItem) {
        JWindow popup = new JWindow();
        JPanel contentPanel = new JPanel();
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        contentPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ColorScheme.DARK_GRAY_COLOR, 1),
                BorderFactory.createEmptyBorder(5, 5, 5, 5)
        ));
        contentPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

        // Add a title
        JLabel titleLabel = new JLabel("Group Items:");
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD));
        titleLabel.setForeground(Color.WHITE);
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        contentPanel.add(titleLabel);
        contentPanel.add(Box.createVerticalStrut(5));

        // Add all items in the group (including the main item)
        // Create a panel for the group name (which combines all items)
        JPanel groupNamePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        groupNamePanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        groupNamePanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Add the group name
        JLabel groupNameLabel = new JLabel(groupItem.getName());
        groupNameLabel.setForeground(ColorScheme.BRAND_ORANGE);
        groupNameLabel.setFont(groupNameLabel.getFont().deriveFont(Font.BOLD));
        groupNamePanel.add(groupNameLabel);

        contentPanel.add(groupNamePanel);
        contentPanel.add(Box.createVerticalStrut(5));

        // Add the main item if it's different from the group name and not a composite name
        String mainItemName = groupItem.getName();
        if (!mainItemName.contains("/")) {
            // Use the item ID from the group item if available
            int itemId = groupItem.getItemId();
            if (itemId <= 0) {
                itemId = lookupItemIdByName(mainItemName);
            }
            addItemToPanel(contentPanel, mainItemName, itemId);
        }

        // Add all alternative items
        if (groupItem.getAlternativeNames() != null) {
            for (String altName : groupItem.getAlternativeNames()) {
                addItemToPanel(contentPanel, altName, lookupItemIdByName(altName));
            }
        }

        popup.setContentPane(contentPanel);
        popup.pack();

        // Ensure the popup isn't too wide
        Dimension size = popup.getSize();
        if (size.width > 250) {
            popup.setSize(new Dimension(250, size.height));
        }

        return popup;
    }

    /**
     * Adds an item with its image to a panel
     *
     * @param panel    The panel to add the item to
     * @param itemName The name of the item
     * @param itemId   The ID of the item
     */
    private void addItemToPanel(JPanel panel, String itemName, int itemId) {
        JPanel itemPanel = new JPanel(new BorderLayout());
        itemPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        itemPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        itemPanel.setMaximumSize(new Dimension(250, 40));

        // Add the item image on the left
        JPanel imagePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        imagePanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

        if (itemId > 0) {
            AsyncBufferedImage itemImage = itemManager.getImage(itemId);
            JLabel imageLabel = new JLabel(new ImageIcon(itemImage));
            imagePanel.add(imageLabel);
        } else {
            // Use a placeholder for items without an ID
            JLabel placeholderLabel = new JLabel("?");
            placeholderLabel.setPreferredSize(new Dimension(32, 32));
            placeholderLabel.setHorizontalAlignment(SwingConstants.CENTER);
            placeholderLabel.setFont(placeholderLabel.getFont().deriveFont(Font.BOLD, 16f));
            placeholderLabel.setForeground(Color.WHITE);
            placeholderLabel.setBorder(BorderFactory.createLineBorder(ColorScheme.DARK_GRAY_COLOR));
            imagePanel.add(placeholderLabel);
        }

        itemPanel.add(imagePanel, BorderLayout.WEST);

        // Add the item name on the right with word wrapping if needed
        JLabel nameLabel = new JLabel();
        nameLabel.setForeground(Color.WHITE);

        // Handle long names
        if (itemName.length() > 20) {
            nameLabel.setText("<html><body style='width: 150px'>" + itemName + "</body></html>");
        } else {
            nameLabel.setText(itemName);
        }

        itemPanel.add(nameLabel, BorderLayout.CENTER);

        panel.add(itemPanel);

        // Add a small vertical gap after each item
        panel.add(Box.createVerticalStrut(2));
    }

    /**
     * Looks up an item ID by name using the item manager
     *
     * @param itemName The name of the item
     * @return The ID of the item, or -1 if not found
     */
    private int lookupItemIdByName(String itemName) {
        List<ItemPrice> results = itemManager.search(itemName);
        if (!results.isEmpty()) {
            return results.get(0).getId();
        }
        return -1;
    }

    /**
     * Formats an item name for display in the tile.
     * Handles long names by truncating or formatting them.
     *
     * @param name The original item name
     * @return The formatted name for display
     */
    private String formatItemNameForDisplay(String name) {
        // For group items with slashes, just show the first item
        if (name.contains("/")) {
            return name.split("/")[0].trim();
        }

        // For long names, truncate or split into multiple lines
        if (name.length() > 12) {
            // If the name has spaces, try to split it into multiple lines
            if (name.contains(" ")) {
                String[] words = name.split(" ");
                StringBuilder result = new StringBuilder("<html>");
                StringBuilder currentLine = new StringBuilder();

                for (String word : words) {
                    if (currentLine.length() + word.length() > 10) {
                        // Start a new line
                        if (currentLine.length() > 0) {
                            result.append(currentLine).append("<br>");
                            currentLine = new StringBuilder();
                        }
                    }

                    if (currentLine.length() > 0) {
                        currentLine.append(" ");
                    }
                    currentLine.append(word);
                }

                // Add the last line
                result.append(currentLine).append("</html>");
                return result.toString();
            } else {
                // No spaces, just truncate
                return name.substring(0, 10) + "...";
            }
        }

        return name;
    }

    /**
     * Updates the grid display
     */
    public void updateGrid() {
        updateItems(plugin.getItems());
    }

    /**
     * Shows a popup with all items in a group when right-clicked
     *
     * @param item The group item
     * @param e    The mouse event
     */
    private void showGroupPopup(BingoItem item, MouseEvent e) {
        if (item.isGroup()) {
            JWindow popup = createGroupPopup(item);
            popup.setLocation(e.getXOnScreen(), e.getYOnScreen());
            popup.setVisible(true);

            // Auto-hide the popup after 5 seconds
            Timer timer = new Timer(5000, evt -> popup.dispose());
            timer.setRepeats(false);
            timer.start();

            // Add a mouse listener to dispose the popup when clicked anywhere
            Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
                if (event instanceof MouseEvent) {
                    MouseEvent me = (MouseEvent) event;
                    if (me.getID() == MouseEvent.MOUSE_PRESSED && !SwingUtilities.isDescendingFrom(me.getComponent(), popup)) {
                        popup.dispose();
                        Toolkit.getDefaultToolkit().removeAWTEventListener(Toolkit.getDefaultToolkit().getAWTEventListeners()[0]);
                    }
                }
            }, AWTEvent.MOUSE_EVENT_MASK);
        }
    }

    /**
     * Handles item source type changes and updates UI accordingly
     */
    private void handleItemSourceChange(BingoConfig.ItemSourceType newSourceType) {

        // Force clear items when switching to Remote URL to prevent manual items from showing
        if (newSourceType == BingoConfig.ItemSourceType.REMOTE) {
            plugin.clearItems();
            plugin.updateUI(); // Update UI immediately to clear the grid
        }

        // Save to profile
        profileManager.setProfileItemSourceType(newSourceType);

        // Force a reload of items
        plugin.reloadItems();

        // Update source warning label
        updateSourceWarningLabel();
        updateButtonVisibility();
    }
} 