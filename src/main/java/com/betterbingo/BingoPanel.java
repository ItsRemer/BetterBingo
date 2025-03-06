package com.betterbingo;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.List;
import javax.inject.Inject;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.PluginErrorPanel;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.AsyncBufferedImage;
import java.awt.FlowLayout;
import javax.swing.JButton;
import javax.swing.Box;
import javax.swing.JComboBox;
import javax.swing.JOptionPane;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import javax.swing.JCheckBox;

public class BingoPanel extends PluginPanel {
    private static final ImageIcon CHECK_MARK = new ImageIcon(ImageUtil.loadImageResource(BingoPlugin.class, "/check.png"));
    private static final Dimension ITEM_SIZE = new Dimension(32, 32);
    private static final int GRID_SIZE = 5; // 5x5 bingo board
    private static final int GRID_GAP = 2; // Gap between cells
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
    private final JLabel sourceWarningLabel = new JLabel();
    private final JLabel itemLimitWarningLabel = new JLabel();

    // Profile management components
    private final JPanel profilePanel = new JPanel(new GridBagLayout());
    private final JComboBox<String> profileComboBox = new JComboBox<>();
    private final JButton newProfileButton = new JButton("New");
    private final JButton deleteProfileButton = new JButton("Delete");
    private final JButton profileSettingsButton = new JButton("Settings");

