package com.betterbingo;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.api.Client;

import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

import javax.swing.SwingUtilities;
import java.util.Optional;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import javax.swing.JOptionPane;
import com.google.gson.JsonSyntaxException;

/**
 * Manages bingo profiles, ensuring isolation and non-interference between profiles.
 */
@Slf4j
@Singleton
public class BingoProfileManager {
    private static final String CONFIG_GROUP = "betterbingo";
    private static final String PROFILES_KEY = "profiles";
    private static final String PROFILE_KEY = "profiles";
    private static final String DEFAULT_PROFILE = "Default";
    private static final String PROFILE_ITEM_UNLOCKS_KEY = "_itemUnlocks";

    private static final String CONFIG_KEY_BINGO_MODE = "bingoMode";
    private static final String CONFIG_KEY_TEAM_CODE = "teamCode";
    private static final String CONFIG_KEY_TEAM_NAME = "teamName";
    private static final String CONFIG_KEY_DISCORD_WEBHOOK = "discordWebhook";
    private static final String CONFIG_KEY_ITEM_SOURCE_TYPE = "itemSourceType";
    private static final String CONFIG_KEY_REMOTE_URL = "remoteUrl";
    private static final String CONFIG_KEY_ITEM_LIST = "manualItems";
    private static final String CONFIG_KEY_REFRESH_INTERVAL = "refreshInterval";
    private static final String CONFIG_KEY_PERSIST_OBTAINED = "persistObtained";
    private static final String CONFIG_KEY_OBTAINED_ITEMS = "obtainedItems";
    private static final String CONFIG_KEY_ACQUISITION_LOG = "acquisitionLog";

    @Inject
    private BingoConfig config;

    @Inject
    private ConfigManager configManager;

    @Inject
    private Client client;

    @Inject
    private BingoPlugin plugin;

    @Inject
    private BingoTeamService teamService;

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    
    /**
     * GSON instance for serialization/deserialization.
     */
    private Gson gson;
    
    /**
     * A lock to ensure thread safety when accessing profiles.
     */
    private ReentrantLock profileLock;

    @Inject
    public BingoProfileManager(ConfigManager configManager, BingoConfig config, Client client, BingoTeamService teamService, BingoPlugin plugin) {
        this.configManager = configManager;
        this.config = config;
        this.client = client;
        this.teamService = teamService;
        this.plugin = plugin;
        this.profileLock = new ReentrantLock();
        
        // Create a well-configured Gson instance for consistent serialization
        this.gson = new GsonBuilder()
            .serializeNulls()
            .disableHtmlEscaping()
            .create();
        
        // Define the configuration group
        configManager.getConfiguration(CONFIG_GROUP, "version", String.class);
        
        // Ensure we have a current profile
        if (config.currentProfile() == null || config.currentProfile().isEmpty()) {
            log.info("No current profile set, initializing with default profile");
            setConfigDirectly(CONFIG_GROUP, "currentProfile", DEFAULT_PROFILE);
            
            // Ensure the default profile exists in the profile list
            List<String> profiles = getProfiles();
            if (!profiles.contains(DEFAULT_PROFILE)) {
                profiles.add(DEFAULT_PROFILE);
                saveProfiles(profiles);
                initializeProfileSettings(DEFAULT_PROFILE);
            }
        }
    }

    /**
     * Gets the list of profiles.
     *
     * @return The list of profiles
     */
    public List<String> getProfiles() {
        try {
            String profilesJson = configManager.getConfiguration(CONFIG_GROUP, PROFILES_KEY);
            if (profilesJson == null || profilesJson.isEmpty()) {
                return new ArrayList<>();
            }
            
            // Create a more lenient Gson parser
            Gson gson = new GsonBuilder().setLenient().create();
            
            try {
                // First try parsing as a string array
                Type listType = new TypeToken<ArrayList<String>>() {}.getType();
                List<String> profiles = gson.fromJson(profilesJson, listType);
                List<String> result = profiles != null ? profiles : new ArrayList<>();
                log.debug("Retrieved profiles as array: {}", result);
                return result;
            } catch (JsonSyntaxException e) {
                log.debug("Failed to parse profiles as array, trying alternative formats: {}", e.getMessage());
                
                // If that fails, try parsing as a single string
                try {
                    String singleProfile = gson.fromJson(profilesJson, String.class);
                    if (singleProfile != null && !singleProfile.isEmpty()) {
                        List<String> result = new ArrayList<>();
                        result.add(singleProfile);
                        log.debug("Retrieved single profile: {}", result);
                        return result;
                    }
                } catch (JsonSyntaxException e2) {
                    log.debug("Failed to parse as single string: {}", e2.getMessage());
                }
                
                // If all parsing fails, try a raw approach
                if (profilesJson.startsWith("\"") && profilesJson.endsWith("\"")) {
                    // Might be a quoted string
                    String unquoted = profilesJson.substring(1, profilesJson.length() - 1);
                    List<String> result = new ArrayList<>();
                    result.add(unquoted);
                    log.debug("Retrieved profile from quoted string: {}", result);
                    return result;
                }
                
                // Last resort: treat the entire string as a profile name
                List<String> result = new ArrayList<>();
                result.add(profilesJson);
                log.debug("Using raw string as profile name: {}", result);
                return result;
            }
        } catch (Exception e) {
            log.error("Error retrieving profiles", e);
            return new ArrayList<>();
        }
    }

    /**
     * Saves the list of profiles to the configuration.
     * 
     * @param profiles The list of profiles to save
     * @return True if the profiles were saved successfully, false otherwise
     */
    private boolean saveProfiles(List<String> profiles) {
        try {
            // Create a Gson serializer
            Gson gson = new GsonBuilder().create();
            
            // Ensure we serialize as a JSON array even for a single profile
            String profilesJson = gson.toJson(profiles);
            log.debug("Saving profiles as JSON array: {}", profilesJson);
            
            // Save the profiles to the configuration
            configManager.setConfiguration(CONFIG_GROUP, PROFILES_KEY, profilesJson);
            
            return true;
        } catch (Exception e) {
            log.error("Error saving profiles", e);
            return false;
        }
    }

