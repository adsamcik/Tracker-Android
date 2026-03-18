package com.adsamcik.tracker.dashboard.ui.compose

import android.Manifest
import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.adsamcik.tracker.dashboard.R
import com.adsamcik.tracker.dashboard.ui.DashboardViewModel
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardMode
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardUiState
import com.adsamcik.tracker.dashboard.ui.compose.state.GoalProgressState
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.di.DailySummary
import com.adsamcik.tracker.shared.base.permission.ContextualPermissionRequest
import com.adsamcik.tracker.shared.base.permission.PermissionDeniedSnackbar
import com.adsamcik.tracker.shared.base.permission.PermissionType
import com.adsamcik.tracker.shared.preferences.PreferenceKeys
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.tracker.api.TrackerServiceApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Entry point composable for the Dashboard tab.
 *
 * Observes tracker state via CompositionLocals and builds [DashboardUiState].
 * Handles contextual permission requests following the same pattern as TrackerRoute.
 */
@Composable
fun DashboardRoute(
	onOpenSettings: () -> Unit = {},
	onOpenMap: () -> Unit = {},
	onOpenGame: (() -> Unit)? = null,
	onOpenChallenges: (() -> Unit)? = onOpenGame,
	onSessionDetailClick: ((Long) -> Unit)? = null,
	contentPadding: PaddingValues = PaddingValues(),
) {
	val context = LocalContext.current
	val viewModel: DashboardViewModel = hiltViewModel()

	// Dependencies via ViewModel (Hilt-injected)
	val controller = viewModel.trackerController
	val lockManager = viewModel.lockManager
	val dailyPointsProvider = viewModel.dailyPointsProvider
	val goalProgressProvider = viewModel.goalProgressProvider
	val activeChallengesProvider = viewModel.activeChallengesProvider

	// Permission state from ViewModel
	val hasLocationPermission by viewModel.hasLocationPermission.collectAsState()
	val showLocationPermissionRequest by viewModel.showLocationPermissionRequest.collectAsState()
	val permissionDenied by viewModel.permissionDenied.collectAsState()
	val snackbarHostState = remember { SnackbarHostState() }
	val coroutineScope = rememberCoroutineScope()
	var userRequestedStop by remember { mutableStateOf(false) }

	// Observe tracking state
	val isTracking by controller.isServiceRunningFlow.collectAsState()
	val sessionInfo by controller.sessionInfoFlow.collectAsState()
	val isLocked by lockManager.isLockedFlow.collectAsState()
	val policyTier by controller.policyTierFlow.collectAsState()
	val sessionData by controller.sessionFlow.collectAsState()
	val collectionData by controller.collectionDataFlow.collectAsState()
	val pathPoints by controller.pathPointsFlow.collectAsState()
	val lastSessionData by controller.lastSessionFlow.collectAsState()
	val lastPathPoints by controller.lastPathPointsFlow.collectAsState()

	// Observe daily/gamification state
	val pointsToday by dailyPointsProvider.pointsTodayFlow.collectAsState()
	val goalProgress by goalProgressProvider.goalProgressFlow.collectAsState()
	val activeChallengeInfos by activeChallengesProvider.activeChallengesFlow.collectAsState()

	// Historical data from ViewModel
	val todaySummary by viewModel.todaySummary.collectAsState()
	val dbLastSession by viewModel.dbLastSession.collectAsState()
	val recentTrips by viewModel.recentTrips.collectAsState()
	val explorationState by viewModel.explorationState.collectAsState()
	val streakState by viewModel.streakState.collectAsState()
	val sessionInsights by viewModel.sessionInsights.collectAsState()

	// Dashboard layout and customize sheet state
	val dashboardLayout by viewModel.dashboardLayout.collectAsState()
	var showCustomizeSheet by remember { mutableStateOf(false) }

	// Fetch daily summary and historical data reactively
	LaunchedEffect(isTracking, sessionData) {
		viewModel.refreshTodaySummary()
		viewModel.loadHistoricalData(isTracking, lastSessionData)
	}

	LaunchedEffect(isTracking, sessionInfo) {
		if (!isTracking && sessionInfo == null) {
			userRequestedStop = false
			return@LaunchedEffect
		}

		while (isTracking || sessionInfo != null) {
			delay(5_000)
			if (TrackerServiceApi.isRunningInSystem(context)) {
				continue
			}

			if (controller.isServiceRunning) {
				controller.updateServiceRunning(false)
				controller.updateSessionInfo(null)
				controller.updatePolicyTier(com.adsamcik.tracker.stats.api.PolicyTier.OFF)
			}

			if (!userRequestedStop) {
				val result = snackbarHostState.showSnackbar(
					message = context.getString(R.string.dashboard_tracking_stopped_unexpectedly),
					actionLabel = context.getString(R.string.dashboard_action_restart_tracking),
				)
				if (result == SnackbarResult.ActionPerformed) {
					TrackerServiceApi.startService(context, isUserInitiated = true)
				}
			}

			userRequestedStop = false
			break
		}
	}

	// Resolve display session (active → controller last → DB last)
	val dbLastSessionAsTracker: TrackerSession? = remember(dbLastSession) {
		dbLastSession?.let { trip ->
			TrackerSession(
				id = trip.id,
				start = trip.startTimeMs,
				end = trip.endTimeMs,
				isUserInitiated = false,
				collections = trip.sampleCount,
				distanceInM = trip.distanceM,
				steps = trip.steps ?: 0,
			)
		}
	}
	val displaySession = if (isTracking) sessionData else (sessionData ?: lastSessionData ?: dbLastSessionAsTracker)
	val displayPathPoints = if (isTracking) pathPoints else (pathPoints ?: lastPathPoints)

	val relevantPathPoints = remember(displaySession, displayPathPoints) {
		if (displaySession != null && displayPathPoints != null &&
			displayPathPoints.first == displaySession.id
		) {
			displayPathPoints.second
		} else {
			null
		}
	}
	val unifiedTodaySummary = remember(todaySummary, goalProgress.stepsToday) {
		todaySummary.withUnifiedSteps(goalProgress.stepsToday)
	}

	// Determine dashboard mode
	val dashboardMode = when {
		isTracking -> DashboardMode.TRACKING
		unifiedTodaySummary?.isEmpty == false -> DashboardMode.IDLE
		displaySession != null -> DashboardMode.IDLE
		else -> DashboardMode.EMPTY
	}

	val challengeModels = remember(activeChallengeInfos) {
		viewModel.mapChallenges(activeChallengeInfos)
	}

	LaunchedEffect(isTracking, displaySession?.id, displaySession?.end) {
		viewModel.refreshSessionInsights(isTracking, displaySession)
	}

	val dashboardState = DashboardUiState(
		dashboardMode = dashboardMode,
		isTracking = isTracking,
		isLocked = isLocked,
		hasLocationPermission = hasLocationPermission,
		policyTier = policyTier,
		sessionData = displaySession,
		collectionData = collectionData,
		pathPoints = relevantPathPoints,
		todaySummary = unifiedTodaySummary,
		pointsToday = pointsToday,
		goalProgress = GoalProgressState(
			gamificationEnabled = goalProgress.gamificationEnabled,
			dailySteps = goalProgress.stepsToday,
			dailyGoalSteps = goalProgress.goalSteps,
			dailyProgress = goalProgress.progress,
		),
		activeChallenges = challengeModels,
		recentTrips = recentTrips,
		explorationState = explorationState,
		streakState = streakState,
		sessionInsights = sessionInsights,
	)

	// Contextual permission request dialog
	if (showLocationPermissionRequest) {
		ContextualPermissionRequest(
			permissionType = PermissionType.LOCATION_FOREGROUND,
			permission = Manifest.permission.ACCESS_FINE_LOCATION,
			onPermissionResult = { granted ->
				viewModel.onPermissionResult(granted)
				if (granted) {
					TrackerServiceApi.startService(context, isUserInitiated = true)
				}
			},
			onDismiss = {
				viewModel.dismissPermissionRequest()
			},
		)
	}

	// Permission denied snackbar
	if (permissionDenied) {
		val message = context.getString(
			com.adsamcik.tracker.shared.base.R.string.permission_denied_tracking_disabled,
		)
		PermissionDeniedSnackbar(
			snackbarHostState = snackbarHostState,
			message = message,
		)
		LaunchedEffect(Unit) {
			viewModel.clearPermissionDenied()
		}
	}

	val visibleWidgets = remember(dashboardState, dashboardLayout) {
		viewModel.widgetRegistry.resolveWidgets(dashboardState, dashboardLayout)
	}
	val resolvedWidgetsForSheet = remember(dashboardLayout) {
		viewModel.widgetRegistry.resolveAllWidgets(dashboardLayout)
	}

	DashboardScreen(
		state = dashboardState,
		visibleWidgets = visibleWidgets,
		resolvedWidgetsForSheet = resolvedWidgetsForSheet,
		showCustomizeSheet = showCustomizeSheet,
		onSettingsClick = onOpenSettings,
		onMapClick = onOpenMap,
		onToggleTracking = { shouldStart ->
			if (shouldStart) {
				userRequestedStop = false
				if (!hasAnyTrackingOptionEnabled(context)) {
					coroutineScope.launch {
						val result = snackbarHostState.showSnackbar(
							message = context.getString(com.adsamcik.tracker.tracker.R.string.error_nothing_to_track),
							actionLabel = context.getString(R.string.dashboard_action_open_tracking_settings),
						)
						if (result == SnackbarResult.ActionPerformed) {
							onOpenSettings()
						}
					}
					return@DashboardScreen
				}
				if (hasLocationPermission) {
					TrackerServiceApi.startService(context, isUserInitiated = true)
				} else {
					viewModel.requestPermission()
				}
			} else {
				userRequestedStop = true
				TrackerServiceApi.stopService(context)
			}
		},
		onRequestPermission = { viewModel.requestPermission() },
		onGameClick = onOpenGame,
		onChallengesClick = onOpenChallenges,
		onSessionDetailClick = onSessionDetailClick,
		onCustomizeClick = { showCustomizeSheet = true },
		onReorderWidgets = { viewModel.reorderWidgets(it) },
		onToggleWidgetVisibility = { viewModel.toggleWidgetVisibility(it) },
		onResetLayout = { viewModel.resetLayout() },
		onDismissCustomize = { showCustomizeSheet = false },
		snackbarHostState = snackbarHostState,
		modifier = Modifier.padding(contentPadding),
	)
}

