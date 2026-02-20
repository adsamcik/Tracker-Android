package com.adsamcik.tracker.dashboard.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FabPosition
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.dashboard.ui.compose.cards.IdleContent
import com.adsamcik.tracker.dashboard.ui.compose.components.DashboardTopBar
import com.adsamcik.tracker.dashboard.ui.compose.components.EmptyStateCard
import com.adsamcik.tracker.dashboard.ui.compose.components.MilestoneHapticEffect
import com.adsamcik.tracker.dashboard.ui.compose.components.TrackingFAB
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardMode
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardUiState
import com.adsamcik.tracker.dashboard.ui.compose.tracking.TrackingContent

/**
 * Main scaffold composable orchestrating the dashboard layout.
 *
 * Delegates to [DashboardTopBar], [TrackingFAB], and mode-specific content:
 * - EMPTY → [EmptyStateContent]
 * - IDLE → [IdleContent] (daily summary, challenges, streaks, trips, exploration)
 * - TRACKING → [TrackingContent] (live stats, map hero, milestones, challenges)
 */
@Composable
internal fun DashboardScreen(
	state: DashboardUiState,
	onSettingsClick: () -> Unit,
	onMapClick: () -> Unit,
	onToggleTracking: (Boolean) -> Unit,
	onRequestPermission: () -> Unit,
	onGameClick: (() -> Unit)?,
	onSessionDetailClick: ((Long) -> Unit)?,
	snackbarHostState: SnackbarHostState,
	modifier: Modifier = Modifier,
) {
	val haptics = LocalHapticFeedback.current

	MilestoneHapticEffect(
		sessionData = state.sessionData,
		isTracking = state.isTracking,
		haptics = haptics,
	)

	Scaffold(
		modifier = modifier,
		topBar = {
			DashboardTopBar(
				isTracking = state.isTracking,
				isLocked = state.isLocked,
				policyTier = state.policyTier,
				pointsToday = state.pointsToday,
				onSettingsClick = onSettingsClick,
				onGameClick = onGameClick,
			)
		},
		floatingActionButton = {
			TrackingFAB(
				isTracking = state.isTracking,
				hasPermission = state.hasLocationPermission,
				onToggleTracking = {
					if (state.isTracking) {
						haptics.performHapticFeedback(
							androidx.compose.ui.hapticfeedback.HapticFeedbackType.Reject,
						)
					} else {
						haptics.performHapticFeedback(
							androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm,
						)
					}
					onToggleTracking(!state.isTracking)
				},
				onRequestPermission = {
					haptics.performHapticFeedback(
						androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
					)
					onRequestPermission()
				},
			)
		},
		floatingActionButtonPosition = FabPosition.End,
		snackbarHost = { SnackbarHost(snackbarHostState) },
	) { paddingValues ->
		Box(
			modifier = Modifier
				.fillMaxSize()
				.padding(paddingValues),
		) {
			when (state.dashboardMode) {
				DashboardMode.EMPTY -> EmptyStateContent()
				DashboardMode.IDLE -> IdleContent(
					state = state,
					onMapClick = onMapClick,
					onGameClick = onGameClick,
					onSessionDetailClick = onSessionDetailClick,
				)
				DashboardMode.TRACKING -> TrackingContent(
					state = state,
					onMapClick = onMapClick,
				)
			}
		}
	}
}

@Composable
private fun EmptyStateContent(modifier: Modifier = Modifier) {
	Column(
		modifier = modifier
			.fillMaxSize()
			.padding(16.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		EmptyStateCard()
	}
}


