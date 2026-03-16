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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.dashboard.data.DashboardWidget
import com.adsamcik.tracker.dashboard.data.ResolvedWidget
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardUiState
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import com.adsamcik.tracker.dashboard.ui.compose.cards.IdleContent
import com.adsamcik.tracker.dashboard.ui.compose.components.CustomizeDashboardSheet
import com.adsamcik.tracker.dashboard.ui.compose.components.DashboardTopBar
import com.adsamcik.tracker.dashboard.ui.compose.components.EmptyStateCard
import com.adsamcik.tracker.dashboard.ui.compose.components.EmptyStateStartHintCard
import com.adsamcik.tracker.dashboard.ui.compose.components.GettingStartedCard
import com.adsamcik.tracker.dashboard.ui.compose.components.MilestoneHapticEffect
import com.adsamcik.tracker.dashboard.ui.compose.components.TrackingPill
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardMode
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
	visibleWidgets: List<DashboardWidget> = emptyList(),
	resolvedWidgetsForSheet: List<ResolvedWidget> = emptyList(),
	showCustomizeSheet: Boolean = false,
	onSettingsClick: () -> Unit,
	onMapClick: () -> Unit,
	onToggleTracking: (Boolean) -> Unit,
	onRequestPermission: () -> Unit,
	onGameClick: (() -> Unit)?,
	onChallengesClick: (() -> Unit)? = onGameClick,
	onSessionDetailClick: ((Long) -> Unit)?,
	onCustomizeClick: () -> Unit = {},
	onReorderWidgets: (List<String>) -> Unit = {},
	onToggleWidgetVisibility: (String) -> Unit = {},
	onResetLayout: () -> Unit = {},
	onDismissCustomize: () -> Unit = {},
	snackbarHostState: SnackbarHostState,
	modifier: Modifier = Modifier,
){
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
	val showPill by remember(state.dashboardMode, visibleWidgets, configuration.orientation) {
		derivedStateOf {
			val todayProgressVisible = visibleWidgets.any { it == DashboardWidget.TodayProgress }
			// Show pill when the TodayProgressCard (index 1, after motivational at 0)
			// is scrolled off-screen, or when the inline card is hidden entirely.
			when (state.dashboardMode) {
				DashboardMode.IDLE -> when {
					!todayProgressVisible -> true
					configuration.orientation == Configuration.ORIENTATION_LANDSCAPE -> true
					else -> idleListState.layoutInfo.visibleItemsInfo.none {
						it.key == DashboardWidget.TodayProgress.id
					}
				}
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
				onCustomizeClick = if (state.dashboardMode == DashboardMode.IDLE) onCustomizeClick else null,
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
					widgets = visibleWidgets,
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

	// Customize dashboard bottom sheet
	if (showCustomizeSheet) {
		CustomizeDashboardSheet(
			resolvedWidgets = resolvedWidgetsForSheet,
			onReorder = onReorderWidgets,
			onToggleVisibility = onToggleWidgetVisibility,
			onResetToDefault = onResetLayout,
			onDismiss = onDismissCustomize,
		)
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

// ─── Previews ────────────────────────────────────────────────────────

@Preview(name = "Idle – Light", showBackground = true)
@Preview(name = "Idle – Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DashboardScreenIdlePreview() {
	AppTheme {
		DashboardScreen(
			state = DashboardUiState.previewIdle(),
			snackbarHostState = remember { SnackbarHostState() },
			onSettingsClick = {},
			onMapClick = {},
			onToggleTracking = {},
			onRequestPermission = {},
			onGameClick = {},
			onSessionDetailClick = {},
		)
	}
}

@Preview(name = "Tracking – Light", showBackground = true)
@Composable
private fun DashboardScreenTrackingPreview() {
	AppTheme {
		DashboardScreen(
			state = DashboardUiState.previewTracking(),
			snackbarHostState = remember { SnackbarHostState() },
			onSettingsClick = {},
			onMapClick = {},
			onToggleTracking = {},
			onRequestPermission = {},
			onGameClick = {},
			onSessionDetailClick = {},
		)
	}
}

