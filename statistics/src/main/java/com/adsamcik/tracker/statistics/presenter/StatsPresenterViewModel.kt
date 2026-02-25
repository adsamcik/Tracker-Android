package com.adsamcik.tracker.statistics.presenter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import com.adsamcik.tracker.shared.base.database.dao.TripDao
import com.adsamcik.tracker.stats.api.repository.DailySummaryRepository
import com.adsamcik.tracker.statistics.repository.SessionRepository
import com.adsamcik.tracker.statistics.viewmodel.DayBar
import com.adsamcik.tracker.statistics.viewmodel.StatsLoadState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * Hilt-compatible ViewModel that bridges the new repository layer with
 * the paging and dialog patterns the statistics UI expects.
 *
 * - Uses [TripDao] for paging (no KMP paging support yet).
 * - Uses [DailySummaryRepository] for weekly bar chart data.
 * - Uses [SessionRepository] for legacy summary/weekly dialog stats.
 */
@HiltViewModel
class StatsPresenterViewModel @Inject constructor(
	private val tripDao: TripDao,
	private val sessionRepository: SessionRepository,
	private val dailySummaryRepository: DailySummaryRepository,
) : ViewModel() {

	/** Pager producing trips ordered by start_time_ms DESC. */
	val tripsFlow = Pager(
		config = PagingConfig(
			pageSize = 20,
			prefetchDistance = 5,
			initialLoadSize = 40,
			enablePlaceholders = false,
		),
	) { tripDao.getAllPaged() }
		.flow
		.cachedIn(viewModelScope)

	private val _summaryStatsState = MutableStateFlow<StatsLoadState>(StatsLoadState.Idle)
	val summaryStatsState: StateFlow<StatsLoadState> = _summaryStatsState.asStateFlow()

	private val _weeklyStatsState = MutableStateFlow<StatsLoadState>(StatsLoadState.Idle)
	val weeklyStatsState: StateFlow<StatsLoadState> = _weeklyStatsState.asStateFlow()

	private val _weeklyBars = MutableStateFlow<List<DayBar>>(emptyList())
	val weeklyBars: StateFlow<List<DayBar>> = _weeklyBars.asStateFlow()

	init {
		loadWeeklyBars()
	}

	private fun loadWeeklyBars() {
		viewModelScope.launch {
			val todayEpochDay = LocalDate.now().toEpochDay()
			val fromDay = todayEpochDay - 6
			dailySummaryRepository.getBetween(fromDay, todayEpochDay).fold(
				ifLeft = { _weeklyBars.value = emptyList() },
				ifRight = { summaries ->
					val summaryMap = summaries.associateBy { it.dayEpoch }
					val dayFormatter = DateTimeFormatter.ofPattern("EEE")
					val bars = (0L..6L).map { offset ->
						val epochDay = fromDay + offset
						val date = LocalDate.ofEpochDay(epochDay)
						DayBar(
							dayLabel = date.format(dayFormatter),
							distanceM = summaryMap[epochDay]?.totalDistance?.raw ?: 0f,
							steps = summaryMap[epochDay]?.totalSteps?.raw ?: 0,
							epochDay = epochDay,
						)
					}
					_weeklyBars.value = bars
				},
			)
		}
	}

	/**
	 * Load summary statistics from repository.
	 * Delegates to legacy [SessionRepository] for the summary dialog.
	 */
	fun loadSummaryStats() {
		viewModelScope.launch {
			_summaryStatsState.value = StatsLoadState.Loading
			try {
				val stats = sessionRepository.getSummaryStats()
				_summaryStatsState.value = StatsLoadState.Success(stats)
			} catch (e: Exception) {
				_summaryStatsState.value = StatsLoadState.Error(
					e.message ?: "Failed to load summary statistics",
				)
			}
		}
	}

	/**
	 * Load weekly statistics from repository.
	 * Delegates to legacy [SessionRepository] for the weekly dialog.
	 */
	fun loadWeeklyStats() {
		viewModelScope.launch {
			_weeklyStatsState.value = StatsLoadState.Loading
			try {
				val stats = sessionRepository.getWeeklyStats()
				_weeklyStatsState.value = StatsLoadState.Success(stats)
			} catch (e: Exception) {
				_weeklyStatsState.value = StatsLoadState.Error(
					e.message ?: "Failed to load weekly statistics",
				)
			}
		}
	}
}
