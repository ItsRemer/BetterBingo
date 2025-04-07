package com.betterbingo;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.inject.Inject;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.api.Client;

import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.ScheduledExecutorService;

import javax.swing.SwingUtilities;

import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.function.BiFunction;
import java.util.function.Supplier;
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
    @Inject
    private Gson gson;
    
    /**
     * A lock to ensure thread safety when accessing profiles.
     */
    private ReentrantLock profileLock;

    private final ScheduledExecutorService executor;

    @Inject
    public BingoProfileManager(ConfigManager configManager, BingoConfig config, Client client, BingoTeamService teamService, BingoPlugin plugin, ScheduledExecutorService executor) {
        this.configManager = configManager;
        this.config = config;
        this.client = client;
        this.teamService = teamService;
        this.plugin = plugin;
        this.profileLock = new ReentrantLock();
        this.executor = executor;
        
        // Define the configuration group
        configManager.getConfiguration(CONFIG_GROUP, "version", String.class);
        
        // Ensure we have a current profile
        if (config.currentProfile() == null || config.currentProfile().isEmpty()) {
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
            
            // Use the injected Gson instance with lenient settings
            try {
                // First try parsing as a string array
                Type listType = new TypeToken<ArrayList<String>>() {}.getType();
                List<String> profiles = gson.fromJson(profilesJson, listType);
                List<String> result = profiles != null ? profiles : new ArrayList<>();
                return result;
            } catch (JsonSyntaxException e) {
                // If that fails, try parsing as a single string
                try {
                    String singleProfile = gson.fromJson(profilesJson, String.class);
                    if (singleProfile != null && !singleProfile.isEmpty()) {
                        List<String> result = new ArrayList<>();
                        result.add(singleProfile);
                        return result;
                    }
                } catch (JsonSyntaxException e2) {
                    // Failed to parse as single string
                }
                
                // If all parsing fails, try a raw approach
                if (profilesJson.startsWith("\"") && profilesJson.endsWith("\"")) {
                    // Might be a quoted string
                    String unquoted = profilesJson.substring(1, profilesJson.length() - 1);
                    List<String> result = new ArrayList<>();
                    result.add(unquoted);
                    return result;
                }
                
                // Last resort: treat the entire string as a profile name
                List<String> result = new ArrayList<>();
                result.add(profilesJson);
                return result;
            }
        } catch (Exception e) {
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
            // Use the injected Gson instance
            // Ensure we serialize as a JSON array even for a single profile
            String profilesJson = gson.toJson(profiles);
            
            // Save the profiles to the configuration
            configManager.setConfiguration(CONFIG_GROUP, PROFILES_KEY, profilesJson);
            
            return true;
        } catch (Exception e) {
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
                return false;
            }
            
            // Initialize the profile with default settings
            initializeProfileSettings(profileName);
            
            return true;
        } catch (Exception e) {
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
                return false; // Profile doesn't exist
            }

            // Check if it's a team profile and delete from backend if so
            BingoConfig.BingoMode mode = getProfileBingoMode(profileName);
            if (mode == BingoConfig.BingoMode.TEAM) {
                String teamCode = getProfileTeamCode(profileName);
                if (teamCode != null && !teamCode.isEmpty()) {
                    // Check if the current user is the team leader before allowing backend deletion
                    String leaderHashStr = getProfileKey(profileName, "leaderAccountHash");
                    long currentUserHash = client.getAccountHash();
                    String currentUserHashStr = String.valueOf(currentUserHash);
                    
                    // Only allow team deletion if current user is the team leader
                    // This is a security measure to prevent unauthorized deletion
                    // Need final reference for lambda
                    if (currentUserHash != -1 && currentUserHashStr.equals(leaderHashStr)) {
                        // Current user is the leader, proceed with backend deletion
                        teamService.deleteTeam(teamCode);
                    }
                }
            }
            
            // Continue with normal profile deletion regardless of backend result
            profiles.remove(profileName);
            saveProfiles(profiles);
            
            // If the deleted profile is the current one, switch to another (preferably Default)
            if (config.currentProfile().equals(profileName)) {
                String newProfile = profiles.contains(DEFAULT_PROFILE) ? DEFAULT_PROFILE : 
                    (profiles.isEmpty() ? DEFAULT_PROFILE : profiles.get(0));
                
                // If we're switching to a profile that doesn't exist yet, create it
                if (!profiles.contains(newProfile)) {
                    profiles.add(newProfile);
                    saveProfiles(profiles);
                    initializeProfileSettings(newProfile);
                }
                
                // Switch to the new profile
                switchProfile(newProfile);
            }
            
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            profileLock.unlock();
        }
    }

    /**
     * Switches to a different profile.
     *
     * @param profileKey The profile to switch to
     */
    public void switchProfile(String profileKey) {
        if (!isValidProfile(profileKey) || isSameProfile(profileKey)) {
            return;
        }

        try {
            final String currentProfile = config.currentProfile();
            unregisterCurrentTeamIfNeeded(currentProfile);

            // Update config with the new profile
            setConfigDirectly(CONFIG_GROUP, "currentProfile", profileKey);

            // Verify that the config actually switched
            final String actualProfile = config.currentProfile();
            if (!profileKey.equals(actualProfile)) {
                throw new RuntimeException("CRITICAL: Profile switch failed despite direct write. "
                        + "Wanted: " + profileKey + ", Got: " + actualProfile);
            }

            // Register the new team if this profile is in TEAM mode
            registerNewTeamIfNeeded(actualProfile);

        } catch (Exception e) {
            // Optionally log or rethrow as needed
        }
    }

    private boolean isValidProfile(String profileKey) {
        return getProfiles().contains(profileKey);
    }

    private boolean isSameProfile(String profileKey) {
        return profileKey.equals(config.currentProfile());
    }

    private void unregisterCurrentTeamIfNeeded(String currentProfile) {
        if (isTeamProfile(currentProfile)) {
            String teamCode = getProfileTeamCode(currentProfile);
            if (teamCode != null && !teamCode.isEmpty()) {
                teamService.unregisterTeamListener(teamCode);
            }
        }
    }

    private void registerNewTeamIfNeeded(String newProfile) {
        if (!isTeamProfile(newProfile)) {
            return;
        }

        // Load items from cache if any, to ensure immediate display
        final String newTeamCode = getProfileTeamCode(newProfile);
        if (newTeamCode == null || newTeamCode.isEmpty()) {
            return;
        }

        List<BingoItem> cachedItems = teamService.getTeamCachedItems(newTeamCode);
        if (cachedItems != null && !cachedItems.isEmpty()) {
            // Handle or display cached items if necessary
        }

        // Register the listeners
        registerTeamListeners(newProfile);

        // If remote-based, trigger a refresh to ensure DB is up-to-date
        if (isRemoteSource(newProfile)) {
            forceTeamRefreshAsync(newTeamCode);
        }
    }

    private boolean isTeamProfile(String profile) {
        return getProfileBingoMode(profile) == BingoConfig.BingoMode.TEAM;
    }

    private boolean isRemoteSource(String profile) {
        String sourceTypeStr = getProfileKey(profile, CONFIG_KEY_ITEM_SOURCE_TYPE);
        BingoConfig.ItemSourceType sourceType = BingoConfig.ItemSourceType.valueOf(sourceTypeStr);

        if (sourceType != BingoConfig.ItemSourceType.REMOTE) {
            return false;
        }

        String remoteUrl = getProfileKey(profile, CONFIG_KEY_REMOTE_URL);
        return remoteUrl != null && !remoteUrl.isEmpty();
    }

    private void forceTeamRefreshAsync(String teamCode) {
        executor.submit(() -> {
            try {
                CompletableFuture<Boolean> refreshFuture = teamService.refreshTeamItems(teamCode);
                // Wait for the refresh to complete (with timeout)
                boolean success = refreshFuture.get(15, TimeUnit.SECONDS);
                if (success) {
                    log.debug("Successfully refreshed team items for team {}", teamCode);
                }
            } catch (Exception e) {
                log.debug("Error refreshing team items: {}", e.getMessage());
            }
        });
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
                    teamService.unregisterTeamListener(teamCode);
                }
            }
        } catch (Exception e) {
            log.debug("Error in unregisterTeamListeners for profile {}: {}", profileName, e.getMessage());
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
                    teamService.registerTeamListener(teamCode, plugin::updateItemsFromFirebase)
                        .exceptionally(ex -> {
                            final boolean isTeamNotFound = isIsTeamNotFound(ex);

                            if (isTeamNotFound) {
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
                                        deleteProfile(finalProfileName);
                                    }
                                });
                            }
                            
                            return null;
                        });
                }
            }
        } catch (Exception e) {
            log.debug("Error in registerTeamListeners for profile {}: {}", profileName, e.getMessage());
        }
    }

    private static boolean isIsTeamNotFound(Throwable ex) {
        String errorMessage = ex.getMessage();
        Throwable cause = ex.getCause();

        return (errorMessage != null && errorMessage.contains("404")) ||
        // Check the cause messages for 404
        (cause != null && cause.getMessage() != null && cause.getMessage().contains("404")) ||
        // Message checks for common team not found messages
        (errorMessage != null && (
            errorMessage.contains("Team not found") ||
            errorMessage.contains("no longer exists") ||
            errorMessage.contains("Failed to get team data") ||
            errorMessage.contains("Team data is null")
        ));
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
            
            return true;
        } catch (Exception e) {
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
                    // Set the team code in the profile config
                    if (teamCode != null && !teamCode.isEmpty()) {
                        setProfileKey(profileName, CONFIG_KEY_TEAM_CODE, teamCode);
                    } else {
                        throw new RuntimeException("Failed to create team: received null team code");
                    }
                    
                    // Set the bingo mode to TEAM
                    setProfileBingoMode(profileName, BingoConfig.BingoMode.TEAM);
                    
                    // Set the profile as active
                    config.setCurrentProfile(profileName);
                    
                    // Register team listeners
                    registerTeamListeners(profileName);
                    
                    return teamCode;
                })
                .exceptionally(ex -> {
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
                        }
                        
                        // Proceed with immediate local deletion of profile
                        deleteProfile(profileName);
                    } catch (Exception e) {
                        log.debug("Error deleting profile {}: {}", profileName, e.getMessage());
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

        // Join the team on the backend
        return teamService.joinTeam(teamCode)
                .thenCompose(success -> {
                    if (success) {
                        // Fetch team data to get settings
                        return teamService.getTeamData(teamCode).thenCompose(teamData -> {
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
                            } finally {
                                profileLock.unlock();
                            }

                            // Switch to the newly configured profile
                            switchProfile(profileName);
                            
                            // Register listener after switching
                            teamService.registerTeamListener(teamCode, plugin::updateItemsFromFirebase);
                            
                            return CompletableFuture.completedFuture(true);
                        });
                    } else {
                        // If joining failed, delete the profile
                        deleteProfile(profileName);
                        return CompletableFuture.completedFuture(false);
                    }
                })
                .exceptionally(ex -> {
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
            // Copy settings from the default config or current profile if available
            String currentProfile = config.currentProfile();
            final BiFunction<String, Supplier<String>, String> getConfigValue = getCurrentProfileFunction(profileName, currentProfile);

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
        } finally {
            profileLock.unlock();
        }
    }

    @NonNull
    private BiFunction<String, Supplier<String>, String> getCurrentProfileFunction(String profileName, String currentProfile) {
        boolean useCurrent = !currentProfile.equals(profileName) && getProfiles().contains(currentProfile);

        // Helper lambda to get config value, falling back to default
        return (key, defaultValueSupplier) -> {
            String value = useCurrent ? getProfileKey(currentProfile, key) : null;
            return value != null ? value : defaultValueSupplier.get();
        };
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
            // If valueOf fails, the value might be a toString() result (e.g., "Manual Input", "Remote URL")
            for (BingoConfig.ItemSourceType sourceType : BingoConfig.ItemSourceType.values()) {
                if (sourceType.toString().equals(itemSourceType)) {
                    // If we find a match, update the stored value to use the enum name
                    setProfileItemSourceType(sourceType);
                    return sourceType;
                }
            }
            
            // If the stored value is invalid and no match found, use the default
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
        setProfileKey(currentProfile, CONFIG_KEY_ITEM_SOURCE_TYPE, itemSourceType.name());
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
                    setProfileBingoMode(profileName, mode);
                    return mode;
                }
            }
            
            // If we can't match the value, log a warning and return the default
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
     * Direct method to set a configuration value that bypasses any caching or intermediate layers.
     * This is a last-resort method for when normal configuration updates aren't taking effect.
     * 
     * @param group The configuration group
     * @param key The configuration key
     * @param value The value to set
     */
    public void setConfigDirectly(String group, String key, String value) {
        // The ConfigManager doesn't always reliably update certain values (especially currentProfile)
        // This direct approach ensures that our configuration updates are immediate and reliable
        
        // First try direct configuration set
        configManager.setConfiguration(group, key, value);
        
        // Check if the update has been applied
        String currentValue = configManager.getConfiguration(group, key);
        if (value.equals(currentValue)) {
            // Success! The value has been updated.
            return;
        }
        
        // If the direct update failed, try a more forceful approach
        // Clear the ConfigManager's cache for this value
        configManager.unsetConfiguration(group, key);
        
        // Try another direct configuration set
        configManager.setConfiguration(group, key, value);
        
        // Verify one more time
        currentValue = configManager.getConfiguration(group, key);
        if (!value.equals(currentValue)) {
            // If it still failed, throw a runtime exception to indicate a critical error
            throw new RuntimeException("Failed to update configuration value after multiple attempts: " + 
                group + "." + key + " = " + value + " (got: " + currentValue + ")");
        }
    }
} 