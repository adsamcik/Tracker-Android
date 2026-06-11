package com.adsamcik.tracker.statistics.presenter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.adsamcik.tracker.shared.model.Trip
import com.adsamcik.tracker.stats.api.repository.DailySummaryRepository
import com.adsamcik.tracker.stats.api.repository.ExplorationRepository
import com.adsamcik.tracker.stats.api.repository.TripPresentationRepository
import com.adsamcik.tracker.statistics.viewmodel.CalendarDayData
import com.adsamcik.tracker.statistics.viewmodel.CalendarState
import com.adsamcik.tracker.statistics.viewmodel.ExplorationStats
import com.adsamcik.tracker.statistics.viewmodel.HistoryTab
import com.adsamcik.tracker.statistics.viewmodel.TimelineEntry
import com.adsamcik.tracker.statistics.viewmodel.TimelineState
import com.adsamcik.tracker.statistics.viewmodel.activityIcon
import com.adsamcik.tracker.statistics.viewmodel.activityLabel
import com.adsamcik.tracker.statistics.viewmodel.formatDistanceLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import javax.inject.Inject

/**
 * Hilt-compatible ViewModel for the History screen using the repository layer.
 * Replaces direct DAO access in [com.adsamcik.tracker.statistics.viewmodel.HistoryViewModel]
 * with stats-layer repository contracts.
 *
 * Outputs: [selectedTab], [timelineState], [calendarState], [pagedTrips], [explorationStats].
 */
