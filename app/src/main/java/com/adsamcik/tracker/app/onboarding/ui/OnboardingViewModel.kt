package com.adsamcik.tracker.app.onboarding.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.adsamcik.tracker.app.onboarding.data.*
import com.adsamcik.tracker.logger.Reporter

/**
 * ViewModel for managing onboarding state and user interactions
 */
class OnboardingViewModel : ViewModel() {
    
    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()
    
    private val _navigationEvent = MutableStateFlow<NavigationEvent?>(null)
    val navigationEvent: StateFlow<NavigationEvent?> = _navigationEvent.asStateFlow()
    
    init {
        // Track onboarding start
        onEvent(OnboardingEvent.StepStarted(OnboardingStep.Welcome))
    }
    
    fun onEvent(event: OnboardingEvent) {
        viewModelScope.launch {
            try {
                handleEvent(event)
            } catch (e: Exception) {
                Reporter.report(e)
                _state.value = _state.value.copy()
            }
        }
    }
    
    private fun handleEvent(event: OnboardingEvent) {
        when (event) {
            OnboardingEvent.NextStep -> handleNextStep()
            OnboardingEvent.PreviousStep -> handlePreviousStep()
            is OnboardingEvent.GoToStep -> handleGoToStep(event.step)
            is OnboardingEvent.SkipStep -> handleSkipStep(event.reason)
            OnboardingEvent.CompleteOnboarding -> handleCompleteOnboarding()
            
            // User preferences
            is OnboardingEvent.UpdatePreferences -> handleUpdatePreferences(event.preferences)
            is OnboardingEvent.ToggleLocationTracking -> handleToggleLocationTracking(event.enabled)
            is OnboardingEvent.ToggleActivityTracking -> handleToggleActivityTracking(event.enabled)
            is OnboardingEvent.ToggleAutomaticTracking -> handleToggleAutomaticTracking(event.enabled)
            is OnboardingEvent.ToggleWifiTracking -> handleToggleWifiTracking(event.enabled)
            is OnboardingEvent.ToggleNotifications -> handleToggleNotifications(event.enabled)
            is OnboardingEvent.SetTrackingFrequency -> handleSetTrackingFrequency(event.frequency)
            is OnboardingEvent.ToggleCloudBackup -> handleToggleCloudBackup(event.enabled)
            
            // Permissions
            is OnboardingEvent.RequestPermission -> handleRequestPermission(event.permission)
            is OnboardingEvent.PermissionGranted -> handlePermissionGranted(event.permission)
            is OnboardingEvent.PermissionDenied -> handlePermissionDenied(event.permission, event.reason)
            
            // Analytics
            is OnboardingEvent.StepStarted -> handleStepStarted(event.step)
            is OnboardingEvent.StepCompleted -> handleStepCompleted(event.step, event.timeSpent)
            
            // Errors
            is OnboardingEvent.Error -> handleError(event.message, event.throwable)
        }
    }
    
    private fun handleNextStep() {
        val currentState = _state.value
        val nextStep = currentState.getNextStep()
        
        if (nextStep != null) {
            // Mark current step as completed
            val updatedCompletedSteps = currentState.completedSteps + currentState.currentStep
            
            _state.value = currentState.copy(
                currentStep = nextStep,
                completedSteps = updatedCompletedSteps
            )
            
            // Track step transition
            onEvent(OnboardingEvent.StepCompleted(currentState.currentStep, 
                System.currentTimeMillis() - currentState.startTime))
            onEvent(OnboardingEvent.StepStarted(nextStep))
        } else {
            // No next step, complete onboarding
            handleCompleteOnboarding()
        }
    }
    
    private fun handlePreviousStep() {
        val currentState = _state.value
        // Simple previous step logic - can be enhanced based on flow
        val previousStep = when (currentState.currentStep) {
            OnboardingStep.ValueDemo -> OnboardingStep.Welcome
            OnboardingStep.Privacy -> OnboardingStep.ValueDemo
            OnboardingStep.WhatToTrack -> OnboardingStep.Privacy
            OnboardingStep.LocationSetup -> OnboardingStep.WhatToTrack
            OnboardingStep.ActivitySetup -> OnboardingStep.LocationSetup
            OnboardingStep.EnhancedFeatures -> OnboardingStep.ActivitySetup
            OnboardingStep.BackgroundLocation -> OnboardingStep.ActivitySetup
            OnboardingStep.Success -> OnboardingStep.WhatToTrack
            else -> null
        }
        
        if (previousStep != null) {
            _state.value = currentState.copy(currentStep = previousStep)
        }
    }
    
