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

public class BingoPanel extends PluginPanel
{
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
    private final JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
    private final JButton resetButton = new JButton("Reset Board");
    private final JButton remoteUpdateButton = new JButton("Update from URL");
    private final JLabel sourceWarningLabel = new JLabel();
    private final JLabel itemLimitWarningLabel = new JLabel();

    @Inject
    public BingoPanel(ItemManager itemManager, BingoPlugin plugin, BingoConfig config)
    {
        super(false);
        
        this.itemManager = itemManager;
        this.plugin = plugin;
        this.config = config;
        
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
        
        // Add warning labels to a warnings panel
        JPanel warningsPanel = new JPanel(new BorderLayout(0, 5));
        warningsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        warningsPanel.add(sourceWarningLabel, BorderLayout.NORTH);
        warningsPanel.add(itemLimitWarningLabel, BorderLayout.CENTER);
        
        topPanel.add(warningsPanel, BorderLayout.NORTH);
        
        errorPanel.setContent("Bingo", "No items loaded.");
        topPanel.add(errorPanel, BorderLayout.CENTER);
        errorPanel.setVisible(false);
        
        // Add buttons to control panel
        controlPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        
        // Set up the control panel with appropriate buttons
        updateButtonVisibility();
        
        add(topPanel, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
        add(controlPanel, BorderLayout.SOUTH);
    }

    /**
     * Updates the source warning label based on the current item source type
     */
    private void updateSourceWarningLabel() {
        if (config.itemSourceType() == BingoConfig.ItemSourceType.MANUAL) {
            sourceWarningLabel.setText("<html><center>Using MANUAL mode - items from the 'Bingo Items' field will be used.<br>Remote URL will be ignored.</center></html>");
        } else {
            sourceWarningLabel.setText("<html><center>Using REMOTE URL mode - items from the URL will be used.<br>Manual 'Bingo Items' will be ignored.</center></html>");
        }
    }
    
    /**
     * Updates the visibility of buttons based on the selected item source
     */
    private void updateButtonVisibility() {
        // Update the source warning label
        updateSourceWarningLabel();
        
        // Remove all buttons
        controlPanel.removeAll();
        
        // Only show the remote update button if using remote source
        if (config.itemSourceType() == BingoConfig.ItemSourceType.REMOTE) {
            controlPanel.add(remoteUpdateButton);
            controlPanel.add(Box.createHorizontalStrut(10)); // Add spacing
        }
        
        // Always show the reset button
        controlPanel.add(resetButton);
        
        // Refresh the panel
        controlPanel.revalidate();
        controlPanel.repaint();
    }

    @Override
    public Dimension getPreferredSize()
    {
        return new Dimension(PluginPanel.PANEL_WIDTH, super.getPreferredSize().height);
    }

    public void updateItems(List<BingoItem> items)
    {
        // Update button visibility in case config changed
        updateButtonVisibility();
        
        itemsContainer.removeAll();
        
        if (items.isEmpty())
        {
            errorPanel.setVisible(true);
            itemLimitWarningLabel.setVisible(false);
            return;
        }
        
        errorPanel.setVisible(false);
        
        // Check if we have more than 25 items and show warning
        if (items.size() > MAX_ITEMS) {
            itemLimitWarningLabel.setText("<html><center>Warning: " + items.size() + " items found. Only the first 25 will be displayed.</center></html>");
            itemLimitWarningLabel.setVisible(true);
        } else {
            itemLimitWarningLabel.setVisible(false);
        }
        
        // Calculate cell size based on panel width
        int availableWidth = PluginPanel.PANEL_WIDTH - 10; // Account for border
        int cellSize = (availableWidth - (GRID_GAP * (GRID_SIZE - 1))) / GRID_SIZE;
        
        // Fill the grid with items or empty cells (limit to 25 items)
        for (int i = 0; i < MAX_ITEMS; i++)
        {
            JPanel cellPanel = new JPanel();
            cellPanel.setLayout(new BorderLayout(0, 1));
            cellPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
            cellPanel.setBorder(BorderFactory.createLineBorder(ColorScheme.DARKER_GRAY_COLOR.darker(), 1));
            cellPanel.setPreferredSize(new Dimension(cellSize, cellSize));
            
            if (i < items.size())
            {
                BingoItem item = items.get(i);
                
                JPanel contentPanel = new JPanel(new BorderLayout(0, 2));
                contentPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
                
                // Create item icon if we have the ID
                if (item.getItemId() != -1)
                {
                    AsyncBufferedImage itemImage = itemManager.getImage(item.getItemId());
                    JLabel iconLabel = new JLabel();
                    iconLabel.setHorizontalAlignment(SwingConstants.CENTER);
                    iconLabel.setPreferredSize(ITEM_SIZE);
                    itemImage.addTo(iconLabel);
                    contentPanel.add(iconLabel, BorderLayout.CENTER);
                }
                
                // Item name label with smaller font and word wrap
                JLabel itemLabel = new JLabel("<html><center><small>" + item.getName() + "</small></center></html>");
                itemLabel.setForeground(item.isObtained() ? ColorScheme.PROGRESS_COMPLETE_COLOR : Color.WHITE);
                itemLabel.setHorizontalAlignment(SwingConstants.CENTER);
                contentPanel.add(itemLabel, BorderLayout.SOUTH);
                
                cellPanel.add(contentPanel, BorderLayout.CENTER);
                
                // Add checkmark if obtained
                if (item.isObtained())
                {
                    JLabel checkLabel = new JLabel(CHECK_MARK);
                    checkLabel.setHorizontalAlignment(SwingConstants.CENTER);
                    cellPanel.add(checkLabel, BorderLayout.NORTH);
                }
            }
            else
            {
                // Empty cell for placeholder
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
} 