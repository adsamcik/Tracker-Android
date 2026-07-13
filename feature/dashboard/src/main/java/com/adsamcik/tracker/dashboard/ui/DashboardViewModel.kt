package com.adsamcik.tracker.dashboard.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.dashboard.data.DashboardLayout
import com.adsamcik.tracker.dashboard.data.DashboardLayoutRepository
import com.adsamcik.tracker.dashboard.data.DashboardWidgetRegistry
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.dashboard.ui.compose.state.ExplorationUiState
import com.adsamcik.tracker.dashboard.ui.compose.state.StreakState
import com.adsamcik.tracker.dashboard.ui.compose.state.WeeklyTrend
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.DailySummaryEntity
import com.adsamcik.tracker.shared.base.di.DailyPointsProvider
import com.adsamcik.tracker.shared.base.di.DailySummary
import com.adsamcik.tracker.shared.base.di.DailySummaryProvider
import com.adsamcik.tracker.shared.base.di.GoalProgressProvider
import com.adsamcik.tracker.shared.base.mapper.toModel
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
import kotlinx.coroutines.withContext
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Provider
import com.adsamcik.tracker.dashboard.ui.compose.state.LatestAchievementUi
import com.adsamcik.tracker.stats.api.achievement.AchievementCatalog
import com.adsamcik.tracker.stats.api.metric.MetricKey
import kotlin.LazyThreadSafetyMode

/**
 * ViewModel for the Dashboard screen.
 *
 * Owns historical data (loaded from Room), permission state, and derived computations.
 * All AppGraph dependencies are injected via Hilt constructor.
 */
