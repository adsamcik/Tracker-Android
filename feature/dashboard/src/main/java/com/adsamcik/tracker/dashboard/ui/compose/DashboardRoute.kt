package com.adsamcik.tracker.dashboard.ui.compose

import android.Manifest
import android.app.Activity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.adsamcik.tracker.dashboard.R
import com.adsamcik.tracker.dashboard.data.DashboardRecentHistoryEntry
import com.adsamcik.tracker.dashboard.data.DashboardRecentHistoryState
import com.adsamcik.tracker.dashboard.ui.DashboardViewModel
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardMode
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardUiState
import com.adsamcik.tracker.dashboard.ui.compose.state.GoalProgressState
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.GroupedActivity
import com.adsamcik.tracker.shared.base.di.DailySummary
import com.adsamcik.tracker.shared.base.di.GoalProgress
import com.adsamcik.tracker.shared.model.Trip
import com.adsamcik.tracker.shared.utils.compose.permission.ContextualPermissionRequest
import com.adsamcik.tracker.shared.utils.compose.permission.PermissionDeniedSnackbar
import com.adsamcik.tracker.shared.utils.compose.permission.PermissionType
import com.adsamcik.tracker.shared.utils.compose.StopTrackingOptionsDialog
import com.adsamcik.tracker.tracker.api.ManualTrackingStartReadiness
import com.adsamcik.tracker.tracker.api.ManualTrackingStartPrerequisite
import com.adsamcik.tracker.tracker.api.ManualTrackingStartRepairNavigation
import com.adsamcik.tracker.tracker.api.ManualTrackingStartResult
import com.adsamcik.tracker.tracker.api.TrackerServiceApi
import com.adsamcik.tracker.tracker.data.session.TrackerSessionSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Duration used for the "Stop for N minutes" auto-tracking lock offered when stopping a session
 * that auto-tracking could otherwise silently restart. Matches the duration used by the
 * equivalent "stop for N minutes" action on the persistent tracking notification.
 */
