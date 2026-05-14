package com.adsamcik.tracker.app.onboarding.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.onboarding.data.SetupStep
import com.adsamcik.tracker.app.onboarding.ui.steps.HowToTrackStep
import com.adsamcik.tracker.app.onboarding.ui.steps.WelcomeStep
import com.adsamcik.tracker.app.onboarding.ui.steps.WhatToCollectStep
import com.adsamcik.tracker.shared.utils.style.compose.PrimaryActionButton

/**
 * Composable route for the first-time setup wizard.
 *
 * Hosts three steps with animated transitions, a progress bar, and back navigation.
 */
@Composable
fun SetupRoute(
    onSetupComplete: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SetupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Hardware back button
    BackHandler(enabled = state.currentStep != SetupStep.Welcome) {
        viewModel.goToPreviousStep()
    }

    SetupScaffold(
        title = if (state.currentStep == SetupStep.Welcome) {
            null
        } else {
            stringResource(
                R.string.setup_step_indicator,
                state.currentStep.index + 1,
                SetupStep.totalSteps,
            )
        },
        progress = if (state.currentStep == SetupStep.Welcome) null else state.progress,
        onBack = if (state.currentStep == SetupStep.Welcome) null else viewModel::goToPreviousStep,
        bottomBar = {
            SetupBottomBar(
                step = state.currentStep,
                onNext = { viewModel.goToNextStep() },
                onComplete = {
                    viewModel.completeSetup(onDone = onSetupComplete)
                },
            )
        },
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) { contentPadding ->
        AnimatedContent(
            targetState = state.currentStep,
            transitionSpec = {
                val forward = targetState.index > initialState.index
                if (forward) {
                    slideInHorizontally { it } togetherWith
                        slideOutHorizontally { -it }
                } else {
                    slideInHorizontally { -it } togetherWith
                        slideOutHorizontally { it }
                }
            },
            label = "setup_step_transition",
            modifier = Modifier.fillMaxSize(),
        ) { step ->
            when (step) {
                SetupStep.Welcome -> WelcomeStep(
                    contentPadding = contentPadding,
                )

                SetupStep.HowToTrack -> HowToTrackStep(
                    autoTrackingMode = state.autoTrackingMode,
                    trackingPreset = state.trackingPreset,
                    onAutoTrackingModeChange = viewModel::setAutoTrackingMode,
                    onPresetChange = viewModel::setTrackingPreset,
                    contentPadding = contentPadding,
                )

                SetupStep.WhatToCollect -> WhatToCollectStep(
                    state = state,
                    onLocationEnabledChange = viewModel::setLocationEnabled,
                    onLocationPrecisionChange = viewModel::setLocationPrecision,
                    onActivityEnabledChange = viewModel::setActivityEnabled,
                    onStepsEnabledChange = viewModel::setStepsEnabled,
                    onWifiEnabledChange = viewModel::setWifiEnabled,
                    onCellEnabledChange = viewModel::setCellEnabled,
                    onLocationPermissionResult = viewModel::onLocationPermissionResult,
                    onBackgroundLocationResult = viewModel::onBackgroundLocationResult,
                    onActivityPermissionResult = viewModel::onActivityPermissionResult,
                    onNotificationPermissionResult = viewModel::onNotificationPermissionResult,
                    onPermissionStateHydrated = viewModel::onPermissionStateHydrated,
                    contentPadding = contentPadding,
                )
            }
        }
    }
}

@Composable
private fun SetupBottomBar(
    step: SetupStep,
    onNext: () -> Unit,
    onComplete: () -> Unit,
) {
    val (text, action, tag) = when (step) {
        SetupStep.Welcome -> Triple(
            stringResource(R.string.onboarding_get_started),
            onNext,
            "setup_cta_get_started",
        )

        SetupStep.HowToTrack -> Triple(
            stringResource(R.string.button_continue),
            onNext,
            "setup_cta_how_to_track",
        )

        SetupStep.WhatToCollect -> Triple(
            stringResource(R.string.setup_start_exploring),
            onComplete,
            "setup_cta_complete",
        )
    }

    PrimaryActionButton(
        text = text,
        onClick = action,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag),
    )
}