    /**
     * Creates a new profile.
     *
     * @param profileName The name of the new profile.
     * @return True if the profile was created successfully, false otherwise.
     */
    public boolean createProfile(String profileName) {
        profileLock.lock();
        try {
            List<String> profiles = getProfiles();
            if (profiles.contains(profileName)) {
                return false;
            }
            profiles.add(profileName);
            
            // Use the new saveProfiles method for more robust serialization
            if (!saveProfiles(profiles)) {
                log.error("Failed to save profiles after adding {}", profileName);
                return false;
            }
            
            // Initialize the profile with default settings
            initializeProfileSettings(profileName);
            
            return true;
        } catch (Exception e) {
            log.error("Error creating profile: {}", profileName, e);
            return false;
        } finally {
            profileLock.unlock();
        }
    }

    /**
     * Deletes a profile.
     *
     * @param profileName The name of the profile to delete.
     * @return True if the profile was deleted successfully, false otherwise.
     */
    public boolean deleteProfile(String profileName) {
        profileLock.lock();
        try {
            List<String> profiles = getProfiles();
            if (!profiles.contains(profileName)) {
                log.warn("Attempted to delete non-existent profile: {}", profileName);
                return false; // Profile doesn't exist
            }

            // Check if it's a team profile and delete from backend if so
            BingoConfig.BingoMode mode = getProfileBingoMode(profileName);
            if (mode == BingoConfig.BingoMode.TEAM) {
                String teamCode = getProfileTeamCode(profileName);
                if (teamCode != null && !teamCode.isEmpty()) {
                    log.info("Profile '{}' is a team profile (code: {}). Checking leadership before backend deletion.", profileName, teamCode);
                    long currentUserHash = client.getAccountHash();
                    
                    if (currentUserHash == -1) {
                         log.warn("Cannot verify leadership for team {} deletion: User not logged in or hash unavailable.", teamCode);
                    } else {
                        // Start asynchronous check and potential deletion
                        final String finalTeamCode = teamCode; // Effectively final for lambda
                        teamService.getTeamData(finalTeamCode)
                            .thenCompose(teamDataMap -> {
                                if (teamDataMap == null) {
                                    log.error("Cannot verify leadership for team {}: Failed to get team data.", finalTeamCode);
                                    return CompletableFuture.completedFuture(false); // Indicate backend delete not performed
                                }
                                
                                String leaderHashStr = (String) teamDataMap.get("leaderAccountHash");
                                if (leaderHashStr == null || leaderHashStr.isEmpty()) {
                                    log.warn("Cannot verify leadership for team {}: Leader hash not found in team data.", finalTeamCode);
                                     return CompletableFuture.completedFuture(false); // Indicate backend delete not performed
                                }
                                
                                if (leaderHashStr.equals(String.valueOf(currentUserHash))) {
                                    log.info("Current user ({}) is leader for team {}. Proceeding with backend deletion.", currentUserHash, finalTeamCode);
                                    return teamService.deleteTeam(finalTeamCode); // Return the delete future
                                } else {
                                    log.info("Current user ({}) is not leader for team {} (leader: {}). Skipping backend deletion.", currentUserHash, finalTeamCode, leaderHashStr);
                                     return CompletableFuture.completedFuture(false); // Indicate backend delete not performed
                                }
                            })
                            .thenAccept(deleted -> {
                                if (deleted) {
                                    log.info("Asynchronous backend deletion for team {} completed successfully.", finalTeamCode);
                                } else {
                                    // This case means either user wasn't leader or delete failed
                                    log.info("Asynchronous backend deletion for team {} was either skipped (not leader) or failed.", finalTeamCode);
                                }
                            })
                            .exceptionally(ex -> {
                                log.error("Error during leadership check or backend deletion for team {} (profile {}).", finalTeamCode, profileName, ex);
                                return null; // Consume exception
                            });
                    }
                } else {
                    log.warn("Profile '{}' is TEAM mode but has no team code stored. Cannot delete from backend.", profileName);
                }
            }
            
            // Proceed with local profile deletion IMMEDIATELY regardless of async backend check/delete outcome
            log.info("Proceeding with immediate local deletion of profile: {}", profileName);

            // Proceed with local profile deletion
            if (!profiles.remove(profileName)) {
                // This shouldn't happen due to the check above, but handle defensively
                 log.error("Failed to remove profile '{}' from list, though it was found initially.", profileName);
                return false;
            }
            
            // Use the new saveProfiles method for more robust serialization
            if (!saveProfiles(profiles)) {
                log.error("Failed to save profiles after removing {}", profileName);
                return false;
            }
            
            // Remove all profile-specific configurations
            // configManager.unsetConfiguration(CONFIG_GROUP, profileName); // This seems to remove sub-keys too based on RL docs
            log.debug("Removing configuration keys for profile: {}", profileName);
            List<String> keysToRemove = new ArrayList<>();
            String prefix = profileName + ".";
            for (String key : configManager.getConfigurationKeys(CONFIG_GROUP + "." + profileName)) { // Iterate profile-specific keys
                 keysToRemove.add(key); // Add the full key path like bingo.profileName.key
            }
            // Also remove the legacy item unlocks key if present
            if (configManager.getConfiguration(CONFIG_GROUP, profileName + PROFILE_ITEM_UNLOCKS_KEY) != null) {
                 keysToRemove.add(profileName + PROFILE_ITEM_UNLOCKS_KEY);
            }
            
            for (String key : keysToRemove) {
                log.debug("Unsetting config key: {}", key);
                configManager.unsetConfiguration(CONFIG_GROUP, key);
            }
            log.info("Successfully deleted local profile data for: {}", profileName);
            
            return true;
        } catch (Exception e) {
            log.error("Error deleting profile: {}", profileName, e);
            return false;
        } finally {
            profileLock.unlock();
        }
    }