private const val STOP_LOCK_MINUTES = 30

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
	val lifecycle = LocalLifecycleOwner.current.lifecycle
	val viewModel: DashboardViewModel = hiltViewModel()

	// Dependencies via ViewModel (Hilt-injected)
	val trackerState = viewModel.trackerStateReader
	val lockManager = viewModel.lockManager

	// Permission state from ViewModel
	val hasLocationPermission by viewModel.hasLocationPermission.collectAsState()
	val snackbarHostState = remember { SnackbarHostState() }
	val coroutineScope = rememberCoroutineScope()
	var userRequestedStop by remember { mutableStateOf(false) }
	var showStopOptions by remember { mutableStateOf(false) }
	var deferredDashboardDataEnabled by remember { mutableStateOf(false) }
	var manualStartReadiness by remember {
		mutableStateOf<ManualTrackingStartReadiness?>(null)
	}
	var manualStartPermissionRequest by remember {
		mutableStateOf<ManualTrackingStartPrerequisite?>(null)
	}
	var manualStartPermissionDenied by remember { mutableStateOf(false) }
	var pendingLocationServicesRepair by remember { mutableStateOf(false) }

	DashboardPermissionResumeEffect(viewModel, context)

	LaunchedEffect(Unit) {
		withFrameNanos { }
		delay(250)
		deferredDashboardDataEnabled = true
	}

	// Observe tracking state
	val isTracking by trackerState.isServiceRunningFlow.collectAsState()
	val sessionInfo by trackerState.sessionInfoFlow.collectAsState()
	val isLocked by lockManager.isLockedFlow.collectAsState()
	val policyTier by trackerState.policyTierFlow.collectAsState()
	val sessionData by trackerState.sessionFlow.collectAsState()
	val collectionSnapshot by trackerState.collectionDataFlow.collectAsState()
	val pathPoints by trackerState.pathPointsFlow.collectAsState()
	val lastSessionData by trackerState.lastSessionFlow.collectAsState()
	val lastPathPoints by trackerState.lastPathPointsFlow.collectAsState()
	val liveSessionPresentation by viewModel.liveSessionPresentation.collectAsStateWithLifecycle()
	val trackingParams by viewModel.trackingParams.collectAsState()
	val trackingUnavailableMessage = stringResource(
		com.adsamcik.tracker.tracker.R.string.notification_tracking_start_failed_title,
	)
	val currentTrackingStoppedUnexpectedlyMessage by rememberUpdatedState(
		stringResource(R.string.dashboard_tracking_stopped_unexpectedly),
	)
	val currentRestartTrackingActionLabel by rememberUpdatedState(
		stringResource(R.string.dashboard_action_restart_tracking),
	)
	val stoppedUntilRechargeMessage = stringResource(
		com.adsamcik.tracker.tracker.R.string.settings_disabled_recharge_summary,
	)

	LaunchedEffect(trackingParams.sourcePolicyRevision, hasLocationPermission) {
		manualStartReadiness = TrackerServiceApi.readManualTrackingStartReadiness(context)
	}

	suspend fun showNoAvailableCaptureSource() {
		val result = snackbarHostState.showSnackbar(
			message = context.getString(com.adsamcik.tracker.tracker.R.string.error_nothing_to_track),
			actionLabel = context.getString(R.string.dashboard_action_open_tracking_settings),
		)
		if (result == SnackbarResult.ActionPerformed) {
			onOpenSettings()
		}
	}

	suspend fun showTrackingUnavailable() {
		snackbarHostState.showSnackbar(
			message = trackingUnavailableMessage,
		)
	}

	fun requestManualStart() {
		coroutineScope.launch {
			when (val result = TrackerServiceApi.requestManualTrackingStart(context)) {
				ManualTrackingStartResult.ENQUEUED -> Unit
				is ManualTrackingStartResult.RepairRequired -> when (result.prerequisite) {
					ManualTrackingStartPrerequisite.LOCATION_SERVICES -> {
						val action = snackbarHostState.showSnackbar(
							message = context.getString(
								com.adsamcik.tracker.tracker.R.string
									.manual_tracking_location_services_required,
							),
							actionLabel = context.getString(
								com.adsamcik.tracker.shared.utils.R.string
									.permission_denied_settings_action,
							),
						)
						if (action == SnackbarResult.ActionPerformed) {
							pendingLocationServicesRepair = true
							if (!ManualTrackingStartRepairNavigation
									.openLocationServicesSettings(context)
							) {
								pendingLocationServicesRepair = false
								showTrackingUnavailable()
							}
						}
					}
					else -> manualStartPermissionRequest = result.prerequisite
				}
				ManualTrackingStartResult.TRACKING_UNAVAILABLE ->
					showTrackingUnavailable()
				ManualTrackingStartResult.NO_AVAILABLE_CAPTURE_SOURCE ->
					showNoAvailableCaptureSource()
			}
			manualStartReadiness = TrackerServiceApi.readManualTrackingStartReadiness(context)
		}
	}

	val manualStartPermissionSatisfied =
		(manualStartReadiness as? ManualTrackingStartReadiness.RepairRequired)?.prerequisite !=
			ManualTrackingStartPrerequisite.PRECISE_LOCATION_PERMISSION

	DashboardManualStartResumeEffect(
		context = context,
		pendingLocationServicesRepair = pendingLocationServicesRepair,
		onReadiness = { manualStartReadiness = it },
		onLocationServicesRepairCompleted = {
			pendingLocationServicesRepair = false
			requestManualStart()
		},
		onLocationServicesRepairUnchanged = { pendingLocationServicesRepair = false },
	)

	LaunchedEffect(context) {
		val intent = (context as? Activity)?.intent
		if (ManualTrackingStartRepairNavigation.consumeDashboardReevaluationRequest(intent)) {
			requestManualStart()
		}
	}

	// Observe daily/gamification state
	val defaultGoalProgress = remember {
		GoalProgress(
			stepsToday = 0,
			goalSteps = 0,
			gamificationEnabled = false,
		)
	}
	val pointsTodayFlow = remember(viewModel, deferredDashboardDataEnabled) {
		if (deferredDashboardDataEnabled) viewModel.pointsTodayFlow else flowOf(0)
	}
	val goalProgressFlow = remember(viewModel, deferredDashboardDataEnabled) {
		if (deferredDashboardDataEnabled) viewModel.goalProgressFlow else flowOf(defaultGoalProgress)
	}
	val pointsToday by pointsTodayFlow.collectAsState(initial = 0)
	val goalProgress by goalProgressFlow.collectAsState(initial = defaultGoalProgress)

	// Historical data from ViewModel
	val todaySummary by viewModel.todaySummary.collectAsState()
	val recentHistory by viewModel.recentHistory.collectAsStateWithLifecycle()
	val explorationState by viewModel.explorationState.collectAsState()
	val streakState by viewModel.streakState.collectAsState()
	val sessionInsights by viewModel.sessionInsights.collectAsState()
	val latestAchievement by viewModel.latestAchievement.collectAsState()

	// Dashboard layout and customize sheet state
	val dashboardLayout by viewModel.dashboardLayout.collectAsState()
	var showCustomizeSheet by remember { mutableStateOf(false) }

	// Fetch daily summary and historical data reactively
	LaunchedEffect(deferredDashboardDataEnabled, isTracking) {
		if (!deferredDashboardDataEnabled) return@LaunchedEffect
		viewModel.refreshTodaySummary()
		viewModel.loadHistoricalData(isTracking)
	}

	LaunchedEffect(lifecycle, isTracking, sessionInfo) {
		if (!isTracking && sessionInfo == null) {
			userRequestedStop = false
			return@LaunchedEffect
		}

		runDashboardConsistencyChecksWhenResumed(
			lifecycle = lifecycle,
			shouldContinue = { isTracking || sessionInfo != null },
		) {
			if (TrackerServiceApi.isRunningInSystem(context)) {
				false
			} else {
				if (trackerState.isServiceRunning) {
					TrackerServiceApi.repairStoppedServiceState(context)
				}

				if (!userRequestedStop) {
					val result = snackbarHostState.showSnackbar(
						message = currentTrackingStoppedUnexpectedlyMessage,
						actionLabel = currentRestartTrackingActionLabel,
					)
					if (result == SnackbarResult.ActionPerformed) {
						requestManualStart()
					}
				}

				userRequestedStop = false
				true
			}
		}
	}

	// Idle detail authority comes exclusively from the first composed physical row.
	val displaySession = remember(
		isTracking,
		sessionData,
		lastSessionData,
		recentHistory,
	) {
		if (isTracking) {
			sessionData
		} else {
			resolveIdleDisplaySession(recentHistory, sessionData, lastSessionData)
		}
	}
	val relevantPathPoints = remember(
		isTracking,
		displaySession,
		pathPoints,
		lastPathPoints,
	) {
		if (isTracking) {
			pathPoints?.takeIf { it.first == displaySession?.id }?.second
		} else {
			sequenceOf(pathPoints, lastPathPoints)
				.filterNotNull()
				.firstOrNull { it.first == displaySession?.id }
				?.second
		}
	}
	val unifiedTodaySummary = remember(todaySummary, goalProgress.stepsToday) {
		todaySummary.withUnifiedSteps(goalProgress.stepsToday)
	}

	// Determine dashboard mode
	val dashboardMode = resolveDashboardMode(
		isTracking = isTracking,
		hasTodaySummary = unifiedTodaySummary?.isEmpty == false,
		displaySession = displaySession,
		recentHistory = recentHistory,
	)

	LaunchedEffect(deferredDashboardDataEnabled, isTracking, displaySession?.id, displaySession?.end) {
		if (!deferredDashboardDataEnabled) return@LaunchedEffect
		viewModel.refreshSessionInsights(isTracking, displaySession)
	}

	val dashboardState = DashboardUiState(
		dashboardMode = dashboardMode,
		isTracking = isTracking,
		isLocked = isLocked,
		hasLocationPermission = manualStartPermissionSatisfied,
		policyTier = policyTier,
		sessionData = displaySession,
		collectionSnapshot = collectionSnapshot,
		pathPoints = relevantPathPoints,
		liveSessionPresentation = liveSessionPresentation,
		todaySummary = unifiedTodaySummary,
		pointsToday = pointsToday,
		goalProgress = GoalProgressState(
			gamificationEnabled = goalProgress.gamificationEnabled,
			dailySteps = goalProgress.stepsToday,
			dailyGoalSteps = goalProgress.goalSteps,
			dailyProgress = goalProgress.progress,
		),
		latestAchievement = latestAchievement,
		recentHistory = recentHistory,
		explorationState = explorationState,
		streakState = streakState,
		sessionInsights = sessionInsights,
	)

	// Contextual permission request dialog. Only an explicit start attempt reaches this state.
	val permissionRequest = manualStartPermissionRequest?.toDashboardPermissionRequest()
	if (permissionRequest != null) {
		ContextualPermissionRequest(
			permissionType = permissionRequest.type,
			permission = permissionRequest.permission,
			onPermissionResult = {
				val repairedPrerequisite = permissionRequest.prerequisite
				manualStartPermissionRequest = null
				coroutineScope.launch {
					val updated = TrackerServiceApi.readManualTrackingStartReadiness(context)
					manualStartReadiness = updated
					val sameRepair = (updated as? ManualTrackingStartReadiness.RepairRequired)
						?.prerequisite == repairedPrerequisite
					if (sameRepair) {
						manualStartPermissionDenied = true
					} else {
						requestManualStart()
					}
				}
			},
			onDismiss = { manualStartPermissionRequest = null },
		)
	}

	// Permission denied snackbar
	if (manualStartPermissionDenied) {
		val message = stringResource(
			com.adsamcik.tracker.shared.utils.R.string.permission_denied_tracking_prerequisite,
		)
		PermissionDeniedSnackbar(
			snackbarHostState = snackbarHostState,
			message = message,
		)
		LaunchedEffect(Unit) {
			delay(5_000)
			manualStartPermissionDenied = false
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
				requestManualStart()
			} else if (trackingParams.autoTrackingMode != GroupedActivity.STILL.ordinal) {
				// Auto-tracking is enabled and would likely restart the session moments after a
				// plain stop (see BackgroundTrackingApi's activity callbacks). Let the user pick
				// how long tracking should actually stay off, mirroring the tracking notification's
				// "stop for N minutes" / "stop until charging" actions.
				showStopOptions = true
			} else {
				userRequestedStop = true
				TrackerServiceApi.stopService(context)
			}
		},
		onRequestPermission = { requestManualStart() },
		onGameClick = onOpenGame,
		onSessionDetailClick = onSessionDetailClick,
		onCustomizeClick = { showCustomizeSheet = true },
		onReorderWidgets = { viewModel.reorderWidgets(it) },
		onToggleWidgetVisibility = { viewModel.toggleWidgetVisibility(it) },
		onResetLayout = { viewModel.resetLayout() },
		onDismissCustomize = { showCustomizeSheet = false },
		snackbarHostState = snackbarHostState,
		modifier = Modifier.padding(contentPadding),
	)

	StopTrackingOptionsDialog(
		visible = showStopOptions,
		title = stringResource(com.adsamcik.tracker.tracker.R.string.stop_tracking_dialog_title),
		message = stringResource(com.adsamcik.tracker.tracker.R.string.stop_tracking_dialog_message),
		stopForMinutesLabel = stringResource(
			com.adsamcik.tracker.tracker.R.string.notification_stop_for_minutes,
			STOP_LOCK_MINUTES,
		),
		stopUntilChargingLabel = stringResource(com.adsamcik.tracker.tracker.R.string.notification_stop_til_recharge),
		justStopLabel = stringResource(com.adsamcik.tracker.tracker.R.string.notification_stop),
		cancelLabel = stringResource(com.adsamcik.tracker.shared.base.R.string.generic_cancel),
		onStopForMinutes = {
			userRequestedStop = true
			lockManager.lockTimeLock(context, STOP_LOCK_MINUTES * Time.MINUTE_IN_MILLISECONDS)
			TrackerServiceApi.stopService(context)
			showStopOptions = false
			coroutineScope.launch {
				snackbarHostState.showSnackbar(
					context.resources.getQuantityString(
						com.adsamcik.tracker.tracker.R.plurals.notification_auto_tracking_lock,
						STOP_LOCK_MINUTES,
						STOP_LOCK_MINUTES,
					),
				)
			}
		},
		onStopUntilCharging = {
			userRequestedStop = true
			lockManager.lockUntilRecharge(context)
			TrackerServiceApi.stopService(context)
			showStopOptions = false
			coroutineScope.launch {
				snackbarHostState.showSnackbar(
					stoppedUntilRechargeMessage,
				)
			}
		},
		onJustStop = {
			userRequestedStop = true
			TrackerServiceApi.stopService(context)
			showStopOptions = false
		},
		onDismiss = { showStopOptions = false },
	)
}

