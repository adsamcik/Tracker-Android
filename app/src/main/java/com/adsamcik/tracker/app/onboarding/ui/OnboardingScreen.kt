package com.adsamcik.tracker.app.onboarding.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.app.onboarding.data.*
import com.adsamcik.tracker.app.onboarding.ui.screens.*

/**
 * Main onboarding screen that orchestrates the different onboarding steps
 */
@Composable
fun OnboardingScreen(
    state: OnboardingState,
    onEvent: (OnboardingEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    // Handle system back to move to previous step when not on the first screen
    BackHandler(enabled = state.currentStep != OnboardingStep.Welcome) {
        onEvent(OnboardingEvent.PreviousStep)
    }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing
    ) { contentPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(contentPadding)
                .imePadding()
        ) {
            // Progress indicator
            OnboardingProgressIndicator(
                currentStep = state.currentStep.stepNumber,
                totalSteps = OnboardingStep.totalSteps,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // Main content area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                when (state.currentStep) {
                OnboardingStep.Welcome -> WelcomeScreen(
                    onGetStarted = { onEvent(OnboardingEvent.NextStep) },
                    onSkip = { onEvent(OnboardingEvent.SkipStep(SkipReason.USER_CHOICE)) }
                )
                
                OnboardingStep.ValueDemo -> ValueDemoScreen(
                    onContinue = { onEvent(OnboardingEvent.NextStep) },
                    onBack = { onEvent(OnboardingEvent.PreviousStep) }
                )
                
                OnboardingStep.Privacy -> PrivacyScreen(
                    preferences = state.userPreferences,
                    onPreferencesUpdate = { prefs -> 
                        onEvent(OnboardingEvent.UpdatePreferences(prefs))
                    },
                    onContinue = { onEvent(OnboardingEvent.NextStep) },
                    onBack = { onEvent(OnboardingEvent.PreviousStep) }
                )
                
                OnboardingStep.WhatToTrack -> WhatToTrackScreen(
                    preferences = state.userPreferences,
                    onPreferencesUpdate = { prefs ->
                        onEvent(OnboardingEvent.UpdatePreferences(prefs))
                    },
                    onContinue = { onEvent(OnboardingEvent.NextStep) },
                    onBack = { onEvent(OnboardingEvent.PreviousStep) }
                )
                
                OnboardingStep.AutoTrackingSetup -> AutoTrackingSetupScreen(
                    preferences = state.userPreferences,
                    onPreferencesUpdate = { prefs ->
                        onEvent(OnboardingEvent.UpdatePreferences(prefs))
                    },
                    onContinue = { onEvent(OnboardingEvent.NextStep) },
                    onBack = { onEvent(OnboardingEvent.PreviousStep) }
                )
                
                OnboardingStep.LocationSetup -> LocationSetupScreen(
                    grantedPermissions = state.grantedPermissions,
                    onPermissionGranted = { permission -> 
                        onEvent(OnboardingEvent.RequestPermission(permission))
                    },
                    onPermissionDenied = { permission, reason -> 
                        onEvent(OnboardingEvent.PermissionDenied(permission, reason))
                    },
                    onContinue = { onEvent(OnboardingEvent.NextStep) },
                    onBack = { onEvent(OnboardingEvent.PreviousStep) },
                    onSkip = { onEvent(OnboardingEvent.SkipStep(SkipReason.PERMISSION_DENIED)) }
                )
                
                OnboardingStep.ActivitySetup -> ActivitySetupScreen(
                    grantedPermissions = state.grantedPermissions,
                    onPermissionGranted = { permission -> 
                        onEvent(OnboardingEvent.RequestPermission(permission))
                    },
                    onPermissionDenied = { permission, reason -> 
                        onEvent(OnboardingEvent.PermissionDenied(permission, reason))
                    },
                    onContinue = { onEvent(OnboardingEvent.NextStep) },
                    onBack = { onEvent(OnboardingEvent.PreviousStep) },
                    onSkip = { onEvent(OnboardingEvent.SkipStep(SkipReason.PERMISSION_DENIED)) }
                )
                
                OnboardingStep.EnhancedFeatures -> EnhancedFeaturesScreen(
                    preferences = state.userPreferences,
                    grantedPermissions = state.grantedPermissions,
                    onPreferencesUpdate = { prefs -> 
                        onEvent(OnboardingEvent.UpdatePreferences(prefs))
                    },
                    onPermissionGranted = { permission -> 
                        onEvent(OnboardingEvent.RequestPermission(permission))
                    },
                    onContinue = { onEvent(OnboardingEvent.NextStep) },
                    onBack = { onEvent(OnboardingEvent.PreviousStep) },
                    onSkip = { onEvent(OnboardingEvent.SkipStep(SkipReason.USER_CHOICE)) }
                )
                
                OnboardingStep.BackgroundLocation -> BackgroundLocationScreen(
                    grantedPermissions = state.grantedPermissions,
                    onPermissionGranted = { permission -> 
                        onEvent(OnboardingEvent.RequestPermission(permission))
                    },
                    onPermissionDenied = { permission, reason -> 
                        onEvent(OnboardingEvent.PermissionDenied(permission, reason))
                    },
                    onContinue = { onEvent(OnboardingEvent.NextStep) },
                    onBack = { onEvent(OnboardingEvent.PreviousStep) },
                    onSkip = { onEvent(OnboardingEvent.SkipStep(SkipReason.PERMISSION_DENIED)) }
                )
                
                OnboardingStep.Success -> SuccessScreen(
                    preferences = state.userPreferences,
                    grantedPermissions = state.grantedPermissions,
                    onComplete = { onEvent(OnboardingEvent.CompleteOnboarding) }
                )
                }
            }
        }
    }
}

@Composable
fun OnboardingProgressIndicator(
    currentStep: Int,
    totalSteps: Int,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        // Progress bar
        LinearProgressIndicator(
            progress = { currentStep.toFloat() / totalSteps },
            modifier = Modifier.fillMaxWidth()
        )
        
        // Step indicator text
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Step $currentStep of $totalSteps",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Text(
                text = "${((currentStep.toFloat() / totalSteps) * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun OnboardingScreenPreview() {
    MaterialTheme {
        OnboardingScreen(
            state = OnboardingState(),
            onEvent = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
fun OnboardingProgressIndicatorPreview() {
    MaterialTheme {
        OnboardingProgressIndicator(
            currentStep = 3,
            totalSteps = 9,
            modifier = Modifier.padding(16.dp)
        )
    }
}