    /**
     * Switches to a different profile
     * @param profileKey The profile key
     */
    public void switchProfile(String profileKey) {
        if (profileKey == null || profileKey.isEmpty()) {
            log.error("Attempted to switch to null or empty profile");
            return;
        }

        log.info("Switching profile in config: {}", profileKey);
        
        try {
            // Save current profile first
            final String currentProfile = config.currentProfile();
            if (currentProfile != null && !currentProfile.isEmpty()) {
                saveProfile(currentProfile);
            }
            
            // Unregister team listeners for the current profile
            final String currentTeamCode = getProfileTeamCode(currentProfile);
            if (currentTeamCode != null && !currentTeamCode.isEmpty() && 
                    getProfileBingoMode(currentProfile) == BingoConfig.BingoMode.TEAM) {
                log.info("Unregistering team listeners for profile: {}, teamCode: {}", 
                        currentProfile, currentTeamCode);
                teamService.unregisterTeamListener(currentTeamCode);
            }
            
            // Update the config to switch profiles - this is the key step
            // Use the direct config write approach for more reliability
            setConfigDirectly(CONFIG_GROUP, "currentProfile", profileKey);
            
            // Verify config change by reading it back
            String actualProfile = config.currentProfile();
            if (!profileKey.equals(actualProfile)) {
                log.error("CRITICAL: Profile switch failed despite direct write. Wanted: {}, Got: {}", 
                    profileKey, actualProfile);
            } else {
                log.info("Profile switch config verified successful: {}", profileKey);
            }
            
            // Register team listeners for the new profile
            final String newTeamCode = getProfileTeamCode(profileKey);
            if (newTeamCode != null && !newTeamCode.isEmpty() && 
                    getProfileBingoMode(profileKey) == BingoConfig.BingoMode.TEAM) {
                log.info("Registering team listeners for profile: {}, teamCode: {}", profileKey, newTeamCode);
                registerTeamListeners(profileKey);
            }
            
            log.info("Profile switch config update complete for: {}", profileKey);
        } catch (Exception e) {
            log.error("Error during profile switch config update", e);
        }
    }

    /**
     * Unregisters team listeners for a profile.
     *
     * @param profileName The name of the profile
     */
    private void unregisterTeamListeners(String profileName) {
        try {
            BingoConfig.BingoMode bingoMode = getProfileBingoMode(profileName);
            if (bingoMode == BingoConfig.BingoMode.TEAM) {
                String teamCode = getProfileTeamCode(profileName);
                if (teamCode != null && !teamCode.isEmpty()) {
                    log.info("Unregistering team listeners for profile: {}, teamCode: {}", profileName, teamCode);
                    teamService.unregisterTeamListener(teamCode);
                }
            }
        } catch (Exception e) {
            log.error("Error unregistering team listeners for profile: {}", profileName, e);
        }
    }

    /**
     * Registers team listeners for a profile.
     *
     * @param profileName The name of the profile
     */
    private void registerTeamListeners(String profileName) {
        try {
            BingoConfig.BingoMode bingoMode = getProfileBingoMode(profileName);
            if (bingoMode == BingoConfig.BingoMode.TEAM) {
                String teamCode = getProfileTeamCode(profileName);
                if (teamCode != null && !teamCode.isEmpty()) {
                    log.info("Registering team listeners for profile: {}, teamCode: {}", profileName, teamCode);
                    teamService.registerTeamListener(teamCode, plugin::updateItemsFromFirebase)
                        .exceptionally(ex -> {
                            log.error("Error registering team listener: {}", ex.getMessage(), ex);
                            
                            // Check explicitly for 404 errors, which indicates the team no longer exists
                            String errorMessage = ex.getMessage();
                            Throwable cause = ex.getCause();
                            
                            boolean isTeamNotFound = 
                                // Direct check for 404 in the message
                                (errorMessage != null && errorMessage.contains("404")) ||
                                // Check the cause messages for 404
                                (cause != null && cause.getMessage() != null && cause.getMessage().contains("404")) ||
                                // Message checks for common team not found messages
                                (errorMessage != null && (
                                    errorMessage.contains("Team not found") || 
                                    errorMessage.contains("no longer exists") ||
                                    errorMessage.contains("Failed to get team data") ||
                                    errorMessage.contains("Team data is null")
                                ));
                            
                            if (isTeamNotFound) {
                                log.error("Team not found in database while registering listeners: {}", teamCode);
                                // Show warning to the user on the EDT
                                final String finalTeamCode = teamCode;
                                final String finalProfileName = profileName;
                                SwingUtilities.invokeLater(() -> {
                                    // Immediately ask user if they want to delete the profile
                                    int option = JOptionPane.showConfirmDialog(
                                        null,
                                        "The team \"" + finalTeamCode + "\" no longer exists in the database.\n" +
                                        "It may have been deleted by the team creator.\n" +
                                        "Would you like to delete this profile?",
                                        "Team Not Found",
                                        JOptionPane.YES_NO_OPTION,
                                        JOptionPane.WARNING_MESSAGE
                                    );
                                    
                                    if (option == JOptionPane.YES_OPTION) {
                                        // Delete the profile
                                        if (deleteProfile(finalProfileName)) {
                                            // Refresh the UI immediately
                                            SwingUtilities.invokeLater(() -> {
                                                // Update the profile dropdown in the panel
                                                if (plugin != null) {
                                                    plugin.getPanel().ifPresent(panel -> panel.updateProfileComboBox());
                                                }
                                                
                                                JOptionPane.showMessageDialog(
                                                    null,
                                                    "Profile \"" + finalProfileName + "\" has been deleted.",
                                                    "Profile Deleted",
                                                    JOptionPane.INFORMATION_MESSAGE
                                                );
                                            });
                                            
                                            // Switch to a different profile
                                            List<String> profiles = getProfiles();
                                            if (!profiles.isEmpty()) {
                                                String newProfile = profiles.get(0);
                                                log.info("Automatically switching to profile: {} after deletion", newProfile);
                                                switchProfile(newProfile);
                                            }
                                        } else {
                                            JOptionPane.showMessageDialog(
                                                null,
                                                "Failed to delete profile \"" + finalProfileName + "\".",
                                                "Error",
                                                JOptionPane.ERROR_MESSAGE
                                            );
                                        }
                                    }
                                });
                            }
                            
                            return null;
                        });
                }
            }
        } catch (Exception e) {
            log.error("Error registering team listeners for profile: {}", profileName, e);
        }
    }

