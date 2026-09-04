package com.adsamcik.tracker.statistics.presenter

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.adsamcik.tracker.shared.model.Trip
import com.adsamcik.tracker.stats.api.repository.DailySummaryRepository
import com.adsamcik.tracker.stats.api.repository.ExplorationRepository
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummary
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummaryRepository
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummaryRequest
import com.adsamcik.tracker.stats.api.repository.StepsNumericUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.TripPresentationRepository
import com.adsamcik.tracker.statistics.viewmodel.CalendarDayData
import com.adsamcik.tracker.statistics.viewmodel.CalendarState
import com.adsamcik.tracker.statistics.viewmodel.ExplorationStats
import com.adsamcik.tracker.statistics.viewmodel.HistoryTab
import com.adsamcik.tracker.statistics.viewmodel.HistoryStepsValue
import com.adsamcik.tracker.statistics.viewmodel.TimelineEntry
import com.adsamcik.tracker.statistics.viewmodel.TimelineState
import com.adsamcik.tracker.statistics.viewmodel.activityIcon
import com.adsamcik.tracker.statistics.viewmodel.activityLabel
import com.adsamcik.tracker.statistics.viewmodel.formatDistanceLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
	private val stepsNumericSummaryRepository: StepsNumericSummaryRepository,
	private val explorationRepository: ExplorationRepository,
	private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

	data class PendingDeleteEvent(val tripId: Long)

	private val _selectedTab = MutableStateFlow(
		savedStateHandle.get<String>(KEY_SELECTED_TAB)
			?.let { savedName -> HistoryTab.entries.firstOrNull { it.name == savedName } }
			?: HistoryTab.TIMELINE,
	)
	val selectedTab: StateFlow<HistoryTab> = _selectedTab.asStateFlow()

	private val _timelineState = MutableStateFlow<TimelineState>(TimelineState.Loading)
	val timelineState: StateFlow<TimelineState> = _timelineState.asStateFlow()

	private val restoredCalendarMonth = savedStateHandle.get<String>(KEY_CALENDAR_MONTH)
		?.let { savedMonth -> runCatching { YearMonth.parse(savedMonth) }.getOrNull() }
		?: YearMonth.now()
	private val restoredSelectedDay = savedStateHandle.get<Long>(KEY_CALENDAR_SELECTED_DAY)
		?.let(LocalDate::ofEpochDay)

	private val _calendarState = MutableStateFlow(
		CalendarState(
			currentMonth = restoredCalendarMonth,
			selectedDay = restoredSelectedDay,
		),
	)
	val calendarState: StateFlow<CalendarState> = _calendarState.asStateFlow()
	private var calendarRequestGeneration = 0L
	private val calendarRequest = MutableStateFlow(
		CalendarRequest(
			generation = calendarRequestGeneration,
			month = restoredCalendarMonth,
			selectedDay = restoredSelectedDay
				?.takeIf { YearMonth.from(it) == restoredCalendarMonth },
		),
	)

	private val _pendingDeletes = MutableStateFlow(
		savedStateHandle.get<LongArray>(KEY_PENDING_DELETE_IDS)?.toSet().orEmpty(),
	)
	val pendingDeletes: StateFlow<Set<Long>> = _pendingDeletes.asStateFlow()

	private val _pendingDeleteEvents = MutableSharedFlow<PendingDeleteEvent>(
		extraBufferCapacity = PENDING_DELETE_EVENT_BUFFER_CAPACITY,
	)
	val pendingDeleteEvents: SharedFlow<PendingDeleteEvent> = _pendingDeleteEvents.asSharedFlow()

	private val pendingDeleteJobs = mutableMapOf<Long, Job>()
	private val resolvingDeletes = mutableSetOf<Long>()

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
		viewModelScope.launch {
			calendarRequest.collectLatest(::loadCalendarRequest)
		}
		restorePendingDeletes()
	}

	/**
	 * Switch to a different history tab.
	 */
	fun selectTab(tab: HistoryTab) {
		_selectedTab.value = tab
		savedStateHandle[KEY_SELECTED_TAB] = tab.name
	}

	/**
	 * Select a day in the calendar. If the day is in a different month,
	 * loads that month's data first.
	 */
	fun selectDay(date: LocalDate) {
		val newMonth = YearMonth.from(date)
		savedStateHandle[KEY_CALENDAR_MONTH] = newMonth.toString()
		savedStateHandle[KEY_CALENDAR_SELECTED_DAY] = date.toEpochDay()
		_calendarState.update {
			it.copy(selectedDay = date, selectedDayDetail = null)
		}
		calendarRequest.value = CalendarRequest(
			generation = ++calendarRequestGeneration,
			month = newMonth,
			selectedDay = date,
		)
	}

	/** Navigate without inventing a selected boundary day for the target month. */
	fun selectMonth(month: YearMonth) {
		savedStateHandle[KEY_CALENDAR_MONTH] = month.toString()
		savedStateHandle.remove<Long>(KEY_CALENDAR_SELECTED_DAY)
		_calendarState.update {
			it.copy(selectedDay = null, selectedDayDetail = null)
		}
		calendarRequest.value = CalendarRequest(
			generation = ++calendarRequestGeneration,
			month = month,
			selectedDay = null,
		)
	}

	private suspend fun loadCalendarRequest(request: CalendarRequest) {
		if (request.month != _calendarState.value.currentMonth || _calendarState.value.dayData.isEmpty()) {
			loadCalendarMonth(request.month, request)
		}
		request.selectedDay?.let { observeSelectedDay(it, request) }
	}

	private suspend fun observeSelectedDay(date: LocalDate, request: CalendarRequest) {
		val startOfDay = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
		val endOfDay = date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant()
			.toEpochMilli()
		val trips = tripPresentationRepository.getTripsBetween(startOfDay, endOfDay)
		val epochDay = date.toEpochDay()
		val summary = dailySummaryRepository.observeBetween(epochDay, epochDay)
			.first()
			.firstOrNull()
		if (calendarRequest.value != request) return
		_calendarState.update {
			it.copy(
				selectedDay = date,
				selectedDayDetail = CalendarState.DayDetail(
					totalDistanceM = summary?.totalDistance?.raw ?: 0f,
					steps = HistoryStepsValue.Materializing,
					tripCount = trips.size,
					trips = trips,
				),
			)
		}
		stepsNumericSummaryRepository.observe(stepsRequest(epochDay, epochDay)).collect { summary ->
			if (calendarRequest.value == request) {
				_calendarState.update { state ->
					state.copy(
						selectedDayDetail = state.selectedDayDetail?.copy(
							steps = summary.forDay(epochDay),
						),
					)
				}
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

	private suspend fun loadCalendarMonth(
		yearMonth: YearMonth,
		request: CalendarRequest,
	) {
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

		if (calendarRequest.value == request) {
			_calendarState.update {
				it.copy(
					currentMonth = yearMonth,
					dayData = dayData,
					selectedDay = request.selectedDay,
					selectedDayDetail = null,
				)
			}
		}
	}

	private fun stepsRequest(
		firstEpochDay: Long,
		lastEpochDayInclusive: Long,
	) = StepsNumericSummaryRequest(
		firstEpochDay = firstEpochDay,
		lastEpochDayInclusive = lastEpochDayInclusive,
		fallbackCalendarZoneId = ZoneId.systemDefault().id,
	)

	/**
	 * Mark a trip as pending deletion and start the ViewModel-owned undo deadline.
	 * The trip is hidden immediately and committed even if the UI leaves composition.
	 */
	fun requestDeleteTrip(tripId: Long) {
		if (tripId in _pendingDeletes.value) return

		_pendingDeletes.value = _pendingDeletes.value + tripId
		persistPendingDeletes()
		_pendingDeleteEvents.tryEmit(PendingDeleteEvent(tripId))
		pendingDeleteJobs[tripId] = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
			try {
				delay(UNDO_DELETE_TIMEOUT_MS)
			} finally {
				if (tripId in _pendingDeletes.value) {
					withContext(NonCancellable) {
						commitPendingDelete(tripId)
					}
				}
			}
		}
	}

	/**
	 * Cancel a pending deletion, restoring the trip in the UI.
	 */
	fun undoDeleteTrip(tripId: Long) {
		if (tripId in resolvingDeletes) return

		_pendingDeletes.value = _pendingDeletes.value - tripId
		persistPendingDeletes()
		pendingDeleteJobs.remove(tripId)?.cancel()
	}

	/**
	 * Permanently delete a trip from the database and clear pending state.
	 */
	fun confirmDeleteTrip(tripId: Long) {
		val deadlineJob = pendingDeleteJobs.remove(tripId)
		if (deadlineJob != null) {
			deadlineJob.cancel()
		} else if (tripId in _pendingDeletes.value) {
			launchImmediateCommit(tripId)
		}
	}

	private fun restorePendingDeletes() {
		_pendingDeletes.value.forEach { tripId ->
			launchImmediateCommit(tripId)
		}
	}

	private fun launchImmediateCommit(tripId: Long) {
		viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
			withContext(NonCancellable) {
				commitPendingDelete(tripId)
			}
		}
	}

	private suspend fun commitPendingDelete(tripId: Long) {
		if (tripId !in _pendingDeletes.value || !resolvingDeletes.add(tripId)) return

		try {
			tripPresentationRepository.deleteTrip(tripId)
			_pendingDeletes.value = _pendingDeletes.value - tripId
			persistPendingDeletes()
			pendingDeleteJobs.remove(tripId)
		} finally {
			resolvingDeletes.remove(tripId)
		}
	}

	private fun persistPendingDeletes() {
		savedStateHandle[KEY_PENDING_DELETE_IDS] = _pendingDeletes.value.toLongArray()
	}

	companion object {
		private const val PAGE_SIZE = 20
		private const val THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000
		private const val EXPLORATION_ZOOM_LEVEL = 14
		private const val PENDING_DELETE_EVENT_BUFFER_CAPACITY = 16

		// Material 3 SnackbarDuration.Long, used by the app's existing undo snackbars, is 10 seconds.
		internal const val UNDO_DELETE_TIMEOUT_MS = 10_000L

		private const val KEY_SELECTED_TAB = "history_selected_tab"
		private const val KEY_CALENDAR_MONTH = "history_calendar_month"
		private const val KEY_CALENDAR_SELECTED_DAY = "history_calendar_selected_day"
		internal const val KEY_PENDING_DELETE_IDS = "history_pending_delete_ids"
	}
}

private data class CalendarRequest(
	val generation: Long,
	val month: YearMonth,
	val selectedDay: LocalDate?,
)

/** Never converts a nonnumeric retained Steps result into zero. */
internal fun StepsNumericSummary.forDay(epochDay: Long): HistoryStepsValue = when (this) {
	is StepsNumericSummary.Ready -> days.firstOrNull { it.epochDay == epochDay }
		?.let { HistoryStepsValue.Ready(it.steps) }
		?: HistoryStepsValue.Unavailable(StepsNumericUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
	StepsNumericSummary.Materializing -> HistoryStepsValue.Materializing
	is StepsNumericSummary.Unverifiable -> HistoryStepsValue.Unavailable(reason)
}