@Composable
internal fun DashboardPermissionResumeEffect(
	viewModel: DashboardViewModel,
	context: android.content.Context,
) {
	val lifecycleOwner = LocalLifecycleOwner.current
	val currentViewModel by rememberUpdatedState(viewModel)
	val currentContext by rememberUpdatedState(context)

	DisposableEffect(lifecycleOwner) {
		val observer = LifecycleEventObserver { _, event ->
			if (event == Lifecycle.Event.ON_RESUME) {
				currentViewModel.checkPermission(currentContext)
			}
		}
		lifecycleOwner.lifecycle.addObserver(observer)
		onDispose {
			lifecycleOwner.lifecycle.removeObserver(observer)
		}
	}
}

internal data class DashboardManualStartPermissionRequest(
	val prerequisite: ManualTrackingStartPrerequisite,
	val type: PermissionType,
	val permission: String,
)

internal fun ManualTrackingStartPrerequisite.toDashboardPermissionRequest():
	DashboardManualStartPermissionRequest? = when (this) {
	ManualTrackingStartPrerequisite.PRECISE_LOCATION_PERMISSION ->
		DashboardManualStartPermissionRequest(
			prerequisite = this,
			type = PermissionType.LOCATION_FOREGROUND,
			permission = Manifest.permission.ACCESS_FINE_LOCATION,
		)
	ManualTrackingStartPrerequisite.ACTIVITY_RECOGNITION_PERMISSION ->
		DashboardManualStartPermissionRequest(
			prerequisite = this,
			type = PermissionType.ACTIVITY_RECOGNITION,
			permission = Manifest.permission.ACTIVITY_RECOGNITION,
		)
	ManualTrackingStartPrerequisite.READ_PHONE_STATE_PERMISSION ->
		DashboardManualStartPermissionRequest(
			prerequisite = this,
			type = PermissionType.PHONE_STATE,
			permission = Manifest.permission.READ_PHONE_STATE,
		)
	ManualTrackingStartPrerequisite.LOCATION_SERVICES -> null
}