private fun DailySummary?.withUnifiedSteps(goalStepsToday: Int): DailySummary? {
	if (this == null || goalStepsToday <= 0) return this

	return when {
		totalSteps == goalStepsToday -> this
		else -> copy(totalSteps = goalStepsToday)
	}
}

private fun hasAnyTrackingOptionEnabled(context: Context): Boolean {
	val preferences = Preferences(context)
	return preferences.getBoolean(PreferenceKeys.LOCATION_ENABLED, PreferenceKeys.LOCATION_ENABLED_DEFAULT) ||
		preferences.getBoolean(PreferenceKeys.STEPS_ENABLED, PreferenceKeys.STEPS_ENABLED_DEFAULT) ||
		preferences.getBoolean(PreferenceKeys.ACTIVITY_ENABLED, PreferenceKeys.ACTIVITY_ENABLED_DEFAULT) ||
		preferences.getBoolean(PreferenceKeys.CELL_ENABLED, PreferenceKeys.CELL_ENABLED_DEFAULT) ||
		preferences.getBoolean(
			PreferenceKeys.WIFI_LOCATION_COUNT_ENABLED,
			PreferenceKeys.WIFI_LOCATION_COUNT_ENABLED_DEFAULT
		) ||
		preferences.getBoolean(PreferenceKeys.WIFI_NETWORK_ENABLED, PreferenceKeys.WIFI_NETWORK_ENABLED_DEFAULT)
}