@HiltViewModel
class DashboardViewModel @Inject constructor(
	@ApplicationContext
	private val appContext: Context,
	private val dispatchers: DispatchersProvider,
	private val appDatabaseProvider: Provider<AppDatabase>,
	private val layoutRepository: DashboardLayoutRepository,
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
			_todaySummary.value = try {
				dailySummaryProvider.get().fetchTodaySummary()
			} catch (e: Exception) {
				Reporter.report(e)
				null
			}
		}
	}

	/**
	 * Loads historical data from Room when not actively tracking.
	 * [lastSessionData] is the controller's last session — if null, falls back to DB.
	 */
	fun loadHistoricalData(isTracking: Boolean, lastSessionData: TrackerSessionSnapshot?) {
		if (isTracking) return

		viewModelScope.launch {
			withContext(dispatchers.io) {
				try {
					val appDatabase = appDatabaseProvider.get()
					if (lastSessionData == null) {
						_dbLastSession.value = appDatabase.tripDao().getRecentTrips(1).firstOrNull()?.toModel()
					}
					_recentTrips.value = appDatabase.tripDao().getRecentTrips(5).map { it.toModel() }

					loadExplorationData(appDatabase)
					loadStreakData(appDatabase)
					loadLatestAchievement(appDatabase)
				} catch (e: Exception) {
					Reporter.report(e)
					// DB errors are non-fatal — cards simply won't show
				}
			}
		}
	}

	fun refreshSessionInsights(isTracking: Boolean, session: TrackerSessionSnapshot?) {
		if (isTracking || session == null) {
			_sessionInsights.value = emptyList()
			return
		}

		viewModelScope.launch {
			_sessionInsights.value = runCatching {
				sessionInsightsGenerator.generate(session.toTrackerSession())
			}.getOrElse {
				Reporter.report(it)
				emptyList()
			}
		}
	}

	private fun TrackerSessionSnapshot.toTrackerSession() = TrackerSession(
		id = id,
		start = start,
		end = end,
		isUserInitiated = isUserInitiated,
		collections = collections,
		distanceInM = distanceInM,
		distanceOnFootInM = distanceOnFootInM,
		distanceInVehicleInM = distanceInVehicleInM,
		steps = steps,
		sessionActivityId = sessionActivityId,
	)

	private suspend fun loadExplorationData(db: AppDatabase) {
		val cellDao = db.explorationCellDao()
		val totalCells = cellDao.countAtLevel(14)
		if (totalCells > 0) {
			val todayStartMs = Calendar.getInstance().apply {
				set(Calendar.HOUR_OF_DAY, 0)
				set(Calendar.MINUTE, 0)
				set(Calendar.SECOND, 0)
				set(Calendar.MILLISECOND, 0)
			}.timeInMillis
			val newToday = cellDao.countDiscoveredSince(todayStartMs, 14)
			val bitmasks = cellDao.getDistinctSeasonBitmasks(14)
			val combinedBitmask = bitmasks.fold(0) { acc, b -> acc or b }
			_explorationState.value = ExplorationUiState(
				totalCells = totalCells,
				newCellsToday = newToday,
				seasonsCovered = Integer.bitCount(combinedBitmask),
				hasExplorationData = true,
			)
		}
	}

	private suspend fun loadStreakData(db: AppDatabase) {
		val streak = db.explorationStreakDao().getByType(DOMAIN_DAILY_DISCOVERY)
		val cal = Calendar.getInstance().apply {
			set(Calendar.HOUR_OF_DAY, 0)
			set(Calendar.MINUTE, 0)
			set(Calendar.SECOND, 0)
			set(Calendar.MILLISECOND, 0)
		}
		val todayEpochDay = cal.timeInMillis / 86_400_000L
		val startEpochDay = todayEpochDay - 6
		val summariesByDay = db.dailySummaryDao()
			.getBetween(startEpochDay, todayEpochDay)
			.associateBy(DailySummaryEntity::dateEpochDay)
		val weeklyDistances = (startEpochDay..todayEpochDay).map { epochDay ->
			summariesByDay[epochDay]?.totalDistanceM ?: 0f
		}
		// Use averages to normalise for unequal group sizes (4 older + 3 recent = 7 days).
		val recentAvg = weeklyDistances.takeLast(3).average().toFloat()
		val olderAvg = weeklyDistances.take(4).average().toFloat()
		val trend = when {
			olderAvg <= 0f -> if (recentAvg > 0f) WeeklyTrend.UP else WeeklyTrend.STEADY
			recentAvg > olderAvg * 1.1f -> WeeklyTrend.UP
			recentAvg < olderAvg * 0.9f -> WeeklyTrend.DOWN
			else -> WeeklyTrend.STEADY
		}

		// Validate streak: reset to 0 if last increment was more than 1 day ago
		val streakCount = if (streak != null && streak.lastIncrementDay > 0) {
			val daysSinceLastIncrement = todayEpochDay - streak.lastIncrementDay
			if (daysSinceLastIncrement > 1) 0 else streak.currentCount
		} else {
			streak?.currentCount ?: 0
		}

		_streakState.value = StreakState(
			currentStreak = streakCount,
			bestStreak = streak?.bestCount ?: 0,
			weeklyDistances = weeklyDistances,
			weeklyTrend = trend,
		)
	}

	private suspend fun loadLatestAchievement(db: AppDatabase) {
		val row = db.achievementProgressDao().getAll()
			.firstOrNull { it.lastTierIndex >= 0 }
		val metric = row?.metricKey?.let(MetricKey::fromStorageKey)
		val definition = if (metric != null) AchievementCatalog.byMetric(metric).getOrNull(row.lastTierIndex) else null
		_latestAchievement.value = if (row != null && definition != null) {
			LatestAchievementUi(
				id = definition.id,
				nameRes = definition.nameRes,
				tier = definition.tier,
				unlockedAt = row.updatedAt,
			)
		} else {
			null
		}
	}

	fun onPermissionResult(granted: Boolean) {
		_hasLocationPermission.value = granted
		if (!granted) {
			_permissionDenied.value = true
		}
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
		private const val DOMAIN_DAILY_DISCOVERY = "DAILY_DISCOVERY"
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