/** Re-reads readiness on resume without opening a system prompt from a lifecycle event alone. */
@Composable
internal fun DashboardManualStartResumeEffect(
	context: android.content.Context,
	pendingLocationServicesRepair: Boolean,
	onReadiness: (ManualTrackingStartReadiness) -> Unit,
	onLocationServicesRepairCompleted: () -> Unit,
	onLocationServicesRepairUnchanged: () -> Unit,
) {
	val lifecycleOwner = LocalLifecycleOwner.current
	val coroutineScope = rememberCoroutineScope()
	val currentContext by rememberUpdatedState(context)
	val currentPendingRepair by rememberUpdatedState(pendingLocationServicesRepair)
	val currentOnReadiness by rememberUpdatedState(onReadiness)
	val currentOnRepairCompleted by rememberUpdatedState(onLocationServicesRepairCompleted)
	val currentOnRepairUnchanged by rememberUpdatedState(onLocationServicesRepairUnchanged)

	DisposableEffect(lifecycleOwner) {
		val observer = LifecycleEventObserver { _, event ->
			if (event == Lifecycle.Event.ON_RESUME) {
				coroutineScope.launch {
					val readiness = TrackerServiceApi.readManualTrackingStartReadiness(currentContext)
					currentOnReadiness(readiness)
					if (currentPendingRepair) {
						val stillBlocked = readiness == ManualTrackingStartReadiness.RepairRequired(
							ManualTrackingStartPrerequisite.LOCATION_SERVICES,
						)
						if (stillBlocked) {
							currentOnRepairUnchanged()
						} else {
							currentOnRepairCompleted()
						}
					}
				}
			}
		}
		lifecycleOwner.lifecycle.addObserver(observer)
		onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
	}
}

