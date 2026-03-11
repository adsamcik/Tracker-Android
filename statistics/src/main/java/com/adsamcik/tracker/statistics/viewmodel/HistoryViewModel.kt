package com.adsamcik.tracker.statistics.viewmodel

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.Train
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.adsamcik.tracker.shared.base.database.dao.DailySummaryDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationCellDao
import com.adsamcik.tracker.shared.base.database.dao.TripDao
import com.adsamcik.tracker.shared.base.database.data.Trip
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
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
 * Tabs available in the History screen.
 */
enum class HistoryTab { TIMELINE, TRIPS, CALENDAR }

/**
 * Individual entry within the timeline view.
 */
@Immutable
sealed interface TimelineEntry {
	val id: String
	val timestampMs: Long

	data class TripEntry(
		val tripId: Long,
		override val timestampMs: Long,
		val title: String,
		val subtitle: String,
		val timeLabel: String,
		val modeIcon: ImageVector,
		val isDistancePlausible: Boolean = true,
	) : TimelineEntry {
		override val id: String get() = "trip_$tripId"
	}

	data class DiscoveryEntry(
		override val id: String,
		override val timestampMs: Long,
		val title: String,
		val subtitle: String,
	) : TimelineEntry

	data class DaySummaryEntry(
		val epochDay: Long,
		override val timestampMs: Long,
		val dateLabel: String,
		val distanceLabel: String,
		val stepsLabel: String,
		val tripsLabel: String,
	) : TimelineEntry {
		override val id: String get() = "day_$epochDay"
	}
}

/**
 * State of the timeline tab content.
 */
@Immutable
sealed interface TimelineState {
	data object Loading : TimelineState
	data object Empty : TimelineState
	data class Content(val entries: List<TimelineEntry>) : TimelineState
}

/**
 * Data for a single day in the calendar view.
 */
@Immutable
data class CalendarDayData(
	val date: LocalDate,
	val intensity: Float,
	val tripCount: Int,
)

/**
 * State of the calendar tab content.
 */
@Immutable
data class CalendarState(
	val currentMonth: YearMonth = YearMonth.now(),
	val dayData: Map<LocalDate, CalendarDayData> = emptyMap(),
	val selectedDay: LocalDate? = null,
	val selectedDayDetail: DayDetail? = null,
) {
	/**
	 * Detail for a selected day showing trips and aggregate stats.
	 */
	@Immutable
	data class DayDetail(
		val totalDistanceM: Float,
		val totalSteps: Int,
		val tripCount: Int,
		val trips: List<Trip>,
	)
}

/**
 * Exploration progress stats for the exploration card.
 */
@Immutable
data class ExplorationStats(
	val totalCells: Int = 0,
	val currentStreak: Int = 0,
	val bestStreak: Int = 0,
)

/**
 * ViewModel for the History screen with timeline, trips, and calendar tabs.
 *
 * Inputs: TripDao, DailySummaryDao, ExplorationCellDao via Hilt injection.
 * Outputs: [selectedTab], [timelineState], [calendarState], [pagedTrips], [explorationStats].
 * Failure modes: Empty states displayed when no data is available.
 */
