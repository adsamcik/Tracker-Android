package com.adsamcik.tracker.statistics.viewmodel

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DownhillSkiing
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.Sailing
import androidx.compose.material.icons.filled.Train
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector
import com.adsamcik.tracker.shared.base.data.SessionActivityIds
import com.adsamcik.tracker.shared.base.database.data.Trip
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

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
 * Returns human-readable label for a detected activity type.
 * Activity type values may be Google Play Services DetectedActivity constants or native Tracker ids.
 */
internal fun activityLabel(activity: Int?): String = when (activity) {
	in SessionActivityIds.WALKING -> "Walk"
	in SessionActivityIds.RUNNING -> "Run"
	in SessionActivityIds.CYCLING -> "Cycle"
	in SessionActivityIds.DRIVING -> "Drive"
	in SessionActivityIds.WATER -> "Sail"
	in SessionActivityIds.AIR -> "Fly"
	in SessionActivityIds.SLOPE_SPORTS -> "Ski"
	else -> "Trip"
}

/**
 * Returns an icon for a detected activity type.
 */
internal fun activityIcon(activity: Int?): ImageVector = when (activity) {
	in SessionActivityIds.WALKING -> Icons.AutoMirrored.Filled.DirectionsWalk
	in SessionActivityIds.RUNNING -> Icons.AutoMirrored.Filled.DirectionsRun
	in SessionActivityIds.CYCLING -> Icons.AutoMirrored.Filled.DirectionsBike
	in SessionActivityIds.DRIVING -> Icons.Filled.DirectionsCar
	in SessionActivityIds.WATER -> Icons.Filled.Sailing
	in SessionActivityIds.AIR -> Icons.Filled.Flight
	in SessionActivityIds.SLOPE_SPORTS -> Icons.Filled.DownhillSkiing
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