    @Inject
    public BingoPanel(ItemManager itemManager, BingoPlugin plugin, BingoConfig config, BingoProfileManager profileManager) {
        super(false);
        this.itemManager = itemManager;
        this.plugin = plugin;
        this.config = config;
        this.profileManager = profileManager;

        setLayout(new BorderLayout(0, 5));
        setBorder(new EmptyBorder(5, 5, 5, 5));

        // Configure grid layout with specific gaps
        itemsContainer.setLayout(new GridLayout(GRID_SIZE, GRID_SIZE, GRID_GAP, GRID_GAP));
        itemsContainer.setBackground(ColorScheme.DARK_GRAY_COLOR);

        // Configure scroll pane
        JScrollPane scrollPane = new JScrollPane(itemsContainer);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
        scrollPane.setBorder(null);

        // Configure reset button
        resetButton.setToolTipText("Reset all bingo progress");
        resetButton.setFocusPainted(false);
        resetButton.addActionListener(e -> plugin.resetBingoBoard());

        // Configure remote update button
        remoteUpdateButton.setToolTipText("Update bingo items from Remote URL (Pastebin)");
        remoteUpdateButton.setFocusPainted(false);
        remoteUpdateButton.addActionListener(e -> plugin.updateRemoteItemsManually());

        // Configure source warning label
        sourceWarningLabel.setForeground(Color.YELLOW);
        sourceWarningLabel.setHorizontalAlignment(SwingConstants.CENTER);
        updateSourceWarningLabel();

        // Configure item limit warning label
        itemLimitWarningLabel.setForeground(Color.ORANGE);
        itemLimitWarningLabel.setHorizontalAlignment(SwingConstants.CENTER);
        itemLimitWarningLabel.setVisible(false);

        // Create a top panel for the warning labels and error panel
        JPanel topPanel = new JPanel(new BorderLayout(0, 5));
        topPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

        JPanel warningsPanel = new JPanel(new BorderLayout(0, 5));
        warningsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        warningsPanel.add(sourceWarningLabel, BorderLayout.NORTH);
        warningsPanel.add(itemLimitWarningLabel, BorderLayout.CENTER);

        topPanel.add(warningsPanel, BorderLayout.NORTH);
        errorPanel.setContent("Bingo", "No items loaded.");
        topPanel.add(errorPanel, BorderLayout.CENTER);
        errorPanel.setVisible(false);
        controlPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        updateButtonVisibility();
        initProfilePanel();
        topPanel.add(profilePanel, BorderLayout.SOUTH);
        add(topPanel, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
        add(controlPanel, BorderLayout.SOUTH);
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

        // Add action listeners
        profileComboBox.addActionListener(e -> {
            String selectedProfile = (String) profileComboBox.getSelectedItem();
            if (selectedProfile != null && !selectedProfile.equals(config.currentProfile())) {
                profileManager.switchProfile(selectedProfile);
            }
        });

        newProfileButton.addActionListener(e -> {
            String profileName = JOptionPane.showInputDialog(this,
                    "Enter a name for the new bingo profile:",
                    "New Bingo Profile",
                    JOptionPane.PLAIN_MESSAGE);

            if (profileName != null && !profileName.trim().isEmpty()) {
                if (profileManager.createProfile(profileName)) {
                    updateProfileComboBox();
                    profileComboBox.setSelectedItem(profileName);

                    int configureResult = JOptionPane.showConfirmDialog(this,
                            "Do you want to configure settings for the new profile?",
                            "Configure Profile",
                            JOptionPane.YES_NO_OPTION,
                            JOptionPane.QUESTION_MESSAGE);

                    if (configureResult == JOptionPane.YES_OPTION) {
                        profileManager.switchProfile(profileName);
                        showProfileSettingsDialog();
                    }
                } else {
                    JOptionPane.showMessageDialog(this,
                            "A profile with that name already exists.",
                            "Profile Creation Failed",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
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

    /**
     * Updates the profile combo box with the current list of profiles.
     */
    public void updateProfileComboBox() {
        profileComboBox.removeAllItems();

        List<String> profiles = profileManager.getProfiles();
        for (String profile : profiles) {
            profileComboBox.addItem(profile);
        }

        profileComboBox.setSelectedItem(config.currentProfile());
    }

    /**
     * Updates the source warning label based on the current item source type
     */
    private void updateSourceWarningLabel() {
        BingoConfig.ItemSourceType itemSourceType = profileManager.getProfileItemSourceType();
        if (itemSourceType == BingoConfig.ItemSourceType.MANUAL) {
            sourceWarningLabel.setText("<html><center>Using MANUAL mode - items from the 'Bingo Items' field will be used.<br>Remote URL will be ignored.</center></html>");
        } else {
            sourceWarningLabel.setText("<html><center>Using REMOTE URL mode - items from the URL will be used.<br>Manual 'Bingo Items' will be ignored.</center></html>");
        }
    }

    /**
     * Updates the visibility of buttons based on the current configuration
     */
    private void updateButtonVisibility() {
        updateSourceWarningLabel();
        controlPanel.removeAll();
        BingoConfig.ItemSourceType itemSourceType = profileManager.getProfileItemSourceType();
        if (itemSourceType == BingoConfig.ItemSourceType.REMOTE) {
            controlPanel.add(remoteUpdateButton);
            controlPanel.add(Box.createHorizontalStrut(10)); // Add spacing
        }
        controlPanel.add(resetButton);
        controlPanel.revalidate();
        controlPanel.repaint();
        revalidate();
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(PluginPanel.PANEL_WIDTH, super.getPreferredSize().height);
    }

    public void updateItems(List<BingoItem> items) {
        updateButtonVisibility();
        itemsContainer.removeAll();
        if (items.isEmpty()) {
            errorPanel.setVisible(true);
            itemLimitWarningLabel.setVisible(false);
            return;
        }

        errorPanel.setVisible(false);

        if (items.size() > MAX_ITEMS) {
            itemLimitWarningLabel.setText("<html><center>Warning: " + items.size() + " items found. Only the first 25 will be displayed.</center></html>");
            itemLimitWarningLabel.setVisible(true);
        } else {
            itemLimitWarningLabel.setVisible(false);
        }

        int availableWidth = PluginPanel.PANEL_WIDTH - 10; // Account for border
        int cellSize = (availableWidth - (GRID_GAP * (GRID_SIZE - 1))) / GRID_SIZE;
        for (int i = 0; i < MAX_ITEMS; i++) {
            JPanel cellPanel = new JPanel();
            cellPanel.setLayout(new BorderLayout(0, 1));
            cellPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
            cellPanel.setBorder(BorderFactory.createLineBorder(ColorScheme.DARKER_GRAY_COLOR.darker(), 1));
            cellPanel.setPreferredSize(new Dimension(cellSize, cellSize));

            if (i < items.size()) {
                BingoItem item = items.get(i);

                JPanel contentPanel = new JPanel(new BorderLayout(0, 2));
                contentPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

                if (item.getItemId() != -1) {
                    AsyncBufferedImage itemImage = itemManager.getImage(item.getItemId());
                    JLabel iconLabel = new JLabel();
                    iconLabel.setHorizontalAlignment(SwingConstants.CENTER);
                    iconLabel.setPreferredSize(ITEM_SIZE);
                    itemImage.addTo(iconLabel);
                    contentPanel.add(iconLabel, BorderLayout.CENTER);
                }

                JLabel itemLabel = new JLabel("<html><center><small>" + item.getName() + "</small></center></html>");
                itemLabel.setForeground(item.isObtained() ? ColorScheme.PROGRESS_COMPLETE_COLOR : Color.WHITE);
                itemLabel.setHorizontalAlignment(SwingConstants.CENTER);
                contentPanel.add(itemLabel, BorderLayout.SOUTH);

                cellPanel.add(contentPanel, BorderLayout.CENTER);

                if (item.isObtained()) {
                    JLabel checkLabel = new JLabel(CHECK_MARK);
                    checkLabel.setHorizontalAlignment(SwingConstants.CENTER);
                    cellPanel.add(checkLabel, BorderLayout.NORTH);
                }
            } else {
                JLabel emptyLabel = new JLabel("Empty");
                emptyLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
                emptyLabel.setHorizontalAlignment(SwingConstants.CENTER);
                cellPanel.add(emptyLabel, BorderLayout.CENTER);
            }

            itemsContainer.add(cellPanel);
        }

        itemsContainer.revalidate();
        itemsContainer.repaint();
    }

    /**
     * Shows a dialog for editing profile-specific settings.
     */
    private void showProfileSettingsDialog() {
        JPanel settingsPanel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(5, 5, 5, 5);
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 1;
        c.weightx = 0.3;

        settingsPanel.add(new JLabel("Item Source:"), c);

        c.gridx = 1;
        c.weightx = 0.7;
        JComboBox<BingoConfig.ItemSourceType> itemSourceCombo = new JComboBox<>(BingoConfig.ItemSourceType.values());
        itemSourceCombo.setSelectedItem(profileManager.getProfileItemSourceType());
        settingsPanel.add(itemSourceCombo, c);
        c.gridx = 0;
        c.gridy++;
        c.weightx = 0.3;
        settingsPanel.add(new JLabel("Bingo Items:"), c);

        c.gridx = 1;
        c.weightx = 0.7;
        c.gridheight = 3;
        c.fill = GridBagConstraints.BOTH;
        javax.swing.JTextArea itemListArea = new javax.swing.JTextArea(profileManager.getProfileItemList(), 10, 30);
        itemListArea.setLineWrap(true);
        JScrollPane itemListScroll = new JScrollPane(itemListArea);
        settingsPanel.add(itemListScroll, c);

        c.gridx = 0;
        c.gridy += 3;
        c.gridheight = 1;
        c.weightx = 0.3;
        c.fill = GridBagConstraints.HORIZONTAL;
        settingsPanel.add(new JLabel("Remote URL:"), c);

        c.gridx = 1;
        c.weightx = 0.7;
        javax.swing.JTextField remoteUrlField = new javax.swing.JTextField(profileManager.getProfileRemoteUrl());
        settingsPanel.add(remoteUrlField, c);

        c.gridx = 0;
        c.gridy++;
        c.weightx = 0.3;
        settingsPanel.add(new JLabel("Refresh Interval (min):"), c);

        c.gridx = 1;
        c.weightx = 0.7;
        javax.swing.JSpinner refreshIntervalSpinner = new javax.swing.JSpinner(
                new javax.swing.SpinnerNumberModel(profileManager.getProfileRefreshInterval(), 1, 60, 1));
        settingsPanel.add(refreshIntervalSpinner, c);

        c.gridx = 0;
        c.gridy++;
        c.weightx = 0.3;
        settingsPanel.add(new JLabel("Save Progress:"), c);

        c.gridx = 1;
        c.weightx = 0.7;
        JCheckBox persistObtainedCheckbox = new JCheckBox("Don't save obtained items between sessions",
                profileManager.getProfilePersistObtained());
        settingsPanel.add(persistObtainedCheckbox, c);

        int result = JOptionPane.showConfirmDialog(this,
                settingsPanel,
                "Profile Settings: " + config.currentProfile(),
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);

        if (result == JOptionPane.OK_OPTION) {
            // Save settings
            profileManager.setProfileItemSourceType((BingoConfig.ItemSourceType) itemSourceCombo.getSelectedItem());
            profileManager.setProfileItemList(itemListArea.getText());
            profileManager.setProfileRemoteUrl(remoteUrlField.getText());
            profileManager.setProfileRefreshInterval((Integer) refreshIntervalSpinner.getValue());
            profileManager.setProfilePersistObtained(persistObtainedCheckbox.isSelected());

            // Reload items
            plugin.reloadItems();

            // Update UI
            updateSourceWarningLabel();
        }
    }
} 