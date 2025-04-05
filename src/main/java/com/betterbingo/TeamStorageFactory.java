package com.betterbingo;

import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Factory for creating team storage strategies.
 * Currently only supports Firebase storage.
 */
@Slf4j
@Singleton
public class TeamStorageFactory {
    private final FirebaseTeamStorage firebaseStorage;
    
    @Inject
    public TeamStorageFactory(FirebaseTeamStorage firebaseStorage) {
        this.firebaseStorage = firebaseStorage;
    }
    
    /**
     * Gets the team storage strategy.
     *
     * @return The Firebase storage strategy
     */
    public TeamStorageStrategy getStorageStrategy() {
        return firebaseStorage;
    }
    
    /**
     * Gets the storage strategy for a team.
     * 
     * @param teamCode The team code (not used in current implementation)
     * @return The Firebase storage strategy
     */
    public TeamStorageStrategy getStorageStrategyForTeam(String teamCode) {
        return firebaseStorage;
    }
} 