    /**
     * Clears all item unlocks from the configuration.
     */
    private void clearItemUnlocks() {
        for (String key : configManager.getConfigurationKeys(CONFIG_GROUP)) {
            if (key.startsWith("itemUnlock_")) {
                configManager.unsetConfiguration(CONFIG_GROUP, key);
            }
        }
    }

    /**
     * Saves the current profile's configuration.
     *
     * @param profileName The name of the profile to save.
     * @return True if the profile was saved successfully, false otherwise.
     */
    public boolean saveProfile(String profileName) {
        profileLock.lock();
        try {
            log.info("Saving profile: {}", profileName);
            
            JsonObject profileData = new JsonObject();
            profileData.addProperty("teamCode", configManager.getConfiguration(CONFIG_GROUP, "teamCode"));
            profileData.addProperty("itemSourceType", configManager.getConfiguration(CONFIG_GROUP, "itemSourceType"));
            profileData.addProperty("remoteUrl", configManager.getConfiguration(CONFIG_GROUP, "remoteUrl"));
            profileData.addProperty("manualItems", configManager.getConfiguration(CONFIG_GROUP, "manualItems"));
            
            // Fix ambiguous method calls by explicitly casting to the correct types
            Integer refreshInterval = configManager.getConfiguration(CONFIG_GROUP, "refreshInterval", int.class);
            profileData.addProperty("refreshInterval", refreshInterval);
            
            Boolean persistObtained = configManager.getConfiguration(CONFIG_GROUP, "persistObtained", boolean.class);
            profileData.addProperty("persistObtained", persistObtained);

            configManager.setConfiguration(CONFIG_GROUP, profileName, gson.toJson(profileData));

            // Save item unlocks
            JsonObject itemUnlocksData = new JsonObject();
            for (String key : configManager.getConfigurationKeys(CONFIG_GROUP)) {
                if (key.startsWith("itemUnlock_")) {
                    itemUnlocksData.addProperty(key, configManager.getConfiguration(CONFIG_GROUP, key));
                }
            }
            configManager.setConfiguration(CONFIG_GROUP, profileName + PROFILE_ITEM_UNLOCKS_KEY, gson.toJson(itemUnlocksData));

            // Save acquisition log
            saveAcquisitionLog();
            
            log.info("Successfully saved profile: {}", profileName);
            return true;
        } catch (Exception e) {
            log.error("Error saving profile: {}", profileName, e);
            return false;
        } finally {
            profileLock.unlock();
        }
    }

    /**
     * Creates a new team profile and sets up the team.
     *
     * @param profileName    The name of the profile to create
     * @param teamName       The name of the team to create
     * @param discordWebhook Discord webhook URL for the team
     * @param itemSourceType Item source type
     * @param remoteUrl      Remote URL for the item source
     * @param manualItems    Manual items for the item source
     * @param refreshInterval Refresh interval in seconds
     * @param persistObtained Whether to persist obtained items
     * @return A CompletableFuture that completes with the team code when the team is created
     */
    public CompletableFuture<String> createTeamProfile(String profileName, String teamName, String discordWebhook,
                                                      BingoConfig.ItemSourceType itemSourceType, String remoteUrl,
                                                      String manualItems, int refreshInterval, boolean persistObtained) {
        // Create the profile and initialize settings
        boolean created = createProfile(profileName);
        if (!created) {
            CompletableFuture<String> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalArgumentException("Failed to create profile: " + profileName));
            return future;
        }
        
        // Initialize profile settings
        initializeProfileSettings(profileName);
        
        // Set item source type, remoteUrl, etc. right away
        setProfileKey(profileName, CONFIG_KEY_ITEM_SOURCE_TYPE, itemSourceType.toString());
        setProfileKey(profileName, CONFIG_KEY_REMOTE_URL, remoteUrl);
        setProfileKey(profileName, CONFIG_KEY_ITEM_LIST, manualItems != null ? manualItems : "");
        setProfileKey(profileName, CONFIG_KEY_REFRESH_INTERVAL, String.valueOf(refreshInterval));
        setProfileKey(profileName, CONFIG_KEY_PERSIST_OBTAINED, String.valueOf(persistObtained));
        
        // Set team name
        setProfileKey(profileName, CONFIG_KEY_TEAM_NAME, teamName);
        
        // Set Discord webhook
        setProfileKey(profileName, CONFIG_KEY_DISCORD_WEBHOOK, discordWebhook != null ? discordWebhook : "");
        