    private fun handleGoToStep(step: OnboardingStep) {
        _state.value = _state.value.copy(currentStep = step)
    }
    
    private fun handleSkipStep(reason: SkipReason) {
        val currentState = _state.value
        val nextStep = currentState.getNextStep()
        
        val updatedSkipReasons = currentState.skipReasons + (currentState.currentStep to reason)
        val updatedSkippedSteps = currentState.skippedSteps + currentState.currentStep
        
        if (nextStep != null) {
            _state.value = currentState.copy(
                currentStep = nextStep,
                skippedSteps = updatedSkippedSteps,
                skipReasons = updatedSkipReasons
            )
        } else {
            handleCompleteOnboarding()
        }
    }
    
    private fun handleCompleteOnboarding() {
        val currentState = _state.value
        _state.value = currentState.copy(
            isCompleted = true,
            completedSteps = currentState.completedSteps + currentState.currentStep
        )
        
        // Navigate to main app
        _navigationEvent.value = NavigationEvent.NavigateToMainApp
    }
    
    private fun handleUpdatePreferences(preferences: UserPreferences) {
        _state.value = _state.value.copy(userPreferences = preferences)
    }
    
    private fun handleToggleLocationTracking(enabled: Boolean) {
        val currentPrefs = _state.value.userPreferences
        _state.value = _state.value.copy(
            userPreferences = currentPrefs.copy(enableLocationTracking = enabled)
        )
    }
    
    private fun handleToggleActivityTracking(enabled: Boolean) {
        val currentPrefs = _state.value.userPreferences
        _state.value = _state.value.copy(
            userPreferences = currentPrefs.copy(enableActivityTracking = enabled)
        )
    }
    
    private fun handleToggleAutomaticTracking(enabled: Boolean) {
        val currentPrefs = _state.value.userPreferences
        _state.value = _state.value.copy(
            userPreferences = currentPrefs.copy(enableAutomaticTracking = enabled)
        )
    }
    
    private fun handleToggleWifiTracking(enabled: Boolean) {
        val currentPrefs = _state.value.userPreferences
        _state.value = _state.value.copy(
            userPreferences = currentPrefs.copy(enableWifiTracking = enabled)
        )
    }
    
    private fun handleToggleNotifications(enabled: Boolean) {
        val currentPrefs = _state.value.userPreferences
        _state.value = _state.value.copy(
            userPreferences = currentPrefs.copy(enableNotifications = enabled)
        )
    }
    
    private fun handleSetTrackingFrequency(frequency: TrackingFrequency) {
        val currentPrefs = _state.value.userPreferences
        _state.value = _state.value.copy(
            userPreferences = currentPrefs.copy(trackingFrequency = frequency)
        )
    }
    
    private fun handleToggleCloudBackup(enabled: Boolean) {
        val currentPrefs = _state.value.userPreferences
        _state.value = _state.value.copy(
            userPreferences = currentPrefs.copy(enableCloudBackup = enabled)
        )
    }
    
    private fun handleRequestPermission(permission: Permission) {
        // Permission request will be handled by the UI layer
        // This is just for state tracking
    }
    
    private fun handlePermissionGranted(permission: Permission) {
        val currentPermissions = _state.value.grantedPermissions
        _state.value = _state.value.copy(
            grantedPermissions = currentPermissions + permission
        )
    }
    
    private fun handlePermissionDenied(permission: Permission, reason: String?) {
        // Handle permission denial - maybe show alternative flow or skip step
        Log.w("Onboarding", "Permission denied: $permission, reason: $reason")
    }
    
    private fun handleStepStarted(step: OnboardingStep) {
        // Analytics/info logging should not crash debug builds
        Log.i("Onboarding", "Onboarding step started: ${step.name}")
    }
    
    private fun handleStepCompleted(step: OnboardingStep, timeSpent: Long) {
        // Analytics/info logging should not crash debug builds
        Log.i("Onboarding", "Onboarding step completed: ${step.name}, time: ${timeSpent}ms")
    }
    
    private fun handleError(message: String, throwable: Throwable?) {
        Reporter.report(throwable ?: Exception(message))
    }
    
    fun clearNavigationEvent() {
        _navigationEvent.value = null
    }
}

/**
 * Navigation events for the onboarding flow
 */
sealed class NavigationEvent {
    object NavigateToMainApp : NavigationEvent()
    data class NavigateToStep(val step: OnboardingStep) : NavigationEvent()
}
