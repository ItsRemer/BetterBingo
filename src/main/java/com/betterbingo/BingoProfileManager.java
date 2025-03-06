package com.betterbingo;

import com.google.gson.Gson;
import com.google.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Manages bingo profiles, allowing players to create, switch between, and delete profiles.
 */
@Slf4j
@Singleton
public class BingoProfileManager {
    private static final String CONFIG_GROUP = "bingo";
    private static final String DEFAULT_PROFILE = "default";
    private static final String CONFIG_KEY_ACQUISITION_LOG = "acquisitionLog";
    private static final String CONFIG_KEY_ITEM_LIST = "itemList";
    private static final String CONFIG_KEY_REMOTE_URL = "remoteUrl";
    private static final String CONFIG_KEY_ITEM_SOURCE_TYPE = "itemSourceType";
    private static final String CONFIG_KEY_REFRESH_INTERVAL = "refreshInterval";
    private static final String CONFIG_KEY_PERSIST_OBTAINED = "persistObtained";

    @Inject
    private ConfigManager configManager;

    @Inject
    private BingoConfig config;

    @Inject
    private BingoPlugin plugin;

    @Inject
    private BingoAntiCheat antiCheat;

    @Inject
    private Gson gson;

    /**
     * Gets the list of available profiles.
     *
     * @return List of profile names
     */
    public List<String> getProfiles() {
        String profilesStr = config.profiles();
        if (profilesStr == null || profilesStr.isEmpty()) {
            return new ArrayList<>(List.of(DEFAULT_PROFILE));
        }

        return Arrays.stream(profilesStr.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    /**
     * Saves the list of profiles.
     *
     * @param profiles List of profile names
     */
    private void saveProfiles(List<String> profiles) {
        String profilesStr = String.join(",", profiles);
        config.setProfiles(profilesStr);
    }

    /**
     * Creates a new profile.
     *
     * @param profileName Name of the profile to create
     * @return true if the profile was created, false if it already exists
     */
    public boolean createProfile(String profileName) {
        if (profileName == null || profileName.trim().isEmpty()) {
            return false;
        }

        List<String> profiles = getProfiles();
        if (profiles.contains(profileName)) {
            return false;
        }

        profiles.add(profileName);
        saveProfiles(profiles);
        initializeProfileSettings(profileName);
        
        log.debug("Created new bingo profile: {}", profileName);
        return true;
    }

    /**
     * Initializes a profile with default settings.
     * This copies settings from the current profile to ensure consistency.
     *
     * @param profileName Name of the profile to initialize
     */
    private void initializeProfileSettings(String profileName) {
        String sourceTypeKey = getProfileKey(profileName, CONFIG_KEY_ITEM_SOURCE_TYPE);
        configManager.setConfiguration(CONFIG_GROUP, sourceTypeKey, getProfileItemSourceType().name());

        String itemListKey = getProfileKey(profileName, CONFIG_KEY_ITEM_LIST);
        configManager.setConfiguration(CONFIG_GROUP, itemListKey, getProfileItemList());

        String remoteUrlKey = getProfileKey(profileName, CONFIG_KEY_REMOTE_URL);
        configManager.setConfiguration(CONFIG_GROUP, remoteUrlKey, getProfileRemoteUrl());

        String refreshIntervalKey = getProfileKey(profileName, CONFIG_KEY_REFRESH_INTERVAL);
        configManager.setConfiguration(CONFIG_GROUP, refreshIntervalKey, getProfileRefreshInterval());

        String persistObtainedKey = getProfileKey(profileName, CONFIG_KEY_PERSIST_OBTAINED);
        configManager.setConfiguration(CONFIG_GROUP, persistObtainedKey, getProfilePersistObtained());
    }

    /**
     * Deletes a profile.
     *
     * @param profileName Name of the profile to delete
     * @return true if the profile was deleted, false if it doesn't exist
     */
    public boolean deleteProfile(String profileName) {
        if (profileName == null || profileName.trim().isEmpty()) {
            return false;
        }

        List<String> profiles = getProfiles();
        if (!profiles.contains(profileName)) {
            return false;
        }

        profiles.remove(profileName);
        saveProfiles(profiles);
        
        // Delete all configuration for this profile
        configManager.unsetConfiguration(CONFIG_GROUP, getProfileKey(profileName, "obtainedItems"));
        configManager.unsetConfiguration(CONFIG_GROUP, getProfileKey(profileName, CONFIG_KEY_ACQUISITION_LOG));
        configManager.unsetConfiguration(CONFIG_GROUP, getProfileKey(profileName, CONFIG_KEY_ITEM_LIST));
        configManager.unsetConfiguration(CONFIG_GROUP, getProfileKey(profileName, CONFIG_KEY_REMOTE_URL));
        configManager.unsetConfiguration(CONFIG_GROUP, getProfileKey(profileName, CONFIG_KEY_ITEM_SOURCE_TYPE));
        configManager.unsetConfiguration(CONFIG_GROUP, getProfileKey(profileName, CONFIG_KEY_REFRESH_INTERVAL));
        configManager.unsetConfiguration(CONFIG_GROUP, getProfileKey(profileName, CONFIG_KEY_PERSIST_OBTAINED));

        log.debug("Deleted bingo profile: {}", profileName);
        return true;
    }

    /**
     * Switches to a different profile.
     *
     * @param profileName Name of the profile to switch to
     */
    public void switchProfile(String profileName) {
        if (profileName == null || profileName.trim().isEmpty()) {
            return;
        }

        List<String> profiles = getProfiles();
        if (!profiles.contains(profileName)) {
            return;
        }

        // If we're already on this profile, do nothing
        if (profileName.equals(config.currentProfile())) {
            return;
        }
        forceSaveCurrentProfile();
        config.setCurrentProfile(profileName);
        plugin.clearObtainedItems();
        antiCheat.clearAcquisitionLog();
        plugin.reloadItems();
        plugin.forceLoadSavedItems();
        loadAcquisitionLog();
        plugin.updateUI();

        log.debug("Switched to bingo profile: {}", profileName);
    }

    /**
     * Force saves the current profile data regardless of persistObtained setting.
     */
    private void forceSaveCurrentProfile() {
        String obtainedItems = plugin.getItems().stream()
                .filter(BingoItem::isObtained)
                .map(BingoItem::getName)
                .collect(Collectors.joining(","));

        String profileKey = getCurrentProfileKey("obtainedItems");
        configManager.setConfiguration(CONFIG_GROUP, profileKey, obtainedItems);
        saveAcquisitionLog();

        log.debug("Force saved profile data for: {}", config.currentProfile());
    }

    /**
     * Resets the current profile.
     */
    public void resetCurrentProfile() {
        plugin.resetBingoBoard(false);
        antiCheat.clearAcquisitionLog();

        log.debug("Reset current bingo profile: {}", config.currentProfile());
    }

    /**
     * Gets a profile-specific configuration key.
     *
     * @param profileName Name of the profile
     * @param key Base configuration key
     * @return Profile-specific configuration key
     */
    public String getProfileKey(String profileName, String key) {
        return profileName + "_" + key;
    }

    /**
     * Gets a configuration key for the current profile.
     *
     * @param key Base configuration key
     * @return Profile-specific configuration key for the current profile
     */
    public String getCurrentProfileKey(String key) {
        return getProfileKey(config.currentProfile(), key);
    }

    /**
     * Saves the acquisition log for the current profile.
     */
    private void saveAcquisitionLog() {
        String profileKey = getCurrentProfileKey(CONFIG_KEY_ACQUISITION_LOG);
        antiCheat.saveAcquisitionLog(profileKey);
    }

    /**
     * Loads the acquisition log for the current profile.
     */
    private void loadAcquisitionLog() {
        String profileKey = getCurrentProfileKey(CONFIG_KEY_ACQUISITION_LOG);
        antiCheat.loadAcquisitionLog(profileKey);
    }

    /**
     * Gets the profile-specific item list.
     * @return The item list for the current profile
     */
    public String getProfileItemList() {
        String profileKey = getCurrentProfileKey(CONFIG_KEY_ITEM_LIST);
        String itemList = configManager.getConfiguration(CONFIG_GROUP, profileKey);
        if (itemList == null || itemList.isEmpty()) {
            return config.itemList();
        }
        
        return itemList;
    }
    
    /**
     * Sets the profile-specific item list.
     * @param itemList The item list to set for the current profile
     */
    public void setProfileItemList(String itemList) {
        String profileKey = getCurrentProfileKey(CONFIG_KEY_ITEM_LIST);
        configManager.setConfiguration(CONFIG_GROUP, profileKey, itemList);
    }
    
    /**
     * Gets the profile-specific remote URL.
     * @return The remote URL for the current profile
     */
    public String getProfileRemoteUrl() {
        String profileKey = getCurrentProfileKey(CONFIG_KEY_REMOTE_URL);
        String remoteUrl = configManager.getConfiguration(CONFIG_GROUP, profileKey);
        if (remoteUrl == null || remoteUrl.isEmpty()) {
            return config.remoteUrl();
        }
        
        return remoteUrl;
    }
    
    /**
     * Sets the profile-specific remote URL.
     * @param remoteUrl The remote URL to set for the current profile
     */
    public void setProfileRemoteUrl(String remoteUrl) {
        String profileKey = getCurrentProfileKey(CONFIG_KEY_REMOTE_URL);
        configManager.setConfiguration(CONFIG_GROUP, profileKey, remoteUrl);
    }
    
    /**
     * Gets the profile-specific item source type.
     * @return The item source type for the current profile
     */
    public BingoConfig.ItemSourceType getProfileItemSourceType() {
        String profileKey = getCurrentProfileKey(CONFIG_KEY_ITEM_SOURCE_TYPE);
        String itemSourceType = configManager.getConfiguration(CONFIG_GROUP, profileKey);
        if (itemSourceType == null || itemSourceType.isEmpty()) {
            return config.itemSourceType();
        }
        
        try {
            return BingoConfig.ItemSourceType.valueOf(itemSourceType);
        } catch (IllegalArgumentException e) {
            return config.itemSourceType();
        }
    }
    
    /**
     * Sets the profile-specific item source type.
     * @param itemSourceType The item source type to set for the current profile
     */
    public void setProfileItemSourceType(BingoConfig.ItemSourceType itemSourceType) {
        String profileKey = getCurrentProfileKey(CONFIG_KEY_ITEM_SOURCE_TYPE);
        configManager.setConfiguration(CONFIG_GROUP, profileKey, itemSourceType.name());
    }

    /**
     * Gets the profile-specific refresh interval.
     * @return The refresh interval for the current profile
     */
    public int getProfileRefreshInterval() {
        String profileKey = getCurrentProfileKey(CONFIG_KEY_REFRESH_INTERVAL);
        String refreshInterval = configManager.getConfiguration(CONFIG_GROUP, profileKey);
        if (refreshInterval == null || refreshInterval.isEmpty()) {
            return config.refreshInterval();
        }
        
        try {
            return Integer.parseInt(refreshInterval);
        } catch (NumberFormatException e) {
            return config.refreshInterval();
        }
    }
    
    /**
     * Sets the profile-specific refresh interval.
     * @param refreshInterval The refresh interval to set for the current profile
     */
    public void setProfileRefreshInterval(int refreshInterval) {
        String profileKey = getCurrentProfileKey(CONFIG_KEY_REFRESH_INTERVAL);
        configManager.setConfiguration(CONFIG_GROUP, profileKey, refreshInterval);
    }

    /**
     * Gets the profile-specific persistObtained setting.
     * @return Whether obtained items should persist between sessions for the current profile
     */
    public boolean getProfilePersistObtained() {
        String profileKey = getCurrentProfileKey(CONFIG_KEY_PERSIST_OBTAINED);
        String persistObtained = configManager.getConfiguration(CONFIG_GROUP, profileKey);
        if (persistObtained == null || persistObtained.isEmpty()) {
            return config.persistObtained();
        }
        
        return Boolean.parseBoolean(persistObtained);
    }
    
    /**
     * Sets the profile-specific persistObtained setting.
     * @param persistObtained Whether obtained items should persist between sessions for the current profile
     */
    public void setProfilePersistObtained(boolean persistObtained) {
        String profileKey = getCurrentProfileKey(CONFIG_KEY_PERSIST_OBTAINED);
        configManager.setConfiguration(CONFIG_GROUP, profileKey, persistObtained);
    }
} 