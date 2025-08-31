package com.adsamcik.tracker.app.onboarding.data

/**
 * Represents the different steps in the onboarding flow
 */
sealed class OnboardingStep {
    object Welcome : OnboardingStep()
    object ValueDemo : OnboardingStep()
    object Privacy : OnboardingStep()
    object WhatToTrack : OnboardingStep()
    object AutoTrackingSetup : OnboardingStep()
    object LocationSetup : OnboardingStep()
    object ActivitySetup : OnboardingStep()
    object EnhancedFeatures : OnboardingStep()
    object BackgroundLocation : OnboardingStep()
    object Success : OnboardingStep()
    
    /**
     * Get display name for analytics and debugging
     */
    val name: String
        get() = when (this) {
            Welcome -> "welcome"
            ValueDemo -> "value_demo"
            Privacy -> "privacy"
            WhatToTrack -> "what_to_track"
            AutoTrackingSetup -> "auto_tracking_setup"
            LocationSetup -> "location_setup"
            ActivitySetup -> "activity_setup"
            EnhancedFeatures -> "enhanced_features"
            BackgroundLocation -> "background_location"
            Success -> "success"
        }
    
    /**
     * Get step number for progress indicator
     */
    val stepNumber: Int
        get() = when (this) {
            Welcome -> 1
            ValueDemo -> 2
            Privacy -> 3 // Deprecated, should not be used
            WhatToTrack -> 3
            AutoTrackingSetup -> 4
            LocationSetup -> 5
            ActivitySetup -> 6
            EnhancedFeatures -> 7
            BackgroundLocation -> 8 // Deprecated, handled within LocationSetup
            Success -> 8
        }
    
    companion object {
        val totalSteps = 8 // Reduced by 1 since BackgroundLocation is handled within LocationSetup
        
        fun fromName(name: String): OnboardingStep? = when (name) {
            "welcome" -> Welcome
            "value_demo" -> ValueDemo
            "privacy" -> Privacy
            "basic_preferences" -> WhatToTrack
            "what_to_track" -> WhatToTrack
            "auto_tracking_setup" -> AutoTrackingSetup
            "location_setup" -> LocationSetup
            "activity_setup" -> ActivitySetup
            "enhanced_features" -> EnhancedFeatures
            "background_location" -> BackgroundLocation
            "success" -> Success
            else -> null
        }
    }
}
