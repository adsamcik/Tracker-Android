package com.adsamcik.tracker.statistics.presenter

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import com.adsamcik.tracker.shared.base.database.data.Trip
import com.adsamcik.tracker.stats.api.repository.DailySummaryRepository
import com.adsamcik.tracker.stats.api.repository.SessionStatsRepository
import com.adsamcik.tracker.stats.api.repository.TripPresentationRepository
import com.adsamcik.tracker.stats.api.repository.WifiObservationRepository
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.statistics.export.GpxShareHelper
import com.adsamcik.tracker.statistics.viewmodel.DayBar
import com.adsamcik.tracker.statistics.viewmodel.StatsLoadState
import com.adsamcik.tracker.statistics.viewmodel.WifiStatsLoadState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import java.util.Calendar
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

/**
 * Hilt-compatible ViewModel that bridges the new repository layer with
 * the paging and dialog patterns the statistics UI expects.
 *
 * - Uses [TripPresentationRepository] for trip list paging and deletion.
 * - Uses [DailySummaryRepository] for weekly bar chart data.
 * - Uses [SessionStatsRepository] for summary/weekly dialog data and local UI formatting.
 * - Uses [WifiObservationRepository] for Wi-Fi summary dialog data.
 */
@HiltViewModel
class StatsPresenterViewModel @Inject constructor(
	private val tripPresentationRepository: TripPresentationRepository,
	private val sessionStatsRepository: SessionStatsRepository,
	private val dailySummaryRepository: DailySummaryRepository,
	private val wifiObservationRepository: WifiObservationRepository,
	private val gpxShareHelper: GpxShareHelper,
	private val sessionStatsUiFormatter: SessionStatsUiFormatter,
) : ViewModel() {

	data class DateFilter(
		val startMs: Long,
		val endMs: Long,
	)

	/** Pager producing trips ordered by start_time_ms DESC. */
	private val _activeDateFilter = MutableStateFlow<DateFilter?>(null)
	val activeDateFilter: StateFlow<DateFilter?> = _activeDateFilter.asStateFlow()

	val tripsFlow = activeDateFilter
		.flatMapLatest { dateFilter ->
			Pager(
				config = PagingConfig(
					pageSize = 20,
					prefetchDistance = 5,
					initialLoadSize = 40,
					enablePlaceholders = false,
				),
			) {
				if (dateFilter == null) {
					tripPresentationRepository.getPagedTrips()
				} else {
					tripPresentationRepository.getPagedTripsOverlapping(
						dateFilter.startMs,
						dateFilter.endMs,
					)
				}
			}.flow
		}
		.cachedIn(viewModelScope)

	private val _summaryStatsState = MutableStateFlow<StatsLoadState>(StatsLoadState.Idle)
	val summaryStatsState: StateFlow<StatsLoadState> = _summaryStatsState.asStateFlow()

	private val _weeklyStatsState = MutableStateFlow<StatsLoadState>(StatsLoadState.Idle)
	val weeklyStatsState: StateFlow<StatsLoadState> = _weeklyStatsState.asStateFlow()

	private val _wifiStatsState = MutableStateFlow<WifiStatsLoadState>(WifiStatsLoadState.Idle)
	val wifiStatsState: StateFlow<WifiStatsLoadState> = _wifiStatsState.asStateFlow()

	private val _weeklyBars = MutableStateFlow<List<DayBar>>(emptyList())
	val weeklyBars: StateFlow<List<DayBar>> = _weeklyBars.asStateFlow()

	private val _heatmapData = MutableStateFlow<Map<LocalDate, Float>>(emptyMap())
	val heatmapData: StateFlow<Map<LocalDate, Float>> = _heatmapData.asStateFlow()

	init {
		observeSummaryWindow()
	}

	private fun observeSummaryWindow() {
		viewModelScope.launch {
			val todayEpochDay = LocalDate.now().toEpochDay()
			val fromDay = todayEpochDay - HEATMAP_DAYS + 1
			dailySummaryRepository.observeBetween(fromDay, todayEpochDay).collect { summaries ->
				_weeklyBars.value = buildWeeklyBars(
					summaries = summaries,
					todayEpochDay = todayEpochDay,
				)
				_heatmapData.value = buildHeatmapData(summaries)
			}
		}
	}

	private fun buildWeeklyBars(
		summaries: List<com.adsamcik.tracker.stats.api.repository.DailySummary>,
		todayEpochDay: Long,
	): List<DayBar> {
		val fromDay = todayEpochDay - 6
		val summaryMap = summaries.associateBy { it.dayEpoch }
		val dayFormatter = DateTimeFormatter.ofPattern("EEE")
		return (0L..6L).map { offset ->
			val epochDay = fromDay + offset
			val date = LocalDate.ofEpochDay(epochDay)
			DayBar(
				dayLabel = date.format(dayFormatter),
				distanceM = summaryMap[epochDay]?.totalDistance?.raw ?: 0f,
				steps = summaryMap[epochDay]?.totalSteps?.raw ?: 0,
				epochDay = epochDay,
				sessionCount = summaryMap[epochDay]?.tripCount ?: 0,
				durationMs = summaryMap[epochDay]?.totalDuration?.raw ?: 0L,
			)
		}
	}

	private fun buildHeatmapData(
		summaries: List<com.adsamcik.tracker.stats.api.repository.DailySummary>,
	): Map<LocalDate, Float> {
		if (summaries.isEmpty()) {
			return emptyMap()
		}
		val maxDistance = summaries.maxOf { it.totalDistance.raw }.coerceAtLeast(1f)
		val maxDuration = summaries.maxOf { it.totalDuration.raw }.coerceAtLeast(1L)
		return summaries.associate { summary ->
			val distanceIntensity = summary.totalDistance.raw / maxDistance
			val durationIntensity = summary.totalDuration.raw.toFloat() / maxDuration.toFloat()
			val intensity = maxOf(distanceIntensity, durationIntensity)
			LocalDate.ofEpochDay(summary.dayEpoch) to intensity.coerceIn(0f, 1f)
		}
	}

	/**
	 * Load summary statistics from repository and format them for the summary dialog.
	 */
	fun loadSummaryStats() {
		viewModelScope.launch {
			_summaryStatsState.value = StatsLoadState.Loading
			sessionStatsRepository.getAllTime().fold(
				ifLeft = { error -> _summaryStatsState.value = StatsLoadState.Error(error.message) },
				ifRight = { snapshot ->
					_summaryStatsState.value = StatsLoadState.Success(
						sessionStatsUiFormatter.formatSummary(snapshot),
					)
				},
			)
		}
	}

	/**
	 * Load weekly statistics from repository and format them for the weekly dialog.
	 */
	fun loadWeeklyStats() {
		viewModelScope.launch {
			_weeklyStatsState.value = StatsLoadState.Loading
			val now = System.currentTimeMillis()
			val weekAgo = Calendar.getInstance(Locale.getDefault()).apply {
				timeInMillis = now
				add(Calendar.WEEK_OF_MONTH, -1)
			}.timeInMillis
			sessionStatsRepository.getBetween(EpochMs(weekAgo), EpochMs(now)).fold(
				ifLeft = { error -> _weeklyStatsState.value = StatsLoadState.Error(error.message) },
				ifRight = { snapshot ->
					_weeklyStatsState.value = StatsLoadState.Success(
						sessionStatsUiFormatter.formatWeekly(snapshot),
					)
				},
			)
		}
	}

	fun loadWifiStats() {
		viewModelScope.launch {
			_wifiStatsState.value = WifiStatsLoadState.Loading
			wifiObservationRepository.getStatsSummary().fold(
				ifLeft = { error ->
					_wifiStatsState.value = WifiStatsLoadState.Error(error.message)
				},
				ifRight = { summary ->
					_wifiStatsState.value = if (summary.totalScans == 0L && summary.uniqueNetworks == 0L) {
						WifiStatsLoadState.Empty
					} else {
						WifiStatsLoadState.Success(summary)
					}
				},
			)
		}
	}

	/**
	 * Export a trip from the stats list as GPX and open the share sheet.
	 */
	fun exportTripGpx(context: Context, trip: Trip) {
		viewModelScope.launch {
			gpxShareHelper.exportAndShare(
				context = context,
				tripId = trip.id,
				startTimeMs = trip.startTimeMs,
				endTimeMs = trip.endTimeMs,
			)
		}
	}

	fun deleteTrip(tripId: Long) {
		viewModelScope.launch {
			tripPresentationRepository.deleteTrip(tripId)
		}
	}

	fun setDateRange(startMs: Long, endMs: Long) {
		_activeDateFilter.value = DateFilter(startMs = startMs, endMs = endMs)
	}

	fun clearDateRange() {
		_activeDateFilter.value = null
	}

	companion object {
		/** ~26 weeks of heatmap history. */
		private const val HEATMAP_DAYS = 182L
	}
}
