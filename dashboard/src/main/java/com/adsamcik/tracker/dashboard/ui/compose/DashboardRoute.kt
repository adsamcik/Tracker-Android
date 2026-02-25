package com.adsamcik.tracker.dashboard.ui.compose

import android.Manifest
import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.adsamcik.tracker.dashboard.ui.DashboardViewModel
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardMode
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardUiState
import com.adsamcik.tracker.dashboard.ui.compose.state.GoalProgressState
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.base.di.LocalDailyPointsProvider
import com.adsamcik.tracker.shared.base.di.LocalDailySummaryProvider
import com.adsamcik.tracker.shared.base.di.LocalGoalProgressProvider
import com.adsamcik.tracker.shared.base.di.LocalActiveChallengesProvider
import com.adsamcik.tracker.shared.base.di.LocalLockManager
import com.adsamcik.tracker.shared.base.di.LocalTrackerController
import com.adsamcik.tracker.shared.base.permission.ContextualPermissionRequest
import com.adsamcik.tracker.shared.base.permission.PermissionDeniedSnackbar
import com.adsamcik.tracker.shared.base.permission.PermissionType
import com.adsamcik.tracker.tracker.api.TrackerServiceApi
import com.adsamcik.tracker.tracker.controller.LockManager
import com.adsamcik.tracker.tracker.controller.TrackerServiceController

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
	onSessionDetailClick: ((Long) -> Unit)? = null,
	contentPadding: PaddingValues = PaddingValues(),
) {
	val context = LocalContext.current
	val viewModel: DashboardViewModel = viewModel {
		DashboardViewModel(context.applicationContext as Application, DefaultDispatchersProvider)
	}

	// Dependencies via CompositionLocal
	val controller = LocalTrackerController.current as TrackerServiceController
	val lockManager = LocalLockManager.current as LockManager
	val dailySummaryProvider = LocalDailySummaryProvider.current
	val dailyPointsProvider = LocalDailyPointsProvider.current
	val goalProgressProvider = LocalGoalProgressProvider.current
	val activeChallengesProvider = LocalActiveChallengesProvider.current

	// Permission state from ViewModel
	val hasLocationPermission by viewModel.hasLocationPermission.collectAsState()
	val showLocationPermissionRequest by viewModel.showLocationPermissionRequest.collectAsState()
	val permissionDenied by viewModel.permissionDenied.collectAsState()
	val snackbarHostState = remember { SnackbarHostState() }

	// Observe tracking state
	val isTracking by controller.isServiceRunningFlow.collectAsState()
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

	// Fetch daily summary and historical data reactively
	LaunchedEffect(isTracking, sessionData) {
		viewModel.refreshTodaySummary(dailySummaryProvider)
		viewModel.loadHistoricalData(isTracking, lastSessionData)
	}

	// Resolve display session (active → controller last → DB last)
	val displaySession = if (isTracking) sessionData else (sessionData ?: lastSessionData ?: dbLastSession)
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

	// Determine dashboard mode
	val dashboardMode = when {
		isTracking -> DashboardMode.TRACKING
		todaySummary?.isEmpty == false -> DashboardMode.IDLE
		displaySession != null -> DashboardMode.IDLE
		else -> DashboardMode.EMPTY
	}

	val challengeModels = remember(activeChallengeInfos) {
		viewModel.mapChallenges(activeChallengeInfos)
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
		todaySummary = todaySummary,
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

	DashboardScreen(
		state = dashboardState,
		onSettingsClick = onOpenSettings,
		onMapClick = onOpenMap,
		onToggleTracking = { shouldStart ->
			if (shouldStart) {
				if (hasLocationPermission) {
					TrackerServiceApi.startService(context, isUserInitiated = true)
				} else {
					viewModel.requestPermission()
				}
			} else {
				TrackerServiceApi.stopService(context)
			}
		},
		onRequestPermission = { viewModel.requestPermission() },
		onGameClick = onOpenGame,
		onSessionDetailClick = onSessionDetailClick,
		snackbarHostState = snackbarHostState,
		modifier = Modifier.padding(contentPadding),
	)
}