internal suspend fun runDashboardConsistencyChecksWhenResumed(
	lifecycle: Lifecycle,
	shouldContinue: () -> Boolean,
	intervalMillis: Long = 5_000,
	checkConsistency: suspend () -> Boolean,
) {
	lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
		while (shouldContinue()) {
			delay(intervalMillis)
			if (checkConsistency()) {
				break
			}
		}
	}
}

/**
 * Resolves idle Last Session only from the leading composed history row. Controller snapshots may
 * refresh that physical row, but may not introduce a different or Steps-only action identity.
 */
internal fun resolveIdleDisplaySession(
	recentHistory: DashboardRecentHistoryState,
	currentSession: TrackerSessionSnapshot?,
	lastSession: TrackerSessionSnapshot?,
): TrackerSessionSnapshot? {
	val topPhysical = (recentHistory as? DashboardRecentHistoryState.Content)
		?.entries
		?.firstOrNull() as? DashboardRecentHistoryEntry.Physical
		?: return null
	val trip = topPhysical.trip
	return sequenceOf(currentSession, lastSession)
		.filterNotNull()
		.firstOrNull { it.id == trip.id }
		?: trip.toTrackerSessionSnapshot()
}

/** Loading and unavailable history remain visible idle product states, not first-use emptiness. */
internal fun resolveDashboardMode(
	isTracking: Boolean,
	hasTodaySummary: Boolean,
	displaySession: TrackerSessionSnapshot?,
	recentHistory: DashboardRecentHistoryState,
): DashboardMode = when {
	isTracking -> DashboardMode.TRACKING
	hasTodaySummary || displaySession != null -> DashboardMode.IDLE
	recentHistory is DashboardRecentHistoryState.Loading -> DashboardMode.IDLE
	recentHistory is DashboardRecentHistoryState.Unavailable -> DashboardMode.IDLE
	recentHistory is DashboardRecentHistoryState.Content && recentHistory.entries.isNotEmpty() ->
		DashboardMode.IDLE
	else -> DashboardMode.EMPTY
}

private fun Trip.toTrackerSessionSnapshot() = TrackerSessionSnapshot(
	id = id,
	start = startTimeMs,
	end = endTimeMs,
	isUserInitiated = false,
	collections = sampleCount,
	distanceInM = distanceM,
	steps = steps ?: 0,
)

private fun DailySummary?.withUnifiedSteps(goalStepsToday: Int): DailySummary? {
	if (this == null || goalStepsToday <= 0) return this

	return when {
		totalSteps == goalStepsToday -> this
		else -> copy(totalSteps = goalStepsToday)
	}
}
