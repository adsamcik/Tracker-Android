package com.adsamcik.tracker.dashboard.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.dashboard.data.DashboardHistoryRepository
import com.adsamcik.tracker.dashboard.data.DashboardHistorySection
import com.adsamcik.tracker.dashboard.data.DashboardLayout
import com.adsamcik.tracker.dashboard.data.DashboardLayoutStore
import com.adsamcik.tracker.dashboard.data.DashboardWeeklyTrend
import com.adsamcik.tracker.dashboard.data.DashboardWidgetRegistry
import com.adsamcik.tracker.dashboard.ui.compose.state.LatestAchievementUi
import com.adsamcik.tracker.dashboard.ui.compose.state.ExplorationUiState
import com.adsamcik.tracker.dashboard.ui.compose.state.StreakState
import com.adsamcik.tracker.dashboard.ui.compose.state.WeeklyTrend
import com.adsamcik.tracker.shared.base.di.DailyPointsProvider
import com.adsamcik.tracker.shared.base.di.DailySummary
import com.adsamcik.tracker.shared.base.di.DailySummaryProvider
import com.adsamcik.tracker.shared.base.di.GoalProgressProvider
import com.adsamcik.tracker.shared.base.result.runCatchingCancellable
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.shared.model.Trip
import com.adsamcik.tracker.tracker.insights.SessionInsight
import com.adsamcik.tracker.tracker.insights.SessionInsightsGenerator
import com.adsamcik.tracker.tracker.controller.LockManager
import com.adsamcik.tracker.tracker.controller.TrackerStateReader
import com.adsamcik.tracker.tracker.data.session.TrackerSessionSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Provider
import kotlin.LazyThreadSafetyMode

/**
 * ViewModel for the Dashboard screen.
 *
 * Owns historical presentation state loaded through feature-facing repositories,
 * permission state, and derived computations.
 * All AppGraph dependencies are injected via Hilt constructor.
 */