        // Create the team and then set the profile key once we have a team code
        return teamService.createTeam(teamName, discordWebhook, itemSourceType, remoteUrl, manualItems, refreshInterval, persistObtained)
                .thenApply(teamCode -> {
                    log.info("Team created successfully for profile {} with code: {}", profileName, teamCode);
                    
                    // Set the team code in the profile config
                    if (teamCode != null && !teamCode.isEmpty()) {
                        setProfileKey(profileName, CONFIG_KEY_TEAM_CODE, teamCode);
                    } else {
                        log.error("Received null or empty team code for profile {}", profileName);
                        throw new RuntimeException("Failed to create team: received null team code");
                    }
                    
                    log.info("[createTeamProfile] Setting final settings for profile: {}", profileName);
                    
                    // Set the bingo mode to TEAM
                    setProfileBingoMode(profileName, BingoConfig.BingoMode.TEAM);
                    log.info("[createTeamProfile] Set bingoMode = TEAM for profile: {}", profileName);
                    
                    // Set the profile as active
                    config.setCurrentProfile(profileName);
                    
                    // Register team listeners
                    registerTeamListeners(profileName);
                    
                    return teamCode;
                })
                .exceptionally(ex -> {
                    log.error("Failed to create team for profile {}: {}. Cleaning up profile.", profileName, ex.getMessage());
                    
                    // Clean up the profile if team creation fails
                    try {
                        if (plugin != null) {
                            plugin.clearObtainedItems();
                        }

                        // Get the team code if it was set previously
                        String existingTeamCode = getProfileTeamCode(profileName);
                        BingoConfig.BingoMode bingoMode = getProfileBingoMode(profileName);
                        
                        // If the profile is in TEAM mode and has a team code, try to delete the team from the backend
                        if (bingoMode == BingoConfig.BingoMode.TEAM && existingTeamCode != null && !existingTeamCode.isEmpty()) {
                            teamService.deleteTeam(existingTeamCode);
                        } else {
                            log.warn("Profile '{}' is TEAM mode but has no team code stored. Cannot delete from backend.", profileName);
                        }
                        
                        log.info("Proceeding with immediate local deletion of profile: {}", profileName);
                        deleteProfile(profileName);
                        
                        log.info("Successfully deleted local profile data for: {}", profileName);
                    } catch (Exception e) {
                        log.error("Error cleaning up profile {} after team creation failure", profileName, e);
                    }
                    
                    // Propagate the exception
                    throw new RuntimeException("Team creation failed: " + ex.getMessage(), ex);
                });
    }

    /**
     * Joins an existing team profile
     *
     * @param profileName The profile name
     * @param teamCode    The team code
     * @return A CompletableFuture that resolves to a boolean indicating success
     */
    public CompletableFuture<Boolean> joinTeamProfile(String profileName, String teamCode) {
        // Create the profile
        boolean created = createProfile(profileName);
        if (!created) {
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalArgumentException("Failed to create profile: " + profileName));
            return future;
        }

        // Initialize basic settings first
        initializeProfileSettings(profileName);

        log.info("Attempting to join team {} for profile: {}", teamCode, profileName);

        // Join the team on the backend
        return teamService.joinTeam(teamCode)
                .thenCompose(success -> {
                    if (success) {
                        log.info("Successfully joined team {} for profile {}", teamCode, profileName);
                        // Fetch team data to get settings
                        return teamService.getTeamData(teamCode).thenCompose(teamData -> {
                            log.info("Fetched team data for joined team: {}", teamCode);
                            
                            // Now that team is created, set the final profile settings, including TEAM mode
                            profileLock.lock();
                            try {
                                setProfileBingoMode(profileName, BingoConfig.BingoMode.TEAM);
                                setProfileKey(profileName, CONFIG_KEY_TEAM_CODE, teamCode);
                                setProfileKey(profileName, CONFIG_KEY_TEAM_NAME, (String) teamData.getOrDefault("teamName", ""));
                                setProfileKey(profileName, CONFIG_KEY_DISCORD_WEBHOOK, (String) teamData.getOrDefault("discordWebhook", ""));
                                String itemSourceStr = (String) teamData.getOrDefault("itemSourceType", BingoConfig.ItemSourceType.MANUAL.toString());
                                setProfileKey(profileName, CONFIG_KEY_ITEM_SOURCE_TYPE, itemSourceStr);
                                setProfileKey(profileName, CONFIG_KEY_REMOTE_URL, (String) teamData.getOrDefault("remoteUrl", ""));
                                setProfileKey(profileName, CONFIG_KEY_ITEM_LIST, (String) teamData.getOrDefault("manualItems", ""));
                                setProfileKey(profileName, CONFIG_KEY_REFRESH_INTERVAL, String.valueOf(teamData.getOrDefault("refreshInterval", config.refreshInterval())));
                                setProfileKey(profileName, CONFIG_KEY_PERSIST_OBTAINED, String.valueOf(teamData.getOrDefault("persistObtained", config.persistObtained())));
                                log.info("Final profile settings applied for joined team profile: {}", profileName);
                            } finally {
                                profileLock.unlock();
                            }

                            // Switch to the newly configured profile
                            switchProfile(profileName);
                            
                            // Since switchProfile is void, we complete the future directly
                            log.info("Switched to joined team profile: {}", profileName);
                            
                            // Register listener after switching
                            teamService.registerTeamListener(teamCode, plugin::updateItemsFromFirebase);
                            
                            // Update the UI on the EDT
                            SwingUtilities.invokeLater(() -> {
                                Optional<BingoPanel> panelOptional = plugin.getPanel();
                                panelOptional.ifPresent(BingoPanel::updateProfileComboBox);
                            });
                            
                            return CompletableFuture.completedFuture(true);
                        });
                    } else {
                        log.warn("Failed to join team {} for profile {}. Cleaning up profile.", teamCode, profileName);
                        // If joining failed, delete the profile
                        deleteProfile(profileName);
                        return CompletableFuture.completedFuture(false);
                    }
                })
                .exceptionally(ex -> {
                    log.error("Failed to join team {} for profile {}: {}. Cleaning up profile.", teamCode, profileName, ex.getMessage(), ex);
                    // Clean up the profile if joining failed
                    deleteProfile(profileName);
                    // Rethrow or handle the exception appropriately
                    throw new RuntimeException("Team joining failed: " + ex.getMessage(), ex);
                });
    }

    /**
     * Initializes a profile with default settings.
     * This copies settings from the current profile to ensure consistency.
     *
     * @param profileName Name of the profile to initialize
     */
    private void initializeProfileSettings(String profileName) {
        profileLock.lock();
        try {
            log.debug("Initializing default settings for profile: {}", profileName);
            // Copy settings from the default config or current profile if available
            String currentProfile = config.currentProfile();
            boolean useCurrent = !currentProfile.equals(profileName) && getProfiles().contains(currentProfile);

            // Helper lambda to get config value, falling back to default
            java.util.function.BiFunction<String, java.util.function.Supplier<String>, String> getConfigValue =
                    (key, defaultValueSupplier) -> {
                        String value = useCurrent ? getProfileKey(currentProfile, key) : null;
                        return value != null ? value : defaultValueSupplier.get();
                    };

            // Set default values for the new profile
            String itemList = getConfigValue.apply(CONFIG_KEY_ITEM_LIST, config::manualItems);
            setProfileKey(profileName, CONFIG_KEY_ITEM_LIST, itemList);

            String remoteUrl = getConfigValue.apply(CONFIG_KEY_REMOTE_URL, config::remoteUrl);
            setProfileKey(profileName, CONFIG_KEY_REMOTE_URL, remoteUrl);

            String itemSourceType = getConfigValue.apply(CONFIG_KEY_ITEM_SOURCE_TYPE, () -> config.itemSourceType().toString());
            setProfileKey(profileName, CONFIG_KEY_ITEM_SOURCE_TYPE, itemSourceType);

            String refreshInterval = getConfigValue.apply(CONFIG_KEY_REFRESH_INTERVAL, () -> String.valueOf(config.refreshInterval()));
            setProfileKey(profileName, CONFIG_KEY_REFRESH_INTERVAL, refreshInterval);

            String persistObtained = getConfigValue.apply(CONFIG_KEY_PERSIST_OBTAINED, () -> String.valueOf(config.persistObtained()));
            setProfileKey(profileName, CONFIG_KEY_PERSIST_OBTAINED, persistObtained);

            String discordWebhook = getConfigValue.apply(CONFIG_KEY_DISCORD_WEBHOOK, config::discordWebhook);
            setProfileKey(profileName, CONFIG_KEY_DISCORD_WEBHOOK, discordWebhook);
            
            String teamName = getConfigValue.apply(CONFIG_KEY_TEAM_NAME, () -> "");
            setProfileKey(profileName, CONFIG_KEY_TEAM_NAME, teamName);
            
            String teamCode = getConfigValue.apply(CONFIG_KEY_TEAM_CODE, () -> "");
            setProfileKey(profileName, CONFIG_KEY_TEAM_CODE, teamCode);

            // Set default bingo mode to SOLO initially. Use .name()
            setProfileBingoMode(profileName, BingoConfig.BingoMode.SOLO);
            log.debug("Finished initializing settings for profile: {}", profileName);
        } finally {
            profileLock.unlock();
        }
    }

    /**
     * Gets a configuration value for a specific profile.
     *
     * @param profileName Name of the profile
     * @param key         Configuration key
     * @return Configuration value or null if not found
     */
    public String getProfileKey(String profileName, String key) {
        if (profileName == null || profileName.isEmpty()) {
            log.warn("Attempted to get key '{}' for null or empty profile name", key);
            return null;
        }
        return configManager.getConfiguration(CONFIG_GROUP, profileName + "." + key);
    }
    
    /**
     * Sets a configuration value for a specific profile.
     *
     * @param profileName Name of the profile
     * @param key         Configuration key
     * @param value       Configuration value
     */
    private void setProfileKey(String profileName, String key, String value) {
        if (profileName == null || profileName.isEmpty()) {
            log.warn("Attempted to set key '{}' for null or empty profile name", key);
            return;
        }
        configManager.setConfiguration(CONFIG_GROUP, profileName + "." + key, value);
    }

    /**
     * Gets a configuration value for the current profile.
     *
     * @param key Configuration key
     * @return Configuration value or null if not found
     */
    public String getCurrentProfileKey(String key) {
        String currentProfile = config.currentProfile();
        if (currentProfile == null || currentProfile.isEmpty()) {
            log.warn("Current profile is null or empty when getting key '{}'", key);
            return null;
        }
        return getProfileKey(currentProfile, key);
    }

    /**
     * Saves the acquisition log for the current profile.
     */
    private void saveAcquisitionLog() {
        // Uncomment this when the antiCheat component is implemented
        /*
        String currentProfile = config.currentProfile();
        if (currentProfile == null || currentProfile.isEmpty()) return;
        String profileKey = currentProfile + "." + CONFIG_KEY_ACQUISITION_LOG;
        antiCheat.saveAcquisitionLog(profileKey);
        */
    }

    /**
     * Loads the acquisition log for the current profile.
     */
    private void loadAcquisitionLog() {
        // Uncomment this when the antiCheat component is implemented
        /*
        String currentProfile = config.currentProfile();
        if (currentProfile == null || currentProfile.isEmpty()) return;
        String profileKey = currentProfile + "." + CONFIG_KEY_ACQUISITION_LOG;
        antiCheat.loadAcquisitionLog(profileKey);
        */
    }

    /**
     * Gets the item list for the current profile.
     *
     * @return Item list string
     */
    public String getProfileItemList() {
        String itemList = getCurrentProfileKey(CONFIG_KEY_ITEM_LIST);
        return itemList != null ? itemList : config.manualItems();
    }

    /**
     * Sets the item list for the current profile.
     *
     * @param itemList Item list string
     */
    public void setProfileItemList(String itemList) {
        String currentProfile = config.currentProfile();
        if (currentProfile == null || currentProfile.isEmpty()) return;
        setProfileKey(currentProfile, CONFIG_KEY_ITEM_LIST, itemList);
    }

    /**
     * Gets the remote URL for the current profile.
     *
     * @return Remote URL
     */
    public String getProfileRemoteUrl() {
        String remoteUrl = getCurrentProfileKey(CONFIG_KEY_REMOTE_URL);
        if (remoteUrl == null) {
            // Use the default remote URL if not set for this profile
            remoteUrl = config.remoteUrl();
            setProfileRemoteUrl(remoteUrl);
        }
        return remoteUrl;
    }

    /**
     * Sets the remote URL for the current profile.
     *
     * @param remoteUrl Remote URL
     */
    public void setProfileRemoteUrl(String remoteUrl) {
        String currentProfile = config.currentProfile();
        if (currentProfile == null || currentProfile.isEmpty()) return;
        setProfileKey(currentProfile, CONFIG_KEY_REMOTE_URL, remoteUrl);
    }

    /**
     * Gets the item source type for the current profile.
     *
     * @return Item source type
     */
    public BingoConfig.ItemSourceType getProfileItemSourceType() {
        String itemSourceType = getCurrentProfileKey(CONFIG_KEY_ITEM_SOURCE_TYPE);
        if (itemSourceType == null) {
            // Use the default item source type if not set for this profile
            BingoConfig.ItemSourceType sourceType = config.itemSourceType();
            setProfileItemSourceType(sourceType);
            return sourceType;
        }
        
        try {
            return BingoConfig.ItemSourceType.valueOf(itemSourceType);
        } catch (IllegalArgumentException e) {
            // If the stored value is invalid, use the default
            BingoConfig.ItemSourceType sourceType = config.itemSourceType();
            setProfileItemSourceType(sourceType);
            return sourceType;
        }
    }

    /**
     * Sets the item source type for the current profile.
     *
     * @param itemSourceType Item source type
     */
    public void setProfileItemSourceType(BingoConfig.ItemSourceType itemSourceType) {
        String currentProfile = config.currentProfile();
        if (currentProfile == null || currentProfile.isEmpty()) return;
        setProfileKey(currentProfile, CONFIG_KEY_ITEM_SOURCE_TYPE, itemSourceType.toString());
    }

    /**
     * Gets the refresh interval for the current profile.
     *
     * @return Refresh interval
     */
    public int getProfileRefreshInterval() {
        String refreshInterval = getCurrentProfileKey(CONFIG_KEY_REFRESH_INTERVAL);
        if (refreshInterval == null) {
            // Use the default refresh interval if not set for this profile
            int interval = config.refreshInterval();
            setProfileRefreshInterval(interval);
            return interval;
        }
        
        try {
            return Integer.parseInt(refreshInterval);
        } catch (NumberFormatException e) {
            // If the stored value is invalid, use the default
            int interval = config.refreshInterval();
            setProfileRefreshInterval(interval);
            return interval;
        }
    }

    /**
     * Sets the refresh interval for the current profile.
     *
     * @param refreshInterval Refresh interval
     */
    public void setProfileRefreshInterval(int refreshInterval) {
        String currentProfile = config.currentProfile();
        if (currentProfile == null || currentProfile.isEmpty()) return;
        setProfileKey(currentProfile, CONFIG_KEY_REFRESH_INTERVAL, String.valueOf(refreshInterval));
    }

    /**
     * Gets whether to persist obtained items for the current profile.
     *
     * @return Whether to persist obtained items
     */
    public boolean getProfilePersistObtained() {
        String persistObtained = getCurrentProfileKey(CONFIG_KEY_PERSIST_OBTAINED);
        if (persistObtained == null) {
            // Use the default persist obtained setting if not set for this profile
            boolean persist = config.persistObtained();
            setProfilePersistObtained(persist);
            return persist;
        }
        
        return Boolean.parseBoolean(persistObtained);
    }

    /**
     * Sets whether to persist obtained items for the current profile.
     *
     * @param persistObtained Whether to persist obtained items
     */
    public void setProfilePersistObtained(boolean persistObtained) {
        String currentProfile = config.currentProfile();
        if (currentProfile == null || currentProfile.isEmpty()) return;
        setProfileKey(currentProfile, CONFIG_KEY_PERSIST_OBTAINED, String.valueOf(persistObtained));
    }
    
    /**
     * Gets the bingo mode for the current profile.
     *
     * @return Bingo mode
     */
    public BingoConfig.BingoMode getProfileBingoMode() {
        return getProfileBingoMode(config.currentProfile());
    }
    
    /**
     * Gets the bingo mode for a specific profile.
     *
     * @param profileName Name of the profile
     * @return Bingo mode
     */
    public BingoConfig.BingoMode getProfileBingoMode(String profileName) {
        String bingoMode = getProfileKey(profileName, CONFIG_KEY_BINGO_MODE);
        log.debug("[getProfileBingoMode] Read raw value for '{}.{}': {}", profileName, CONFIG_KEY_BINGO_MODE, bingoMode);
        
        if (bingoMode == null) {
            // Use the default bingo mode if not set for this profile
            BingoConfig.BingoMode mode = config.bingoMode();
            setProfileBingoMode(profileName, mode);
            return mode;
        }
        
        try {
            // Try to parse as an enum name (e.g., "SOLO", "TEAM")
            return BingoConfig.BingoMode.valueOf(bingoMode);
        } catch (IllegalArgumentException e) {
            // If valueOf fails, the value might be a toString() result (e.g., "Solo Bingo", "Team Bingo")
            for (BingoConfig.BingoMode mode : BingoConfig.BingoMode.values()) {
                if (mode.toString().equals(bingoMode)) {
                    // If we find a match, update the stored value to use the enum name
                    log.info("[getProfileBingoMode] Converting stored value '{}' to enum name '{}'", 
                            bingoMode, mode.name());
                    setProfileBingoMode(profileName, mode);
                    return mode;
                }
            }
            
            // If we can't match the value, log a warning and return the default
            log.warn("[getProfileBingoMode] Invalid mode value '{}' stored for profile '{}'. Returning default SOLO.",
                    bingoMode, profileName);
            setProfileBingoMode(profileName, BingoConfig.BingoMode.SOLO);
            return BingoConfig.BingoMode.SOLO;
        }
    }
    
    /**
     * Sets the bingo mode for a specific profile.
     *
     * @param profileName Name of the profile
     * @param bingoMode Bingo mode to set
     */
    public void setProfileBingoMode(String profileName, BingoConfig.BingoMode bingoMode) {
        if (profileName == null || profileName.isEmpty()) return;
        setProfileKey(profileName, CONFIG_KEY_BINGO_MODE, bingoMode.name());
    }
    
    /**
     * Gets the team code for the current profile.
     *
     * @return Team code
     */
    public String getProfileTeamCode() {
        return getProfileTeamCode(config.currentProfile());
    }
    
    /**
     * Gets the team code for a specific profile.
     *
     * @param profileName Name of the profile
     * @return Team code
     */
    public String getProfileTeamCode(String profileName) {
        return getProfileKey(profileName, CONFIG_KEY_TEAM_CODE);
    }
    
    /**
     * Sets the team code for the current profile.
     *
     * @param teamCode Team code
     */
    public void setProfileTeamCode(String teamCode) {
        String currentProfile = config.currentProfile();
        if (currentProfile == null || currentProfile.isEmpty()) return;
        setProfileKey(currentProfile, CONFIG_KEY_TEAM_CODE, teamCode);
    }
    
    /**
     * Gets the team name for the current profile.
     *
     * @return Team name
     */
    public String getProfileTeamName() {
        return getProfileTeamName(config.currentProfile());
    }
    
    /**
     * Gets the team name for a specific profile.
     *
     * @param profileName Name of the profile
     * @return Team name
     */
    public String getProfileTeamName(String profileName) {
        return getProfileKey(profileName, CONFIG_KEY_TEAM_NAME);
    }
    
    /**
     * Sets the team name for the current profile.
     *
     * @param teamName Team name
     */
    public void setProfileTeamName(String teamName) {
        String currentProfile = config.currentProfile();
        if (currentProfile == null || currentProfile.isEmpty()) return;
        setProfileKey(currentProfile, CONFIG_KEY_TEAM_NAME, teamName);
    }
    
    /**
     * Gets the Discord webhook URL for the current profile.
     *
     * @return Discord webhook URL
     */
    public String getProfileDiscordWebhook() {
        String webhook = getCurrentProfileKey(CONFIG_KEY_DISCORD_WEBHOOK);
        if (webhook == null) {
            webhook = config.discordWebhook();
        }
        return webhook;
    }
    
    /**
     * Sets the Discord webhook URL for the current profile.
     *
     * @param discordWebhook Discord webhook URL
     */
    public void setProfileDiscordWebhook(String discordWebhook) {
        String currentProfile = config.currentProfile();
        if (currentProfile == null || currentProfile.isEmpty()) return;
        setProfileKey(currentProfile, CONFIG_KEY_DISCORD_WEBHOOK, discordWebhook);
    }
    
    /**
     * Cleans up resources used by the profile manager.
     * This should be called when the plugin is shutting down.
     */
    public void cleanup() {
        try {
            log.info("Cleaning up BingoProfileManager resources");
            
            // Save the current profile before shutting down
            String currentProfile = config.currentProfile();
            if (currentProfile != null && !currentProfile.isEmpty()) {
                saveProfile(currentProfile);
            }
            
            // Unregister all team listeners
            List<String> profiles = getProfiles();
            for (String profileName : profiles) {
                unregisterTeamListeners(profileName);
            }
            
            // Shutdown the executor service
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
            
            log.info("BingoProfileManager resources cleaned up successfully");
        } catch (Exception e) {
            log.error("Error cleaning up BingoProfileManager resources", e);
        }
    }

    /**
     * Direct method to set a configuration value that bypasses any caching or intermediate layers.
     * This is a last-resort method for when normal configuration updates aren't taking effect.
     * 
     * @param group The configuration group
     * @param key The configuration key
     * @param value The value to set
     */
    public void setConfigDirectly(String group, String key, String value) {
        log.info("DIRECT CONFIG WRITE: Setting {}.{} = {}", group, key, value);
        configManager.setConfiguration(group, key, value);
        
        // Force a small delay to ensure config change is processed
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Double-check the change took effect if it's the currentProfile key
        if (group.equals(CONFIG_GROUP) && key.equals("currentProfile")) {
            String currentValue = config.currentProfile();
            log.info("After direct write, currentProfile = {}", currentValue);
            
            // If value still doesn't match, try one more extreme approach
            if (!value.equals(currentValue)) {
                log.warn("Direct config write still failed (got: {}), attempting config force-write", currentValue);
                
                // Try disabling and re-enabling the config setting
                configManager.unsetConfiguration(group, key);
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                
                configManager.setConfiguration(group, key, value);
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                
                // Final check
                currentValue = config.currentProfile();
                log.info("After forced config reset and write, currentProfile = {}", currentValue);
            }
        }
    }
} 