@HiltViewModel
class HistoryPresenterViewModel @Inject constructor(
	private val tripPresentationRepository: TripPresentationRepository,
	private val dailySummaryRepository: DailySummaryRepository,
	private val explorationRepository: ExplorationRepository,
) : ViewModel() {

	private val _selectedTab = MutableStateFlow(HistoryTab.TIMELINE)
	val selectedTab: StateFlow<HistoryTab> = _selectedTab.asStateFlow()

	private val _timelineState = MutableStateFlow<TimelineState>(TimelineState.Loading)
	val timelineState: StateFlow<TimelineState> = _timelineState.asStateFlow()

	private val _calendarState = MutableStateFlow(CalendarState())
	val calendarState: StateFlow<CalendarState> = _calendarState.asStateFlow()

	private val _pendingDeletes = MutableStateFlow<Set<Long>>(emptySet())
	val pendingDeletes: StateFlow<Set<Long>> = _pendingDeletes.asStateFlow()

	val explorationStats: StateFlow<ExplorationStats> = explorationRepository
		.observeCellCount(EXPLORATION_ZOOM_LEVEL)
		.map { count -> ExplorationStats(totalCells = count) }
		.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExplorationStats())

	val pagedTrips: Flow<PagingData<Trip>> = Pager(
		config = PagingConfig(pageSize = PAGE_SIZE),
		pagingSourceFactory = { tripPresentationRepository.getPagedTrips() },
	).flow.cachedIn(viewModelScope)

	init {
		loadTimeline()
		loadCalendarMonth(YearMonth.now())
	}

	/**
	 * Switch to a different history tab.
	 */
	fun selectTab(tab: HistoryTab) {
		_selectedTab.value = tab
	}

	/**
	 * Select a day in the calendar. If the day is in a different month,
	 * loads that month's data first.
	 */
	fun selectDay(date: LocalDate) {
		val currentMonth = _calendarState.value.currentMonth
		val newMonth = YearMonth.from(date)
		if (newMonth != currentMonth) {
			loadCalendarMonth(newMonth)
		}
		viewModelScope.launch {
			val startOfDay = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
			val endOfDay = date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant()
				.toEpochMilli()
			val trips = tripPresentationRepository.getTripsBetween(startOfDay, endOfDay)
			val epochDay = date.toEpochDay()
			val summary = dailySummaryRepository.observeBetween(epochDay, epochDay)
				.first()
				.firstOrNull()
			_calendarState.update {
				it.copy(
					selectedDay = date,
					selectedDayDetail = CalendarState.DayDetail(
						totalDistanceM = summary?.totalDistance?.raw ?: 0f,
						totalSteps = summary?.totalSteps?.raw ?: 0,
						tripCount = trips.size,
						trips = trips,
					),
				)
			}
		}
	}

	private fun loadTimeline() {
		viewModelScope.launch {
			val now = System.currentTimeMillis()
			val thirtyDaysAgo = now - THIRTY_DAYS_MS
			val trips = tripPresentationRepository.getTripsBetween(thirtyDaysAgo, now)

			if (trips.isEmpty()) {
				_timelineState.value = TimelineState.Empty
				return@launch
			}

			val entries = mutableListOf<TimelineEntry>()
			val timeFormatter = java.text.SimpleDateFormat("HH:mm", Locale.getDefault())
			val dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

			val tripsByDay = trips.groupBy { trip ->
				Instant.ofEpochMilli(trip.startTimeMs)
					.atZone(ZoneId.systemDefault())
					.toLocalDate()
			}

			// Preload summaries for the date range via repository
			val firstDay = tripsByDay.keys.minOrNull()?.toEpochDay() ?: return@launch
			val lastDay = tripsByDay.keys.maxOrNull()?.toEpochDay() ?: return@launch
			val summaryMap = dailySummaryRepository.observeBetween(firstDay, lastDay)
				.first()
				.associateBy { it.dayEpoch }

			for ((date, dayTrips) in tripsByDay.toSortedMap(compareByDescending { it })) {
				val summary = summaryMap[date.toEpochDay()]
				entries.add(
					TimelineEntry.DaySummaryEntry(
						epochDay = date.toEpochDay(),
						timestampMs = date.atStartOfDay(ZoneId.systemDefault()).toInstant()
							.toEpochMilli(),
						dateLabel = date.format(dateFormatter),
						distanceLabel = formatDistanceLabel(
							summary?.totalDistance?.raw ?: 0f,
						),
						stepsLabel = "${summary?.totalSteps?.raw ?: 0} steps",
						tripCount = dayTrips.size,
					),
				)

				for (trip in dayTrips.sortedByDescending { it.startTimeMs }) {
					entries.add(
						TimelineEntry.TripEntry(
							tripId = trip.id,
							timestampMs = trip.startTimeMs,
							title = activityLabel(trip.primaryActivity),
							subtitle = "${timeFormatter.format(java.util.Date(trip.startTimeMs))}" +
								" - ${timeFormatter.format(java.util.Date(trip.endTimeMs))}",
							timeLabel = formatDistanceLabel(trip.distanceM),
							modeIcon = activityIcon(trip.primaryActivity),
						),
					)
				}
			}

			_timelineState.value = TimelineState.Content(entries)
		}
	}

	private fun loadCalendarMonth(yearMonth: YearMonth) {
		viewModelScope.launch {
			val firstDay = yearMonth.atDay(1).toEpochDay()
			val lastDay = yearMonth.atEndOfMonth().toEpochDay()
			val summaries = dailySummaryRepository.observeBetween(firstDay, lastDay)
				.first()

			val maxDistance = summaries.maxOfOrNull { it.totalDistance.raw } ?: 1f
			val dayData = summaries.associate { summary ->
				val date = LocalDate.ofEpochDay(summary.dayEpoch)
				date to CalendarDayData(
					date = date,
					intensity = if (maxDistance > 0) summary.totalDistance.raw / maxDistance else 0f,
					tripCount = summary.tripCount,
				)
			}

			_calendarState.update {
				it.copy(
					currentMonth = yearMonth,
					dayData = dayData,
					selectedDay = null,
					selectedDayDetail = null,
				)
			}
		}
	}

	/**
	 * Mark a trip as pending deletion. The trip is hidden in the UI
	 * but remains in the database until [confirmDeleteTrip] is called.
	 */
	fun requestDeleteTrip(tripId: Long) {
		_pendingDeletes.value = _pendingDeletes.value + tripId
	}

	/**
	 * Cancel a pending deletion, restoring the trip in the UI.
	 */
	fun undoDeleteTrip(tripId: Long) {
		_pendingDeletes.value = _pendingDeletes.value - tripId
	}

	/**
	 * Permanently delete a trip from the database and clear pending state.
	 */
	fun confirmDeleteTrip(tripId: Long) {
		_pendingDeletes.value = _pendingDeletes.value - tripId
		viewModelScope.launch {
			tripPresentationRepository.deleteTrip(tripId)
		}
	}

	companion object {
		private const val PAGE_SIZE = 20
		private const val THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000
		private const val EXPLORATION_ZOOM_LEVEL = 14
	}
}
