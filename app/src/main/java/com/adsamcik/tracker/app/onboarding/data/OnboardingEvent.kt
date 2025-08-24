package com.adsamcik.tracker.app.onboarding.data

/**
 * Events that can occur during the onboarding process
 */
sealed class OnboardingEvent {
    
    // Navigation events
    object NextStep : OnboardingEvent()
    object PreviousStep : OnboardingEvent()
    data class GoToStep(val step: OnboardingStep) : OnboardingEvent()
    data class SkipStep(val reason: SkipReason) : OnboardingEvent()
    object CompleteOnboarding : OnboardingEvent()
    
    // User preference events
    data class UpdatePreferences(val preferences: UserPreferences) : OnboardingEvent()
    data class ToggleLocationTracking(val enabled: Boolean) : OnboardingEvent()
    data class ToggleActivityTracking(val enabled: Boolean) : OnboardingEvent()
    data class ToggleAutomaticTracking(val enabled: Boolean) : OnboardingEvent()
    data class ToggleWifiTracking(val enabled: Boolean) : OnboardingEvent()
    data class ToggleNotifications(val enabled: Boolean) : OnboardingEvent()
    data class SetTrackingFrequency(val frequency: TrackingFrequency) : OnboardingEvent()
    data class ToggleCloudBackup(val enabled: Boolean) : OnboardingEvent()
    
    // Permission events
    data class RequestPermission(val permission: Permission) : OnboardingEvent()
    data class PermissionGranted(val permission: Permission) : OnboardingEvent()
    data class PermissionDenied(val permission: Permission, val reason: String? = null) : OnboardingEvent()
    
    // Analytics events
    data class StepStarted(val step: OnboardingStep) : OnboardingEvent()
    data class StepCompleted(val step: OnboardingStep, val timeSpent: Long) : OnboardingEvent()
    
    // Error events
    data class Error(val message: String, val throwable: Throwable? = null) : OnboardingEvent()
}
