package com.adsamcik.tracker.statistics.presenter

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.adsamcik.tracker.shared.model.Trip
import com.adsamcik.tracker.stats.api.repository.ExplorationRepository
import com.adsamcik.tracker.stats.api.repository.TripPresentationRepository
import com.adsamcik.tracker.stats.api.repository.PagedTrackingHistoryRepository
import com.adsamcik.tracker.stats.api.repository.HistoryRange
import com.adsamcik.tracker.stats.api.repository.HistoryRangeRequest
import com.adsamcik.tracker.stats.api.repository.HistoryRangeRead
import com.adsamcik.tracker.stats.api.repository.HistoryContinuation
import com.adsamcik.tracker.stats.api.repository.HistoryProblem
import com.adsamcik.tracker.stats.api.repository.HistoryProblemReason
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryReadSnapshot
import com.adsamcik.tracker.stats.api.repository.HistoryBrowseMode
import com.adsamcik.tracker.stats.api.repository.HistoryPagingItem
import com.adsamcik.tracker.statistics.viewmodel.CalendarDayData
import com.adsamcik.tracker.statistics.viewmodel.CalendarState
import com.adsamcik.tracker.statistics.viewmodel.ExplorationStats
import com.adsamcik.tracker.statistics.viewmodel.HistoryTab
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

/**
 * Hilt-compatible ViewModel for the History screen using the repository layer.
 * Replaces direct DAO access in [com.adsamcik.tracker.statistics.viewmodel.HistoryViewModel]
 * with stats-layer repository contracts.
 *
 * Observation paging is shared by Timeline and the selected Calendar day.
 * Exact session/recording actions retain their existing repository.
 */
