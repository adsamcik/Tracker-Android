package com.adsamcik.tracker.app.onboarding.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.onboarding.data.SetupStep
import com.adsamcik.tracker.app.onboarding.ui.steps.HowToTrackStep
import com.adsamcik.tracker.app.onboarding.ui.steps.BackgroundAccessStep
import com.adsamcik.tracker.app.onboarding.ui.steps.OnlineMapTilesStep
import com.adsamcik.tracker.app.onboarding.ui.steps.WelcomeStep
import com.adsamcik.tracker.app.onboarding.ui.steps.WhatToCollectStep
import com.adsamcik.tracker.shared.utils.style.compose.ridgelineSettle

/**
 * Composable route for the first-time setup wizard.
 *
 * Hosts three steps with animated transitions, a progress bar, and back navigation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupRoute(
    onSetupComplete: () -> Unit,
    modifier: Modifier = Modifier,
    showOnboardingReadError: Boolean = false,
    viewModel: SetupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshPermissionCapabilities()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Hardware back button
    BackHandler(enabled = state.currentStep != SetupStep.Welcome) {
        viewModel.goToPreviousStep()
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            if (state.currentStep != SetupStep.Welcome) {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            text = stringResource(
                                R.string.setup_step_indicator,
                                state.currentStep.index + 1,
                                SetupStep.totalSteps,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.goToPreviousStep() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_navigate_back),
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                    ),
                )
            }
        },
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            // Progress indicator (hidden on Welcome)
            if (state.currentStep != SetupStep.Welcome) {
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    strokeCap = StrokeCap.Round,
                )

                Spacer(modifier = Modifier.height(8.dp))
            }

            // Animated step content
            val stepTransitionSpec = ridgelineSettle<IntOffset>()
            AnimatedContent(
                targetState = state.currentStep,
                transitionSpec = {
                    val forward = targetState.index > initialState.index
                    if (forward) {
                        slideInHorizontally(animationSpec = stepTransitionSpec) { it } togetherWith
                            slideOutHorizontally(animationSpec = stepTransitionSpec) { -it }
                    } else {
                        slideInHorizontally(animationSpec = stepTransitionSpec) { -it } togetherWith
                            slideOutHorizontally(animationSpec = stepTransitionSpec) { it }
                    }
                },
                label = "setup_step_transition",
                modifier = Modifier.weight(1f),
            ) { step ->
                when (step) {
                    SetupStep.Welcome -> WelcomeStep(
                        onGetStarted = { viewModel.goToNextStep() },
                        showOnboardingReadError = showOnboardingReadError,
                    )

                    SetupStep.HowToTrack -> HowToTrackStep(
                        autoTrackingMode = state.autoTrackingMode,
                        trackingPreset = state.trackingPreset,
                        onAutoTrackingModeChange = viewModel::setAutoTrackingMode,
                        onPresetChange = viewModel::setTrackingPreset,
                        onContinue = { viewModel.goToNextStep() },
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
                        onActivityPermissionResult = viewModel::onActivityPermissionResult,
                        onNotificationPermissionResult = viewModel::onNotificationPermissionResult,
                        onWifiPermissionResult = viewModel::onWifiPermissionResult,
                        onCellPermissionResult = viewModel::onCellPermissionResult,
                        onComplete = { viewModel.goToNextStep() },
                    )

                    SetupStep.BackgroundAccess -> BackgroundAccessStep(
						state = state,
						onBackgroundLocationResult = viewModel::onBackgroundLocationResult,
						onDeclineBackgroundLocation = viewModel::declineBackgroundLocation,
                        onContinue = { viewModel.goToNextStep() },
                    )

                    SetupStep.OnlineMapTiles -> OnlineMapTilesStep(
                        enabled = state.onlineMapTilesEnabled,
                        onEnabledChange = viewModel::setOnlineMapTilesEnabled,
                        onComplete = {
                            viewModel.completeSetup(onDone = onSetupComplete)
                        },
                    )
                }
            }
        }
    }
}
