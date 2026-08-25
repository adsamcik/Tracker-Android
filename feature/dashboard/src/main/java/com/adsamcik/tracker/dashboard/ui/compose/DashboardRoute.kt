package com.adsamcik.tracker.dashboard.ui.compose

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.lifecycle.repeatOnLifecycle
import com.adsamcik.tracker.dashboard.R
import com.adsamcik.tracker.dashboard.ui.DashboardViewModel
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardMode
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardUiState
import com.adsamcik.tracker.dashboard.ui.compose.state.GoalProgressState
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.assist.Assist
import com.adsamcik.tracker.shared.base.data.GroupedActivity
import com.adsamcik.tracker.shared.base.di.DailySummary
import com.adsamcik.tracker.shared.base.di.GoalProgress
import com.adsamcik.tracker.shared.base.extension.hasActivityPermission
import com.adsamcik.tracker.shared.base.extension.hasPressureSensor
import com.adsamcik.tracker.shared.base.extension.hasPreciseLocationPermission
import com.adsamcik.tracker.shared.base.extension.hasReadPhonePermission
import com.adsamcik.tracker.shared.base.extension.hasStepCounterSensor
import com.adsamcik.tracker.shared.base.result.runCatchingCancellable
import com.adsamcik.tracker.shared.utils.compose.permission.ContextualPermissionRequest
import com.adsamcik.tracker.shared.utils.compose.permission.PermissionDeniedSnackbar
import com.adsamcik.tracker.shared.utils.compose.permission.PermissionType
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.shared.utils.compose.StopTrackingOptionsDialog
import com.adsamcik.tracker.tracker.api.ManualTrackingCaptureReachability
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
	val showLocationPermissionRequest by viewModel.showLocationPermissionRequest.collectAsState()
	val permissionDenied by viewModel.permissionDenied.collectAsState()
	val snackbarHostState = remember { SnackbarHostState() }
	val coroutineScope = rememberCoroutineScope()
	var userRequestedStop by remember { mutableStateOf(false) }
	var showStopOptions by remember { mutableStateOf(false) }
	var deferredDashboardDataEnabled by remember { mutableStateOf(false) }
	var manualCaptureReachability by remember {
		mutableStateOf<ManualTrackingCaptureReachability?>(null)
	}

	DashboardPermissionResumeEffect(viewModel, context)

	LaunchedEffect(Unit) {
		manualCaptureReachability = runCatchingCancellable {
			TrackerServiceApi.readManualTrackingCaptureReachability(context)
		}.getOrNull()
	}

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
	val trackingParams by viewModel.trackingParams.collectAsState()

	fun resolveManualStartDecision(
		preciseLocationPermissionOverride: Boolean? = null,
		reachability: ManualTrackingCaptureReachability.Available,
	): DashboardManualStartDecision = resolveDashboardManualStartDecision(
		params = trackingParams,
		capabilities = dashboardCaptureCapabilities(
			context = context,
			anyLocationPermissionGranted = hasLocationPermission,
			preciseLocationPermissionOverride = preciseLocationPermissionOverride,
		),
		reachableSources = reachability.reachableSources,
	)

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
			message = context.getString(R.string.dashboard_tracking_temporarily_unavailable),
		)
	}

	fun requestManualStart(preciseLocationPermissionOverride: Boolean? = null) {
		coroutineScope.launch {
			val reachability = runCatchingCancellable {
				TrackerServiceApi.readManualTrackingCaptureReachability(context)
			}.getOrNull()
			manualCaptureReachability = reachability
			val decision = when (reachability) {
				is ManualTrackingCaptureReachability.Available ->
					resolveManualStartDecision(preciseLocationPermissionOverride, reachability)
				ManualTrackingCaptureReachability.Unavailable,
				null,
				-> DashboardManualStartDecision.TRACKING_UNAVAILABLE
			}
			when (decision) {
				DashboardManualStartDecision.START -> when (
					resolveDashboardManualStartEnqueueDecision(
						TrackerServiceApi.startServiceAndAwaitEnqueue(
							context = context,
							isUserInitiated = true,
						),
					)
				) {
					DashboardManualStartEnqueueDecision.ENQUEUED -> Unit
					DashboardManualStartEnqueueDecision.TRACKING_UNAVAILABLE ->
						showTrackingUnavailable()
				}
				DashboardManualStartDecision.REQUEST_PRECISE_LOCATION_PERMISSION ->
					viewModel.requestPermission()
				DashboardManualStartDecision.TRACKING_UNAVAILABLE ->
					showTrackingUnavailable()
				DashboardManualStartDecision.NO_AVAILABLE_CAPTURE_SOURCE ->
					showNoAvailableCaptureSource()
			}
		}
	}

	val manualStartPermissionSatisfied = when (val reachability = manualCaptureReachability) {
		is ManualTrackingCaptureReachability.Available ->
			resolveManualStartDecision(reachability = reachability) !=
				DashboardManualStartDecision.REQUEST_PRECISE_LOCATION_PERMISSION
		ManualTrackingCaptureReachability.Unavailable,
		null,
		-> true
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
	val dbLastSession by viewModel.dbLastSession.collectAsState()
	val recentTrips by viewModel.recentTrips.collectAsState()
	val explorationState by viewModel.explorationState.collectAsState()
	val streakState by viewModel.streakState.collectAsState()
	val sessionInsights by viewModel.sessionInsights.collectAsState()
	val latestAchievement by viewModel.latestAchievement.collectAsState()

	// Dashboard layout and customize sheet state
	val dashboardLayout by viewModel.dashboardLayout.collectAsState()
	var showCustomizeSheet by remember { mutableStateOf(false) }

	// Fetch daily summary and historical data reactively
	LaunchedEffect(deferredDashboardDataEnabled, isTracking, sessionData) {
		if (!deferredDashboardDataEnabled) return@LaunchedEffect
		viewModel.refreshTodaySummary()
		viewModel.loadHistoricalData(isTracking, lastSessionData)
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
						message = context.getString(R.string.dashboard_tracking_stopped_unexpectedly),
						actionLabel = context.getString(R.string.dashboard_action_restart_tracking),
					)
					if (result == SnackbarResult.ActionPerformed) {
						TrackerServiceApi.startService(context, isUserInitiated = true)
					}
				}

				userRequestedStop = false
				true
			}
		}
	}

	// Resolve display session (active → controller last → DB last)
	val dbLastSessionAsTracker: TrackerSessionSnapshot? = remember(dbLastSession) {
		dbLastSession?.let { trip ->
			TrackerSessionSnapshot(
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
		todaySummary = unifiedTodaySummary,
		pointsToday = pointsToday,
		goalProgress = GoalProgressState(
			gamificationEnabled = goalProgress.gamificationEnabled,
			dailySteps = goalProgress.stepsToday,
			dailyGoalSteps = goalProgress.goalSteps,
			dailyProgress = goalProgress.progress,
		),
		latestAchievement = latestAchievement,
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
					requestManualStart(preciseLocationPermissionOverride = true)
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
			com.adsamcik.tracker.shared.utils.R.string.permission_denied_tracking_disabled,
		)
		PermissionDeniedSnackbar(
			snackbarHostState = snackbarHostState,
			message = message,
		)
		LaunchedEffect(Unit) {
			delay(5_000)
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
		onRequestPermission = { viewModel.requestPermission() },
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
					context.getString(com.adsamcik.tracker.tracker.R.string.settings_disabled_recharge_summary),
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

private fun DailySummary?.withUnifiedSteps(goalStepsToday: Int): DailySummary? {
	if (this == null || goalStepsToday <= 0) return this

	return when {
		totalSteps == goalStepsToday -> this
		else -> copy(totalSteps = goalStepsToday)
	}
}

private fun dashboardCaptureCapabilities(
	context: android.content.Context,
	anyLocationPermissionGranted: Boolean,
	preciseLocationPermissionOverride: Boolean? = null,
): DashboardCaptureCapabilities {
	val packageManager = context.packageManager
	val preciseLocationPermissionGranted = preciseLocationPermissionOverride
		?: context.hasPreciseLocationPermission
	val cellHardwareAvailable = packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) ||
		(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
			packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_RADIO_ACCESS))

	return DashboardCaptureCapabilities(
		locationHardwareAvailable = packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION),
		anyLocationPermissionGranted = anyLocationPermissionGranted || preciseLocationPermissionGranted,
		preciseLocationPermissionGranted = preciseLocationPermissionGranted,
		activityPermissionGranted = context.hasActivityPermission,
		stepCounterAvailable = context.hasStepCounterSensor,
		wifiHardwareAvailable = packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI),
		cellHardwareAvailable = cellHardwareAvailable,
		readPhoneStatePermissionGranted = context.hasReadPhonePermission,
		pressureSensorAvailable = context.hasPressureSensor,
		playServicesAvailable = Assist.isPlayServicesAvailable(context),
	)
}
