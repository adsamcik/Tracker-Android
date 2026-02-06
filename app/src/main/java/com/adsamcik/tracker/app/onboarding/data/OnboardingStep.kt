@file:Suppress("DEPRECATION")

package com.adsamcik.tracker.app.onboarding.data

/**
 * Represents the streamlined onboarding flow steps.
 * Simplified from 8-step wizard to single-screen welcome + completion.
 * 
 * Legacy multi-step flow removed per Apple-style philosophy:
 * - Smart defaults applied immediately (no configuration required upfront)
 * - Permissions requested contextually when needed (not during onboarding)
 * - Time-to-first-track: <30 seconds
 */
sealed class OnboardingStep {
    object Welcome : OnboardingStep()
    object Success : OnboardingStep()
    
    // Legacy steps kept for backward compatibility (deprecated, not shown in UI)
    @Deprecated("Use streamlined single-screen onboarding", ReplaceWith("Welcome"))
    object ValueDemo : OnboardingStep()
    @Deprecated("No longer shown - privacy is implicit", ReplaceWith("Welcome"))
    object Privacy : OnboardingStep()
    @Deprecated("Smart defaults applied automatically", ReplaceWith("Welcome"))
    object WhatToTrack : OnboardingStep()
    @Deprecated("Smart defaults applied automatically", ReplaceWith("Welcome"))
    object AutoTrackingSetup : OnboardingStep()
    @Deprecated("Permissions requested contextually", ReplaceWith("Welcome"))
    object LocationSetup : OnboardingStep()
    @Deprecated("Permissions requested contextually", ReplaceWith("Welcome"))
    object ActivitySetup : OnboardingStep()
    @Deprecated("Smart defaults applied automatically", ReplaceWith("Welcome"))
    object EnhancedFeatures : OnboardingStep()
    @Deprecated("Permissions requested contextually after sessions", ReplaceWith("Welcome"))
    object BackgroundLocation : OnboardingStep()
    
    /**
     * Get display name for analytics and debugging
     */
    val name: String
        get() = when (this) {
            Welcome -> "welcome"
            Success -> "success"
            // Legacy names for analytics continuity
            ValueDemo -> "value_demo_deprecated"
            Privacy -> "privacy_deprecated"
            WhatToTrack -> "what_to_track_deprecated"
            AutoTrackingSetup -> "auto_tracking_deprecated"
            LocationSetup -> "location_deprecated"
            ActivitySetup -> "activity_deprecated"
            EnhancedFeatures -> "enhanced_deprecated"
            BackgroundLocation -> "background_deprecated"
        }
    
    /**
     * Get step number for progress indicator (streamlined flow)
     */
    val stepNumber: Int
        get() = when (this) {
            Welcome -> 1
            Success -> 1 // Completion is immediate
            // Legacy step numbers (not used in new UI)
            else -> 1
        }
    
    companion object {
        /**
         * Total steps in streamlined onboarding flow
         */
        const val totalSteps = 1
        
        fun fromName(name: String): OnboardingStep? = when (name) {
            "welcome" -> Welcome
            "success" -> Success
            // Legacy step mappings for analytics continuity
            "value_demo" -> ValueDemo
            "privacy" -> Privacy
            "basic_preferences", "what_to_track" -> WhatToTrack
            "auto_tracking_setup" -> AutoTrackingSetup
            "location_setup" -> LocationSetup
            "activity_setup" -> ActivitySetup
            "enhanced_features" -> EnhancedFeatures
            "background_location" -> BackgroundLocation
            else -> null
        }
    }
}