@HiltViewModel
class DashboardViewModel @Inject constructor(
	@ApplicationContext
	private val appContext: Context,
	private val historyRepository: DashboardHistoryRepository,
	private val layoutRepository: DashboardLayoutStore,
	private val sessionInsightsGenerator: SessionInsightsGenerator,
	val widgetRegistry: DashboardWidgetRegistry,
	val trackerStateReader: TrackerStateReader,
	val lockManager: LockManager,
	private val dailySummaryProvider: Provider<DailySummaryProvider>,
	private val dailyPointsProviderFactory: Provider<DailyPointsProvider>,
	private val goalProgressProviderFactory: Provider<GoalProgressProvider>,
	trackingParamsRepository: TrackingParamsRepository,
) : ViewModel() {

	private val dailyPointsProvider by lazy(LazyThreadSafetyMode.NONE) {
		dailyPointsProviderFactory.get()
	}
	private val goalProgressProvider by lazy(LazyThreadSafetyMode.NONE) {
		goalProgressProviderFactory.get()
	}

	val pointsTodayFlow: StateFlow<Int>
		get() = dailyPointsProvider.pointsTodayFlow

	val goalProgressFlow: StateFlow<com.adsamcik.tracker.shared.base.di.GoalProgress>
		get() = goalProgressProvider.goalProgressFlow

	val dashboardLayout: StateFlow<DashboardLayout> = layoutRepository.layout
		.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardLayout())
	val trackingParams: StateFlow<TrackingParamsState> = trackingParamsRepository.data
		.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TrackingParamsState())

	val isTracking: StateFlow<Boolean> = trackerStateReader.isServiceRunningFlow
		.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
	val isLocked: StateFlow<Boolean> = lockManager.isLockedFlow
		.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

	private val _todaySummary = MutableStateFlow<DailySummary?>(null)
	val todaySummary: StateFlow<DailySummary?> = _todaySummary.asStateFlow()

	private val _dbLastSession = MutableStateFlow<Trip?>(null)
	val dbLastSession: StateFlow<Trip?> = _dbLastSession.asStateFlow()

	private val _recentTrips = MutableStateFlow<List<Trip>>(emptyList())
	val recentTrips: StateFlow<List<Trip>> = _recentTrips.asStateFlow()

	private val _explorationState = MutableStateFlow(ExplorationUiState())
	val explorationState: StateFlow<ExplorationUiState> = _explorationState.asStateFlow()

	private val _streakState = MutableStateFlow(StreakState())
	val streakState: StateFlow<StreakState> = _streakState.asStateFlow()

	private val _latestAchievement = MutableStateFlow<LatestAchievementUi?>(null)
	val latestAchievement: StateFlow<LatestAchievementUi?> = _latestAchievement.asStateFlow()

	private val _sessionInsights = MutableStateFlow<List<SessionInsight>>(emptyList())
	val sessionInsights: StateFlow<List<SessionInsight>> = _sessionInsights.asStateFlow()

	private val _hasLocationPermission = MutableStateFlow(checkLocationPermission(appContext))
	val hasLocationPermission: StateFlow<Boolean> = _hasLocationPermission.asStateFlow()

	private val _showLocationPermissionRequest = MutableStateFlow(false)
	val showLocationPermissionRequest: StateFlow<Boolean> =
		_showLocationPermissionRequest.asStateFlow()

	private val _permissionDenied = MutableStateFlow(false)
	val permissionDenied: StateFlow<Boolean> = _permissionDenied.asStateFlow()

	/**
	 * Fetches today's summary via the injected [DailySummaryProvider].
	 */
	fun refreshTodaySummary() {
		viewModelScope.launch {
			_todaySummary.value = runCatchingCancellable {
				dailySummaryProvider.get().fetchTodaySummary()
			}.getOrNull()
		}
	}

	/**
	 * Loads historical data from persistence when not actively tracking.
	 * [lastSessionData] is the controller's last session; if null, the repository
	 * supplies the latest persisted session.
	 */
	fun loadHistoricalData(isTracking: Boolean, lastSessionData: TrackerSessionSnapshot?) {
		if (isTracking) return

		viewModelScope.launch {
			runCatchingCancellable {
				val history = historyRepository.load(includeLastSession = lastSessionData == null)
				if (lastSessionData == null) {
					_dbLastSession.value = history.lastSession
				}
				_recentTrips.value = history.recentTrips
				when (val exploration = history.exploration) {
					is DashboardHistorySection.Loaded -> {
						_explorationState.value = exploration.value?.let {
							ExplorationUiState(
								totalCells = it.totalCells,
								newCellsToday = it.newCellsToday,
								seasonsCovered = it.seasonsCovered,
								hasExplorationData = true,
							)
						} ?: ExplorationUiState()
					}
					DashboardHistorySection.Failed -> Unit
				}
				when (val streak = history.streak) {
					is DashboardHistorySection.Loaded -> {
						_streakState.value = StreakState(
							currentStreak = streak.value.currentStreak,
							bestStreak = streak.value.bestStreak,
							weeklyDistances = streak.value.weeklyDistances,
							weeklyTrend = when (streak.value.weeklyTrend) {
								DashboardWeeklyTrend.UP -> WeeklyTrend.UP
								DashboardWeeklyTrend.DOWN -> WeeklyTrend.DOWN
								DashboardWeeklyTrend.STEADY -> WeeklyTrend.STEADY
							},
						)
					}
					DashboardHistorySection.Failed -> Unit
				}
				when (val achievement = history.latestAchievement) {
					is DashboardHistorySection.Loaded -> {
						_latestAchievement.value = achievement.value?.let {
							LatestAchievementUi(
								id = it.id,
								nameRes = it.nameRes,
								tier = it.tier,
								unlockedAt = it.unlockedAt,
							)
						}
					}
					DashboardHistorySection.Failed -> Unit
				}
			}.getOrNull()
		}
	}

	fun refreshSessionInsights(isTracking: Boolean, session: TrackerSessionSnapshot?) {
		if (isTracking || session == null) {
			_sessionInsights.value = emptyList()
			return
		}

		viewModelScope.launch {
			_sessionInsights.value = runCatchingCancellable {
				sessionInsightsGenerator.generate(session)
			}.getOrElse {
				emptyList()
			}
		}
	}

	fun onPermissionResult(granted: Boolean) {
		_hasLocationPermission.value = granted
		if (!granted) {
			_permissionDenied.value = true
		}
		_showLocationPermissionRequest.value = false
	}

	fun requestPermission() {
		_showLocationPermissionRequest.value = true
	}

	fun dismissPermissionRequest() {
		_showLocationPermissionRequest.value = false
	}

	fun clearPermissionDenied() {
		_permissionDenied.value = false
	}

	fun checkPermission(context: Context) {
		_hasLocationPermission.value = checkLocationPermission(context)
	}

	// ─── Dashboard layout management ─────────────────────────────────

	fun reorderWidgets(widgetIds: List<String>) {
		viewModelScope.launch {
			layoutRepository.reorder(widgetIds)
		}
	}

	fun toggleWidgetVisibility(widgetId: String) {
		viewModelScope.launch {
			layoutRepository.toggleVisibility(widgetId)
		}
	}

	fun resetLayout() {
		viewModelScope.launch {
			layoutRepository.resetToDefault()
		}
	}

	companion object {
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
	}
}
