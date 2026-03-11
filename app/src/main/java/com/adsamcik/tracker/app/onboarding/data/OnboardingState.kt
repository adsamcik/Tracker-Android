@file:Suppress("DEPRECATION")

package com.adsamcik.tracker.app.onboarding.data

import com.adsamcik.tracker.app.onboarding.ui.components.LocationPrecisionMode

/**
 * Reasons why a user might skip an onboarding step
 */
enum class SkipReason {
    USER_CHOICE,        // User explicitly chose to skip
    PERMISSION_DENIED,  // User denied a permission
    ALREADY_CONFIGURED, // Feature is already set up
    NOT_APPLICABLE     // Step not applicable to user's preferences
}

/**
 * User preferences collected during onboarding
 */
data class UserPreferences(
    val enableLocationTracking: Boolean = true,
    val enableActivityTracking: Boolean = true,
    val enableAutomaticTracking: Boolean = true,
    val enableWifiTracking: Boolean = false,
    val enableStepsTracking: Boolean = true,
    val enableCellTracking: Boolean = false,
    val enableNotifications: Boolean = false,
    val trackingFrequency: TrackingFrequency = TrackingFrequency.BALANCED,
    val dataStorageLocal: Boolean = true,
    val enableCloudBackup: Boolean = false,
    // Stage 2 additions
    val autoCleanupOldData: Boolean = false,
    val autoStartTracking: Boolean = false,
    val enableSmartPause: Boolean = true,
    // Auto-tracking settings mapped to real Settings screen options
    val autoTransitionsEnabled: Boolean = true,
    val activityWatcherEnabled: Boolean = true,
    val pauseWhileCharging: Boolean = false,
    // New: expose the same tuning as Settings
    val autoTrackingModeIndex: Int = 1, // 0 Disabled, 1 On foot, 2 In motion
    val trackingMinDistanceMeters: Int? = null, // if null, keep default
    val trackingMinTimeSeconds: Int? = null, // if null, keep default
    // Location precision choice (Phase 2)
    val locationPrecisionMode: LocationPrecisionMode? = null
)

/**
 * Tracking frequency options
 */
enum class TrackingFrequency {
    HIGH_ACCURACY,  // High battery usage, best accuracy
    BALANCED,       // Moderate battery usage, good accuracy
    POWER_SAVER     // Low battery usage, reduced accuracy
}

// Removed TrackingProfile in favor of direct distance/time tuning and auto-tracking mode

/**
 * Permissions that can be granted during onboarding
 */
enum class Permission {
    LOCATION_FOREGROUND,
    LOCATION_BACKGROUND,
    ACTIVITY_RECOGNITION,
    NOTIFICATIONS,
    NEARBY_WIFI_DEVICES
}

/**
 * Overall state of the onboarding process
 */
data class OnboardingState(
    val currentStep: OnboardingStep = OnboardingStep.Welcome,
    val completedSteps: Set<OnboardingStep> = emptySet(),
    val skippedSteps: Set<OnboardingStep> = emptySet(),
    val userPreferences: UserPreferences = UserPreferences(),
    val grantedPermissions: Set<Permission> = emptySet(),
    val skipReasons: Map<OnboardingStep, SkipReason> = emptyMap(),
    // History stack to support proper back navigation across conditional flows
    val stepHistory: List<OnboardingStep> = emptyList(),
    val isCompleted: Boolean = false,
    val startTime: Long = System.currentTimeMillis()
) {
    
    /**
     * Get the next step in the onboarding flow
     */
    fun getNextStep(): OnboardingStep? {
        return when (currentStep) {
            OnboardingStep.Welcome -> OnboardingStep.ValueDemo
            OnboardingStep.ValueDemo -> OnboardingStep.WhatToTrack
            OnboardingStep.Privacy -> OnboardingStep.WhatToTrack // Should not reach here after removal
            OnboardingStep.WhatToTrack -> OnboardingStep.AutoTrackingSetup
            OnboardingStep.AutoTrackingSetup -> {
                // Conditional flow based on user preferences
                if (userPreferences.enableLocationTracking) {
                    OnboardingStep.LocationSetup
                } else if (userPreferences.enableActivityTracking) {
                    OnboardingStep.ActivitySetup
                } else {
                    OnboardingStep.Success
                }
            }
            OnboardingStep.LocationSetup -> {
                // After location setup, go to enhanced features or success
                // Background location is now handled within LocationSetup
                if (userPreferences.enableActivityTracking) {
                    OnboardingStep.ActivitySetup
                } else if (userPreferences.enableWifiTracking) {
                    OnboardingStep.EnhancedFeatures
                } else {
                    OnboardingStep.Success
                }
            }
            OnboardingStep.ActivitySetup -> {
                // Activity setup no longer needs to go to background location
                // since it's handled in location setup
                if (userPreferences.enableWifiTracking) {
                    OnboardingStep.EnhancedFeatures
                } else {
                    OnboardingStep.Success
                }
            }
            OnboardingStep.EnhancedFeatures -> OnboardingStep.Success
            OnboardingStep.BackgroundLocation -> {
                if (userPreferences.enableWifiTracking) {
                    OnboardingStep.EnhancedFeatures
                } else {
                    OnboardingStep.Success
                }
            }
            OnboardingStep.Success -> null // End of flow
        }
    }
    
    /**
     * Check if a step should be shown based on user preferences
     */
    fun shouldShowStep(step: OnboardingStep): Boolean {
        return when (step) {
            OnboardingStep.LocationSetup -> userPreferences.enableLocationTracking
            OnboardingStep.ActivitySetup -> userPreferences.enableActivityTracking
            OnboardingStep.BackgroundLocation -> false // No longer needed as separate step
            OnboardingStep.EnhancedFeatures -> userPreferences.enableWifiTracking
            else -> true // Always show core steps
        }
    }
    
    /**
     * Calculate overall progress percentage
     */
    val progressPercentage: Float
        get() {
            val totalPossibleSteps = OnboardingStep.totalSteps
            val completedCount = completedSteps.size + if (isCompleted) 1 else 0
            return (completedCount.toFloat() / totalPossibleSteps).coerceIn(0f, 1f)
        }
}