@HiltViewModel
class HistoryViewModel @Inject constructor(
	private val tripDao: TripDao,
	private val dailySummaryDao: DailySummaryDao,
	private val explorationCellDao: ExplorationCellDao,
) : ViewModel() {

	private val _selectedTab = MutableStateFlow(HistoryTab.TIMELINE)
	val selectedTab: StateFlow<HistoryTab> = _selectedTab.asStateFlow()

	private val _timelineState = MutableStateFlow<TimelineState>(TimelineState.Loading)
	val timelineState: StateFlow<TimelineState> = _timelineState.asStateFlow()

	private val _calendarState = MutableStateFlow(CalendarState())
	val calendarState: StateFlow<CalendarState> = _calendarState.asStateFlow()

	val explorationStats: StateFlow<ExplorationStats> = explorationCellDao
		.countAtLevelFlow(EXPLORATION_ZOOM_LEVEL)
		.map { count -> ExplorationStats(totalCells = count) }
		.stateIn(viewModelScope, SharingStarted.Eagerly, ExplorationStats())

	val pagedTrips: Flow<PagingData<Trip>> = Pager(
		config = PagingConfig(pageSize = PAGE_SIZE),
		pagingSourceFactory = { tripDao.getAllPaged() },
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
			val endOfDay = date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
			val trips = tripDao.getBetween(startOfDay, endOfDay)
			val summary = dailySummaryDao.getByDay(date.toEpochDay())
			_calendarState.value = _calendarState.value.copy(
				selectedDay = date,
				selectedDayDetail = CalendarState.DayDetail(
					totalDistanceM = summary?.totalDistanceM ?: 0f,
					totalSteps = summary?.totalSteps ?: 0,
					tripCount = trips.size,
					trips = trips,
				),
			)
		}
	}

	private fun loadTimeline() {
		viewModelScope.launch {
			val now = System.currentTimeMillis()
			val thirtyDaysAgo = now - THIRTY_DAYS_MS
			val trips = tripDao.getBetween(thirtyDaysAgo, now)

			if (trips.isEmpty()) {
				_timelineState.value = TimelineState.Empty
				return@launch
			}

			val entries = mutableListOf<TimelineEntry>()
			val timeFormatter = java.text.SimpleDateFormat("HH:mm", Locale.getDefault())
			val dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

			// Group trips by day and interleave with day summaries
			val tripsByDay = trips.groupBy { trip ->
				Instant.ofEpochMilli(trip.startTimeMs)
					.atZone(ZoneId.systemDefault())
					.toLocalDate()
			}

			for ((date, dayTrips) in tripsByDay.toSortedMap(compareByDescending { it })) {
				// Day summary header
				val summary = dailySummaryDao.getByDay(date.toEpochDay())
				entries.add(
					TimelineEntry.DaySummaryEntry(
						epochDay = date.toEpochDay(),
						timestampMs = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
						dateLabel = date.format(dateFormatter),
						distanceLabel = formatDistanceLabel(summary?.totalDistanceM ?: 0f),
						stepsLabel = "${summary?.totalSteps ?: 0} steps",
						tripsLabel = "${dayTrips.size} trips",
					),
				)

				// Individual trips for the day
				for (trip in dayTrips.sortedByDescending { it.startTimeMs }) {
					entries.add(
						TimelineEntry.TripEntry(
							tripId = trip.id,
							timestampMs = trip.startTimeMs,
							title = activityLabel(trip.primaryActivity),
							subtitle = "${timeFormatter.format(java.util.Date(trip.startTimeMs))} - ${timeFormatter.format(java.util.Date(trip.endTimeMs))}",
							timeLabel = formatDistanceLabel(trip.distanceM),
							modeIcon = activityIcon(trip.primaryActivity),
							isDistancePlausible = !trip.hasDistanceAnomaly,
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
			val summaries = dailySummaryDao.getBetween(firstDay, lastDay)

			val maxDistance = summaries.maxOfOrNull { it.totalDistanceM } ?: 1f
			val dayData = summaries.associate { summary ->
				val date = LocalDate.ofEpochDay(summary.dateEpochDay)
				date to CalendarDayData(
					date = date,
					intensity = if (maxDistance > 0) summary.totalDistanceM / maxDistance else 0f,
					tripCount = summary.tripCount,
				)
			}

			_calendarState.value = _calendarState.value.copy(
				currentMonth = yearMonth,
				dayData = dayData,
				selectedDay = null,
				selectedDayDetail = null,
			)
		}
	}

	companion object {
		private const val PAGE_SIZE = 20
		private const val THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000
		private const val EXPLORATION_ZOOM_LEVEL = 14
	}
}

/**
 * Returns human-readable label for a detected activity type.
 * Activity type values follow Google Play Services DetectedActivity constants.
 */
internal fun activityLabel(activity: Int?): String = when (activity) {
	7 -> "Walk"        // DetectedActivity.WALKING
	8 -> "Run"         // DetectedActivity.RUNNING
	1 -> "Cycle"       // DetectedActivity.ON_BICYCLE
	0 -> "Drive"       // DetectedActivity.IN_VEHICLE
	else -> "Trip"
}

/**
 * Returns an icon for a detected activity type.
 */
internal fun activityIcon(activity: Int?): ImageVector = when (activity) {
	7 -> Icons.AutoMirrored.Filled.DirectionsWalk
	8 -> Icons.AutoMirrored.Filled.DirectionsRun
	1 -> Icons.AutoMirrored.Filled.DirectionsBike
	0 -> Icons.Filled.DirectionsCar
	4 -> Icons.Filled.Train  // UNKNOWN used as transit placeholder
	else -> Icons.Filled.QuestionMark
}

/**
 * Format distance in meters as a human-readable string.
 */
internal fun formatDistanceLabel(meters: Float): String {
	return if (meters >= 1000) {
		String.format(Locale.getDefault(), "%.1f km", meters / 1000.0)
	} else {
		String.format(Locale.getDefault(), "%.0f m", meters.toDouble())
	}
}
