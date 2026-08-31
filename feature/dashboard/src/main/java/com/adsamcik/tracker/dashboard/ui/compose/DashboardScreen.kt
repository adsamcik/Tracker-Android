package com.adsamcik.tracker.dashboard.ui.compose

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.dashboard.data.DashboardWidget
import com.adsamcik.tracker.dashboard.data.ResolvedWidget
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardUiState
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardLiveSessionPresentation
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import com.adsamcik.tracker.dashboard.ui.compose.cards.IdleContent
import com.adsamcik.tracker.dashboard.ui.compose.components.CustomizeDashboardSheet
import com.adsamcik.tracker.dashboard.ui.compose.components.DashboardTopBar
import com.adsamcik.tracker.dashboard.ui.compose.cards.LocationPermissionBanner
import com.adsamcik.tracker.dashboard.ui.compose.components.EmptyStateCard
import com.adsamcik.tracker.dashboard.ui.compose.components.EmptyStateStartHintCard
import com.adsamcik.tracker.dashboard.ui.compose.components.GettingStartedCard
import com.adsamcik.tracker.dashboard.ui.compose.components.MilestoneHapticEffect
import com.adsamcik.tracker.dashboard.ui.compose.components.TrackingPill
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardMode
import com.adsamcik.tracker.dashboard.ui.compose.state.allowsRuntimeMilestones
import com.adsamcik.tracker.dashboard.ui.compose.tracking.TrackingContent
import com.adsamcik.tracker.dashboard.ui.compose.tracking.StepsOnlyTrackingContent
import com.adsamcik.tracker.dashboard.ui.compose.tracking.TrackingHistoryResolutionContent
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
 * - IDLE → [IdleContent] (daily summary, achievements, streaks, trips, exploration)
 * - TRACKING → [TrackingContent] (live stats, map hero, milestones)
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
	val floatingActionBottomPadding = DashboardLayoutDefaults.floatingActionBottomPadding(
		bottomInset = bottomInset,
		isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
		navigationLayout = navigationLayout,
	)

	MilestoneHapticEffect(
		sessionData = state.sessionData,
		isTracking = state.isTracking,
		haptics = haptics,
		milestonesEnabled = state.allowsRuntimeMilestones,
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
	val todayProgressIndex by remember(visibleWidgets) {
		derivedStateOf { visibleWidgets.indexOf(DashboardWidget.TodayProgress) }
	}
	val todayProgressInViewport by remember(idleListState, todayProgressIndex) {
		derivedStateOf {
			if (todayProgressIndex < 0) {
				false
			} else {
				val lazyColumnIndex = todayProgressIndex + 1 // motivational header occupies slot 0
				idleListState.layoutInfo.visibleItemsInfo.any { item ->
					item.key == DashboardWidget.TodayProgress.id || item.index == lazyColumnIndex
				}
			}
		}
	}
	val showPill by remember(
		state.dashboardMode,
		visibleWidgets,
		configuration.orientation,
		todayProgressInViewport,
	) {
		derivedStateOf {
			val todayProgressVisible = visibleWidgets.any { it == DashboardWidget.TodayProgress }
			when (state.dashboardMode) {
				DashboardMode.IDLE -> when {
					!todayProgressVisible -> true
					else -> !todayProgressInViewport
				}
				DashboardMode.TRACKING -> true
				// In EMPTY state the EmptyStateStartHintCard is already the primary CTA — showing
				// the floating pill on top of short static content caused visible overlap with the
				// Quick-start card rows. Rely on the inline hint instead until the user has a
				// TodayProgress widget worth anchoring the pill to.
				DashboardMode.EMPTY -> false
			}
		}
	}

	Box(modifier = modifier.fillMaxSize()) {
		Scaffold(
			modifier = Modifier.fillMaxSize(),
			// Consume no insets here: the outer NavHost/MainRoot reserves the floating nav-bar
			// clearance itself, and we want the dashboard's background surface to extend all the
			// way to the bottom of the screen rather than stopping above the system nav bar.
			contentWindowInsets = WindowInsets(0, 0, 0, 0),
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
		) { paddingValues ->
			Box(
				modifier = Modifier
					.fillMaxSize()
					.padding(paddingValues),
			) {
				when (state.dashboardMode) {
					DashboardMode.EMPTY -> EmptyStateContent(
						bottomClearance = bottomClearance,
						onSettingsClick = onSettingsClick,
						onMapClick = onMapClick,
						onGameClick = onGameClick,
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
						onSessionDetailClick = onSessionDetailClick,
						onToggleTracking = wrappedToggle,
						onRequestPermission = wrappedPermission,
					)
					DashboardMode.TRACKING -> when (
						val presentation = state.liveSessionPresentation
					) {
						is DashboardLiveSessionPresentation.StepsOnly -> {
							val session = state.sessionData
							if (session != null && session.id == presentation.segmentId) {
								StepsOnlyTrackingContent(
									sessionData = session,
									presentation = presentation,
									bottomClearance = bottomClearance,
								)
							} else {
								TrackingHistoryResolutionContent(
									historyUnavailable = false,
									bottomClearance = bottomClearance,
								)
							}
						}
						is DashboardLiveSessionPresentation.Standard -> {
							if (state.sessionData?.id == presentation.segmentId) {
								TrackingContent(
									state = state,
									bottomClearance = bottomClearance,
									onMapClick = onMapClick,
								)
							} else {
								TrackingHistoryResolutionContent(
									historyUnavailable = false,
									bottomClearance = bottomClearance,
								)
							}
						}
						is DashboardLiveSessionPresentation.HistoryUnavailable ->
							TrackingHistoryResolutionContent(
								historyUnavailable = true,
								bottomClearance = bottomClearance,
							)
						DashboardLiveSessionPresentation.Inactive,
						is DashboardLiveSessionPresentation.Resolving ->
							TrackingHistoryResolutionContent(
								historyUnavailable = false,
								bottomClearance = bottomClearance,
							)
					}
				}
			}
		}

		// Sticky tracking pill overlay. We bypass Scaffold's floatingActionButton slot
		// because that slot composes the FAB inside a fixed FabPlacement region whose
		// effective Y depends on safeDrawing insets even when contentWindowInsets=0 —
		// which, combined with the outer floating nav bar, made the pill float halfway
		// up the screen instead of sitting just above the nav bar. Anchoring directly
		// to BottomEnd of the dashboard surface gives us deterministic placement.
		Box(
			modifier = Modifier
				.align(Alignment.BottomEnd)
				.padding(
					end = DashboardLayoutDefaults.FloatingActionEndMargin,
					bottom = floatingActionBottomPadding,
				),
		) {
			TrackingPill(
				visible = showPill,
				isTracking = state.isTracking,
				hasPermission = state.hasLocationPermission,
				sessionData = state.sessionData,
				onToggleTracking = wrappedToggle,
				onRequestPermission = wrappedPermission,
			)
		}

		// Snackbar host — anchored above the floating nav bar so messages don't hide behind it.
		SnackbarHost(
			hostState = snackbarHostState,
			modifier = Modifier
				.align(Alignment.BottomCenter)
				.padding(bottom = floatingActionBottomPadding),
		)
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
	onSettingsClick: () -> Unit,
	onMapClick: () -> Unit,
	onGameClick: (() -> Unit)?,
	onToggleTracking: () -> Unit,
	onRequestPermission: () -> Unit,
	isTracking: Boolean,
	hasPermission: Boolean,
	modifier: Modifier = Modifier,
) {
	val startTrackingAction = if (hasPermission) onToggleTracking else onRequestPermission

	LazyColumn(
		modifier = modifier
			.fillMaxSize()
			.testTag("dashboard_empty_list"),
		contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp),
		verticalArrangement = Arrangement.spacedBy(16.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		if (!hasPermission) {
			item(key = "permission_banner") {
				LocationPermissionBanner(onRequestPermission = onRequestPermission)
			}
		}
		item {
			EmptyStateCard(
				onExploreClick = onMapClick,
				onAchieveClick = onGameClick ?: {},
				onTrackClick = startTrackingAction,
				onPrivacyClick = onSettingsClick,
			)
		}
		// Put the start-tracking CTA right below the hero so the primary action is reachable
		// without scrolling. GettingStartedCard is informational detail that can live below
		// the fold.
		item {
			EmptyStateStartHintCard(
				onStart = startTrackingAction,
				hasPermission = hasPermission,
			)
		}
		item {
			GettingStartedCard(
				onChooseTrackingStyleClick = onSettingsClick,
				onStartFirstTrackClick = startTrackingAction,
				onReviewMapsAndStatsClick = onMapClick,
			)
		}
		item(key = "dashboard_empty_bottom_clearance") {
			Spacer(
				modifier = Modifier
					.height(bottomClearance)
					.testTag("dashboard_empty_bottom_clearance"),
			)
		}
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
