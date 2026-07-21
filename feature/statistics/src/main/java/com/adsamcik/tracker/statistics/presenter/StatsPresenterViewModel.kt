package com.adsamcik.tracker.statistics.presenter

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.time.Clock
import com.adsamcik.tracker.shared.model.Trip
import com.adsamcik.tracker.stats.api.repository.CellSignalRepository
import com.adsamcik.tracker.stats.api.repository.DailySummaryRepository
import com.adsamcik.tracker.stats.api.repository.SessionStatsRepository
import com.adsamcik.tracker.stats.api.repository.TripPresentationRepository
import com.adsamcik.tracker.stats.api.repository.WifiObservationRepository
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.statistics.export.GpxShareHelper
import com.adsamcik.tracker.statistics.viewmodel.CellSignalReportLoadState
import com.adsamcik.tracker.statistics.viewmodel.DayBar
import com.adsamcik.tracker.statistics.viewmodel.StatsLoadState
import com.adsamcik.tracker.statistics.viewmodel.WifiStatsLoadState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
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
	private val cellSignalRepository: CellSignalRepository,
	private val gpxShareHelper: GpxShareHelper,
	private val sessionStatsUiFormatter: SessionStatsUiFormatter,
	private val savedStateHandle: SavedStateHandle,
	private val clock: Clock,
	private val dispatchers: DispatchersProvider,
) : ViewModel() {

	data class DateFilter(
		val startMs: Long,
		val endMs: Long,
	)

	/** Pager producing trips ordered by start_time_ms DESC. */
	private val _activeDateFilter = MutableStateFlow(
		savedStateHandle.toDateFilter(),
	)
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

	private val _cellSignalReportState =
		MutableStateFlow<CellSignalReportLoadState>(CellSignalReportLoadState.Idle)
	val cellSignalReportState: StateFlow<CellSignalReportLoadState> =
		_cellSignalReportState.asStateFlow()

	private val _weeklyBars = MutableStateFlow<List<DayBar>>(emptyList())
	val weeklyBars: StateFlow<List<DayBar>> = _weeklyBars.asStateFlow()

	private val _heatmapData = MutableStateFlow<Map<LocalDate, Float>>(emptyMap())
	val heatmapData: StateFlow<Map<LocalDate, Float>> = _heatmapData.asStateFlow()

	private val todayEpochDay = MutableStateFlow(currentLocalDate().toEpochDay())
	private lateinit var dayRolloverJob: Job

	init {
		observeSummaryWindow()
		dayRolloverJob = observeDayRollovers()
	}

	private fun observeSummaryWindow() {
		viewModelScope.launch {
			todayEpochDay.flatMapLatest { currentTodayEpochDay ->
				val fromDay = currentTodayEpochDay - HEATMAP_DAYS + 1
				dailySummaryRepository.observeBetween(fromDay, currentTodayEpochDay)
					.map { summaries -> currentTodayEpochDay to summaries }
			}.collect { (currentTodayEpochDay, summaries) ->
				_weeklyBars.value = buildWeeklyBars(
					summaries = summaries,
					todayEpochDay = currentTodayEpochDay,
				)
				_heatmapData.value = buildHeatmapData(summaries)
			}
		}
	}

	private fun observeDayRollovers() = viewModelScope.launch(dispatchers.default) {
		while (true) {
			val nowMs = clock.currentTimeMillis()
			val currentDate = localDateAt(nowMs)
			val nextDayStartMs = currentDate.plusDays(1)
				.atStartOfDay(ZoneId.systemDefault())
				.toInstant()
				.toEpochMilli()
			delay((nextDayStartMs - nowMs).coerceAtLeast(1L))
			refreshToday()
		}
	}

	internal fun refreshToday() {
		todayEpochDay.value = currentLocalDate().toEpochDay()
	}

	internal fun cancelDayRolloverObservation() {
		dayRolloverJob.cancel()
	}

	private fun currentLocalDate(): LocalDate = localDateAt(clock.currentTimeMillis())

	private fun localDateAt(epochMs: Long): LocalDate = Instant.ofEpochMilli(epochMs)
		.atZone(ZoneId.systemDefault())
		.toLocalDate()

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
			val now = clock.currentTimeMillis()
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

	fun loadCellSignalReport() {
		viewModelScope.launch {
			_cellSignalReportState.value = CellSignalReportLoadState.Loading
			cellSignalRepository.getReport().fold(
				ifLeft = { error ->
					_cellSignalReportState.value = CellSignalReportLoadState.Error(error.message)
				},
				ifRight = { report ->
					_cellSignalReportState.value = if (report.totalSamples == 0L) {
						CellSignalReportLoadState.Empty
					} else {
						CellSignalReportLoadState.Success(report)
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
		savedStateHandle[KEY_DATE_FILTER_START_MS] = startMs
		savedStateHandle[KEY_DATE_FILTER_END_MS] = endMs
	}

	fun clearDateRange() {
		_activeDateFilter.value = null
		savedStateHandle[KEY_DATE_FILTER_START_MS] = null
		savedStateHandle[KEY_DATE_FILTER_END_MS] = null
	}

	private fun SavedStateHandle.toDateFilter(): DateFilter? {
		val startMs = get<Long>(KEY_DATE_FILTER_START_MS) ?: return null
		val endMs = get<Long>(KEY_DATE_FILTER_END_MS) ?: return null
		return DateFilter(startMs = startMs, endMs = endMs)
	}

	companion object {
		/** ~26 weeks of heatmap history. */
		private const val HEATMAP_DAYS = 182L
		private const val KEY_DATE_FILTER_START_MS = "stats_date_filter_start_ms"
		private const val KEY_DATE_FILTER_END_MS = "stats_date_filter_end_ms"
	}
}
