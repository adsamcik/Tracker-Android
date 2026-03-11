package com.adsamcik.tracker.dashboard.ui.compose

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.dashboard.ui.compose.cards.IdleContent
import com.adsamcik.tracker.dashboard.ui.compose.components.DashboardTopBar
import com.adsamcik.tracker.dashboard.ui.compose.components.EmptyStateCard
import com.adsamcik.tracker.dashboard.ui.compose.components.EmptyStateStartHintCard
import com.adsamcik.tracker.dashboard.ui.compose.components.GettingStartedCard
import com.adsamcik.tracker.dashboard.ui.compose.components.MilestoneHapticEffect
import com.adsamcik.tracker.dashboard.ui.compose.components.TrackingPill
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardMode
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardUiState
import com.adsamcik.tracker.dashboard.ui.compose.tracking.TrackingContent
import com.adsamcik.tracker.shared.utils.style.compose.rememberMainNavigationLayout

/**
 * Main scaffold composable orchestrating the dashboard layout.
 *
 * The primary tracking action (play/stop) is integrated into the
 * [TodayProgressCard] via [TrackingActionRing]. When the card scrolls
 * out of view, a compact [TrackingPill] appears at the bottom.
 *
 * Delegates to [DashboardTopBar] and mode-specific content:
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
	onChallengesClick: (() -> Unit)? = onGameClick,
	onSessionDetailClick: ((Long) -> Unit)?,
	snackbarHostState: SnackbarHostState,
	modifier: Modifier = Modifier,
) {
	val haptics = LocalHapticFeedback.current
	val configuration = LocalConfiguration.current
	val navigationLayout = rememberMainNavigationLayout()
	val bottomInset = maxOf(
		WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
		WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding(),
	)
	val bottomClearance = DashboardLayoutDefaults.contentBottomClearance(
		navigationLayout = navigationLayout,
		bottomInset = bottomInset,
		isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
	)
	val floatingActionBottomPadding = DashboardLayoutDefaults.floatingActionBottomPadding(bottomInset)

	MilestoneHapticEffect(
		sessionData = state.sessionData,
		isTracking = state.isTracking,
		haptics = haptics,
	)

	// Haptic-wrapped callbacks
	val wrappedToggle: () -> Unit = {
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
	}

	val wrappedPermission: () -> Unit = {
		haptics.performHapticFeedback(
			androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
		)
		onRequestPermission()
	}

	// Scroll state for idle content — drives pill visibility
	val idleListState = rememberLazyListState()
	val showPill by remember {
		derivedStateOf {
			// Show pill when the TodayProgressCard (index 1, after motivational at 0)
			// is no longer the first visible item — i.e., it has scrolled off-screen.
			// Also show in TRACKING and EMPTY modes since there's no inline ring.
			when (state.dashboardMode) {
				DashboardMode.IDLE -> idleListState.firstVisibleItemIndex > 1
				DashboardMode.TRACKING -> true
				DashboardMode.EMPTY -> true
			}
		}
	}

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
		snackbarHost = { SnackbarHost(snackbarHostState) },
	) { paddingValues ->
		Box(
			modifier = Modifier
				.fillMaxSize()
				.padding(paddingValues),
		) {
			when (state.dashboardMode) {
				DashboardMode.EMPTY -> EmptyStateContent(
					bottomClearance = bottomClearance,
					onToggleTracking = wrappedToggle,
					onRequestPermission = wrappedPermission,
					isTracking = state.isTracking,
					hasPermission = state.hasLocationPermission,
				)
				DashboardMode.IDLE -> IdleContent(
					state = state,
					listState = idleListState,
					bottomClearance = bottomClearance,
					onMapClick = onMapClick,
					onGameClick = onGameClick,
					onChallengesClick = onChallengesClick,
					onSessionDetailClick = onSessionDetailClick,
					onToggleTracking = wrappedToggle,
					onRequestPermission = wrappedPermission,
				)
				DashboardMode.TRACKING -> TrackingContent(
					state = state,
					bottomClearance = bottomClearance,
					onMapClick = onMapClick,
				)
			}

			// Sticky tracking pill — appears when the inline ring scrolls away
			TrackingPill(
				visible = showPill,
				isTracking = state.isTracking,
				hasPermission = state.hasLocationPermission,
				sessionData = state.sessionData,
				onToggleTracking = wrappedToggle,
				onRequestPermission = wrappedPermission,
				modifier = Modifier
					.align(Alignment.BottomCenter)
					.padding(bottom = floatingActionBottomPadding),
			)
		}
	}
}

@Composable
private fun EmptyStateContent(
	bottomClearance: Dp,
	onToggleTracking: () -> Unit,
	onRequestPermission: () -> Unit,
	isTracking: Boolean,
	hasPermission: Boolean,
	modifier: Modifier = Modifier,
) {
	LazyColumn(
		modifier = modifier.fillMaxSize(),
		contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = bottomClearance),
		verticalArrangement = Arrangement.spacedBy(16.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		item { EmptyStateCard() }
		item { GettingStartedCard() }
		item { EmptyStateStartHintCard() }
	}
}

