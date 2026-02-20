package com.adsamcik.tracker.dashboard.ui.compose

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardMode
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardUiState
import com.adsamcik.tracker.dashboard.ui.compose.state.ChallengeUiModel
import com.adsamcik.tracker.dashboard.ui.compose.state.ExplorationUiState
import com.adsamcik.tracker.dashboard.ui.compose.state.GoalProgressState
import com.adsamcik.tracker.dashboard.ui.compose.state.StreakState
import com.adsamcik.tracker.dashboard.ui.compose.state.WeeklyTrend
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.Trip
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

	// Dependencies via CompositionLocal
	val controller = LocalTrackerController.current as TrackerServiceController
	val lockManager = LocalLockManager.current as LockManager
	val dailySummaryProvider = LocalDailySummaryProvider.current
	val dailyPointsProvider = LocalDailyPointsProvider.current
	val goalProgressProvider = LocalGoalProgressProvider.current
	val activeChallengesProvider = LocalActiveChallengesProvider.current

	// Permission state
	var hasLocationPermission by remember { mutableStateOf(checkLocationPermission(context)) }
	var showLocationPermissionRequest by remember { mutableStateOf(false) }
	var permissionDenied by remember { mutableStateOf(false) }
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

	// Fetch daily summary and historical data reactively
	var todaySummary by remember { mutableStateOf<com.adsamcik.tracker.shared.base.di.DailySummary?>(null) }
	var dbLastSession by remember { mutableStateOf<TrackerSession?>(null) }
	var recentTrips by remember { mutableStateOf<List<Trip>>(emptyList()) }
	var explorationState by remember { mutableStateOf(ExplorationUiState()) }
	var streakState by remember { mutableStateOf(StreakState()) }

	LaunchedEffect(isTracking, sessionData) {
		todaySummary = try {
			dailySummaryProvider.fetchTodaySummary()
		} catch (_: Exception) {
			null
		}

		// Load historical data from DB when not actively tracking
		if (!isTracking) {
			withContext(Dispatchers.IO) {
				val db = AppDatabase.database(context)
				try {
					if (lastSessionData == null) {
						dbLastSession = db.sessionDao().getLast(1)
					}
					recentTrips = db.tripDao().getRecentTrips(5)

					// Load exploration data
					val cellDao = db.explorationCellDao()
					val totalCells = cellDao.countAtLevel(14)
					if (totalCells > 0) {
						val todayStartMs = java.util.Calendar.getInstance().apply {
							set(java.util.Calendar.HOUR_OF_DAY, 0)
							set(java.util.Calendar.MINUTE, 0)
							set(java.util.Calendar.SECOND, 0)
							set(java.util.Calendar.MILLISECOND, 0)
						}.timeInMillis
						val newToday = cellDao.countDiscoveredSince(todayStartMs, 14)
						val bitmasks = cellDao.getDistinctSeasonBitmasks(14)
						val combinedBitmask = bitmasks.fold(0) { acc, b -> acc or b }
						explorationState = ExplorationUiState(
							totalCells = totalCells,
							newCellsToday = newToday,
							seasonsCovered = Integer.bitCount(combinedBitmask),
							hasExplorationData = true,
						)
					}

					// Load streak data
					val streak = db.explorationStreakDao().getByType("DAILY_DISCOVERY")
					val weeklyDistances = mutableListOf<Float>()
					val cal = java.util.Calendar.getInstance()
					cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
					cal.set(java.util.Calendar.MINUTE, 0)
					cal.set(java.util.Calendar.SECOND, 0)
					cal.set(java.util.Calendar.MILLISECOND, 0)
					// Get distances for each of last 7 days (oldest first)
					for (daysAgo in 6 downTo 0) {
						val dayStart = cal.timeInMillis - daysAgo * 86_400_000L
						val dayEnd = dayStart + 86_400_000L
						val trips = db.tripDao().getBetween(dayStart, dayEnd)
						weeklyDistances.add(trips.sumOf { it.distanceM.toDouble() }.toFloat())
					}
					val thisWeek = weeklyDistances.takeLast(3).sum()
					val lastWeek = weeklyDistances.take(3).sum()
					val trend = when {
						thisWeek > lastWeek * 1.1f -> WeeklyTrend.UP
						thisWeek < lastWeek * 0.9f -> WeeklyTrend.DOWN
						else -> WeeklyTrend.STEADY
					}
					streakState = StreakState(
						currentStreak = streak?.currentCount ?: 0,
						bestStreak = streak?.bestCount ?: 0,
						weeklyDistances = weeklyDistances,
						weeklyTrend = trend,
					)
				} catch (_: Exception) {
					// DB errors are non-fatal — cards simply won't show
				}
			}
		}
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
		activeChallengeInfos.map { info ->
			ChallengeUiModel(
				id = info.id,
				title = info.title,
				description = info.description,
				progress = info.progress,
				iconResName = "",
				difficulty = info.difficulty,
				timeRemainingMs = info.timeRemainingMs,
				rewardPoints = 0,
			)
		}
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
				hasLocationPermission = granted
				if (granted) {
					TrackerServiceApi.startService(context, isUserInitiated = true)
				} else {
					permissionDenied = true
				}
			},
			onDismiss = {
				showLocationPermissionRequest = false
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
			permissionDenied = false
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
					showLocationPermissionRequest = true
				}
			} else {
				TrackerServiceApi.stopService(context)
			}
		},
		onRequestPermission = { showLocationPermissionRequest = true },
		onGameClick = onOpenGame,
		onSessionDetailClick = onSessionDetailClick,
		snackbarHostState = snackbarHostState,
		modifier = Modifier.padding(contentPadding),
	)
}

private fun checkLocationPermission(context: Context): Boolean {
	return ContextCompat.checkSelfPermission(
		context,
		Manifest.permission.ACCESS_FINE_LOCATION,
	) == PackageManager.PERMISSION_GRANTED ||
		ContextCompat.checkSelfPermission(
			context,
			Manifest.permission.ACCESS_COARSE_LOCATION,
		) == PackageManager.PERMISSION_GRANTED
}