@HiltViewModel
class HistoryPresenterViewModel @Inject constructor(
	private val tripPresentationRepository: TripPresentationRepository,
	private val trackingHistoryRepository: PagedTrackingHistoryRepository,
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

	private val restoredCalendarMonth = savedStateHandle.get<String>(KEY_CALENDAR_MONTH)
		?.let { savedMonth -> runCatching { YearMonth.parse(savedMonth) }.getOrNull() }
		?: YearMonth.now()
	private val restoredSelectedDay = savedStateHandle.get<Long>(KEY_CALENDAR_SELECTED_DAY)
		?.let { runCatching { LocalDate.ofEpochDay(it) }.getOrNull() }
		?.takeIf { YearMonth.from(it) == restoredCalendarMonth }

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

	private val viewZone = MutableStateFlow(ZoneId.systemDefault())

	@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
	val timelineHistory: Flow<PagingData<HistoryPagingItem>> = viewZone
		.flatMapLatest { zone -> observationPages(HistoryRange(
			fromInclusiveMs = 0L,
			toExclusiveMs = Long.MAX_VALUE,
			viewZoneId = zone.id,
			mode = HistoryBrowseMode.OBSERVATIONS,
		)) }.cachedIn(viewModelScope)

	@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
	val calendarHistory: Flow<PagingData<HistoryPagingItem>> = combine(
		calendarRequest.map { it.selectedDay }.distinctUntilChanged(), viewZone,
	) { day, zone -> day?.let { measuredDayRange(it, zone) } }.flatMapLatest { range ->
		if (range == null) {
			flowOf(PagingData.empty())
		} else {
			observationPages(range)
		}
	}.cachedIn(viewModelScope)

	private fun observationPages(range: HistoryRange): Flow<PagingData<HistoryPagingItem>> = Pager(
		config = PagingConfig(pageSize = OBSERVATION_PAGE_SIZE, enablePlaceholders = false),
		pagingSourceFactory = { trackingHistoryRepository.pagingSource(range) },
	).flow

	/** Display dates follow the current phone zone; saved UTC times remain unchanged. */
	fun useViewZone(zone: ZoneId) { viewZone.value = zone }

	private val monthRetry = MutableStateFlow(0L)

    /** Restarts the selected calendar month read without changing its month or display zone. */
    fun retryCalendarMonth() { monthRetry.value++ }

	init {
		viewModelScope.launch {
            combine(_selectedTab, calendarRequest.map { it.month }.distinctUntilChanged(), viewZone, monthRetry) {
                tab, month, zone, _ -> Triple(tab, month, zone)
            }.collectLatest { (tab, month, zone) ->
                if (tab == HistoryTab.CALENDAR) {
                    observeCalendarMonth(month, zone)
                }
            }
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
			it.copy(currentMonth = newMonth, selectedDay = date, selectedDayDetail = null,
                dayData = if (it.currentMonth == newMonth) {
                    it.dayData
                } else {
                    emptyMap()
                },
                monthProblems = if (it.currentMonth == newMonth) {
                    it.monthProblems
                } else {
                    emptySet()
                })
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
			it.copy(currentMonth = month, selectedDay = null, selectedDayDetail = null, dayData = emptyMap(),
                monthProblems = emptySet())
		}
		calendarRequest.value = CalendarRequest(
			generation = ++calendarRequestGeneration,
			month = month,
			selectedDay = null,
		)
	}

    /** One existing qualified month observation supplies Room invalidation and deletion/epoch signals. */
    private suspend fun observeCalendarMonth(month: YearMonth, zone: ZoneId) {
        updateCalendarMonth(month, zone, emptyMap(), emptySet(), true)
        val from = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli().coerceAtLeast(0L)
        val to = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        if (to <= from) {
            updateCalendarMonth(month, zone, emptyMap(), emptySet(), false)
            return
        }
        val range = HistoryRange(from, to, zone.id, mode = HistoryBrowseMode.OBSERVATIONS)
        try {
            trackingHistoryRepository.observeRange(HistoryRangeRequest(range, limit = 1)).collectLatest { first ->
                updateCalendarMonth(month, zone, emptyMap(), emptySet(), true)
                if (first is HistoryRangeRead.Page) {
                    readCalendarMonthDays(month, zone, first.snapshot)
                }
                else {
                    updateCalendarMonth(month, zone, emptyMap(), setOf(HistoryProblem(null,
                    (first as HistoryRangeRead.Unavailable).reason)), false)
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            updateCalendarMonth(month, zone, emptyMap(), setOf(HistoryProblem(null,
                HistoryProblemReason.STORAGE_UNAVAILABLE)), false)
        }
    }

    /** Each round reads at most one existing 100-key page per day and retains only day presence/frontiers. */
    private suspend fun readCalendarMonthDays(month: YearMonth, zone: ZoneId, snapshot: TrackingHistoryReadSnapshot) {
        val days = linkedMapOf<LocalDate, CalendarDayData>()
        val problems = linkedSetOf<HistoryProblem>()
        val pending = linkedMapOf<LocalDate, HistoryContinuation?>()
        for (number in month.lengthOfMonth() downTo 1) {
            val day = month.atDay(number)
            if (measuredDayRange(day, zone) != null) {
                pending[day] = null
            }
        }
        while (pending.isNotEmpty()) {
            for (day in pending.keys.toList()) {
                currentCoroutineContext().ensureActive()
                val range = requireNotNull(measuredDayRange(day, zone))
                val after = pending[day]
                val read = trackingHistoryRepository.readCalendarDay(range, after)
                when (read) {
                    is HistoryRangeRead.Unavailable -> {
                        problems += HistoryProblem(null, read.reason)
                        pending.remove(day)
                    }
                    is HistoryRangeRead.Page -> {
                        if (read.snapshot != snapshot) {
                            updateCalendarMonth(month, zone, emptyMap(), setOf(HistoryProblem(null,
                                HistoryProblemReason.INTEGRITY_UNAVAILABLE)), false)
                            return
                        }
                        problems += read.problems
                        advanceCalendarDay(day, range, after, read, days, pending)?.let { problems += it }
                    }
                }
                updateCalendarMonth(month, zone, days.toMap(), problems.toSet(), pending.isNotEmpty())
                yield()
            }
        }
        updateCalendarMonth(month, zone, days.toMap(), problems.toSet(), false)
    }


    private fun updateCalendarMonth(month: YearMonth, zone: ZoneId, days: Map<LocalDate, CalendarDayData>,
        problems: Set<HistoryProblem>, loading: Boolean) {
        if (calendarRequest.value.month == month && viewZone.value == zone &&
            _selectedTab.value == HistoryTab.CALENDAR) {
            _calendarState.update { it.copy(currentMonth = month, dayData = days, monthProblems =
                problems, monthLoading = loading) }
        }
    }

	/**
	 * Mark a trip as pending deletion and start the ViewModel-owned undo deadline.
	 * The trip is hidden immediately and committed even if the UI leaves composition.
	 */
	fun requestDeleteTrip(tripId: Long) {
		if (tripId in _pendingDeletes.value) {
			return
		}

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
		if (tripId in resolvingDeletes) {
			return
		}

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
		if (tripId !in _pendingDeletes.value || !resolvingDeletes.add(tripId)) {
			return
		}

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
		private const val OBSERVATION_PAGE_SIZE = 50
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


private suspend fun PagedTrackingHistoryRepository.readCalendarDay(
    range: HistoryRange, after: HistoryContinuation?,
): HistoryRangeRead = try {
    readRange(HistoryRangeRequest(range,
        limit = if (after == null) { 1 } else { 100 }, after = after))
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    HistoryRangeRead.Unavailable(HistoryProblemReason.STORAGE_UNAVAILABLE)
}

private fun advanceCalendarDay(
    day: LocalDate,
    range: HistoryRange,
    after: HistoryContinuation?,
    read: HistoryRangeRead.Page,
    days: MutableMap<LocalDate, CalendarDayData>,
    pending: MutableMap<LocalDate, HistoryContinuation?>,
): HistoryProblem? {
    val next = read.next
    if (read.rows.any { it.measurement != null }) {
        days[day] = CalendarDayData(day)
        pending.remove(day)
    } else if (next == null) {
        pending.remove(day)
    } else if (next.range != range || next == after) {
        pending.remove(day)
        return HistoryProblem(null, HistoryProblemReason.INTEGRITY_UNAVAILABLE)
    } else {
        pending[day] = next
    }
    return null
}


/** A day before the supported UTC epoch contains no saved observation. */
internal fun measuredDayRange(day: LocalDate, zone: ZoneId): HistoryRange? {
	val start = day.atStartOfDay(zone).toInstant().toEpochMilli().coerceAtLeast(0L)
	val end = day.plusDays(1L).atStartOfDay(zone).toInstant().toEpochMilli()
	return if (end <= start) {
		null
	} else {
		HistoryRange(start, end, zone.id,
		mode = HistoryBrowseMode.OBSERVATIONS)
	}
}
