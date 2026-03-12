package com.adsamcik.tracker.dashboard.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.dashboard.ui.compose.state.ChallengeUiModel
import com.adsamcik.tracker.dashboard.ui.compose.state.ExplorationUiState
import com.adsamcik.tracker.dashboard.ui.compose.state.StreakState
import com.adsamcik.tracker.dashboard.ui.compose.state.WeeklyTrend
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.Trip
import com.adsamcik.tracker.shared.base.di.ActiveChallengeInfo
import com.adsamcik.tracker.shared.base.di.ActiveChallengesProvider
import com.adsamcik.tracker.shared.base.di.DailyPointsProvider
import com.adsamcik.tracker.shared.base.di.DailySummary
import com.adsamcik.tracker.shared.base.di.DailySummaryProvider
import com.adsamcik.tracker.shared.base.di.GoalProgressProvider
import com.adsamcik.tracker.tracker.controller.LockManager
import com.adsamcik.tracker.tracker.controller.TrackerServiceController
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import javax.inject.Inject

/**
 * ViewModel for the Dashboard screen.
 *
 * Owns historical data (loaded from Room), permission state, and derived computations.
 * All AppGraph dependencies are injected via Hilt constructor.
 */
@HiltViewModel
class DashboardViewModel @Inject constructor(
	@ApplicationContext private val appContext: Context,
	private val dispatchers: DispatchersProvider,
	private val appDatabase: AppDatabase,
	val trackerController: TrackerServiceController,
	val lockManager: LockManager,
	private val dailySummaryProvider: DailySummaryProvider,
	val dailyPointsProvider: DailyPointsProvider,
	val goalProgressProvider: GoalProgressProvider,
	val activeChallengesProvider: ActiveChallengesProvider,
) : ViewModel() {

	val isTracking: StateFlow<Boolean> = trackerController.isServiceRunningFlow
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
				dailySummaryProvider.fetchTodaySummary()
			} catch (_: Exception) {
				null
			}
		}
	}

	/**
	 * Loads historical data from Room when not actively tracking.
	 * [lastSessionData] is the controller's last session — if null, falls back to DB.
	 */
	fun loadHistoricalData(isTracking: Boolean, lastSessionData: TrackerSession?) {
		if (isTracking) return

		viewModelScope.launch {
			withContext(dispatchers.io) {
				try {
					if (lastSessionData == null) {
						_dbLastSession.value = appDatabase.tripDao().getRecentTrips(1).firstOrNull()
					}
					_recentTrips.value = appDatabase.tripDao().getRecentTrips(5)

					loadExplorationData(appDatabase)
					loadStreakData(appDatabase)
				} catch (_: Exception) {
					// DB errors are non-fatal — cards simply won't show
				}
			}
		}
	}

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
		val streak = db.explorationStreakDao().getByType("DAILY_DISCOVERY")
		val weeklyDistances = mutableListOf<Float>()
		val cal = Calendar.getInstance().apply {
			set(Calendar.HOUR_OF_DAY, 0)
			set(Calendar.MINUTE, 0)
			set(Calendar.SECOND, 0)
			set(Calendar.MILLISECOND, 0)
		}
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
		_streakState.value = StreakState(
			currentStreak = streak?.currentCount ?: 0,
			bestStreak = streak?.bestCount ?: 0,
			weeklyDistances = weeklyDistances,
			weeklyTrend = trend,
		)
	}

	fun mapChallenges(infos: List<ActiveChallengeInfo>): List<ChallengeUiModel> {
		return infos.map { info ->
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
