package com.adsamcik.tracker.dashboard.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FabPosition
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.dashboard.R
import com.adsamcik.tracker.dashboard.ui.compose.components.DashboardTopBar
import com.adsamcik.tracker.dashboard.ui.compose.components.EmptyStateCard
import com.adsamcik.tracker.dashboard.ui.compose.components.MilestoneHapticEffect
import com.adsamcik.tracker.dashboard.ui.compose.components.TrackingFAB
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardMode
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardUiState

/**
 * Main scaffold composable orchestrating the dashboard layout.
 *
 * Delegates to [DashboardTopBar], [TrackingFAB], and mode-specific content:
 * - EMPTY → [EmptyStateContent]
 * - IDLE → [IdleContent] (placeholder for Phase 4)
 * - TRACKING → [TrackingContent] (placeholder for Phase 3)
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
		floatingActionButtonPosition = FabPosition.Center,
		snackbarHost = { SnackbarHost(snackbarHostState) },
	) { paddingValues ->
		Box(
			modifier = Modifier
				.fillMaxSize()
				.padding(paddingValues),
		) {
			when (state.dashboardMode) {
				DashboardMode.EMPTY -> EmptyStateContent()
				DashboardMode.IDLE -> IdleContent()
				DashboardMode.TRACKING -> TrackingContent()
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

@Composable
private fun IdleContent(modifier: Modifier = Modifier) {
	Box(
		modifier = modifier.fillMaxSize(),
		contentAlignment = Alignment.Center,
	) {
		Text(
			text = stringResource(R.string.dashboard_idle_placeholder),
			style = MaterialTheme.typography.bodyLarge,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}

@Composable
private fun TrackingContent(modifier: Modifier = Modifier) {
	Box(
		modifier = modifier.fillMaxSize(),
		contentAlignment = Alignment.Center,
	) {
		Text(
			text = stringResource(R.string.dashboard_tracking_placeholder),
			style = MaterialTheme.typography.bodyLarge,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}
