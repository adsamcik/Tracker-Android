package com.adsamcik.tracker.stats.api.repository

import com.adsamcik.tracker.shared.model.tracking.TrackingSource

/** Saved native evidence only. Reading status never starts collection or changes its availability. */
fun interface AllDaySourceStatusQuery {
	suspend fun read(request: AllDaySourceStatusRequest): AllDaySourceStatusRead
}

sealed interface AllDaySourceStatusRequest {
	val source: TrackingSource

	data class Steps(val range: AmbientStepsHistoryRangeRequest) : AllDaySourceStatusRequest {
		override val source: TrackingSource = TrackingSource.STEPS
	}

	data class Wifi(val window: HistoryObservationWindow, val limit: Int = 4_096) : AllDaySourceStatusRequest {
		override val source: TrackingSource = TrackingSource.WIFI
		init { require(limit in 1..4_096) }
	}

	data class Cell(val window: HistoryObservationWindow, val limit: Int = 4_096) : AllDaySourceStatusRequest {
		override val source: TrackingSource = TrackingSource.CELL
		init { require(limit in 1..4_096) }
	}

	data class Location(val window: HistoryObservationWindow, val limit: Int = 4_096) : AllDaySourceStatusRequest {
		override val source: TrackingSource = TrackingSource.LOCATION
		init { require(limit in 1..4_096) }
	}

	/** Activity and Pressure currently have no supported sessionless product. */
	data class Unsupported(override val source: TrackingSource) : AllDaySourceStatusRequest {
		init { require(source == TrackingSource.ACTIVITY || source == TrackingSource.PRESSURE) }
	}
}

/** A provider aggregate is a real window and revision, not a fabricated admitted sensor event. */
data class AllDayStepsWindowStatus(
	val identity: String,
	val semanticRevision: Long,
	val day: AmbientStepsStructuralDay,
	val startTimeMs: Long,
	val endTimeMs: Long,
) {
	init { require(identity.isNotBlank()); require(semanticRevision > 0L); require(startTimeMs >= 0L && endTimeMs > startTimeMs) }
}

data class AllDayStepsGapStatus(val day: AmbientStepsStructuralDay, val startTimeMs: Long, val endTimeMs: Long)

/** Original reading clocks; this carries no network, tower, signal or coordinate values. */
data class AllDayReadingTime(val observedTimeMs: Long, val wallTimeUncertaintyMs: Long) {
	init { require(observedTimeMs >= 0L && wallTimeUncertaintyMs >= 0L) }
}

data class AllDayWifiFactStatus(
	val identity: String,
	val semanticRevision: Long,
	val coverage: AmbientWifiCoverage,
	val coverageStartTimeMs: Long,
	val observedTimeMs: Long,
	val latestPossibleTimeMs: Long,
	val readings: List<AllDayReadingTime>,
)

data class AllDayCellFactStatus(
	val identity: String,
	val semanticRevision: Long,
	val coverage: AmbientCellCoverage,
	val subscriptionCompleteness: AmbientCellSubscriptionCompleteness,
	val coverageStartTimeMs: Long,
	val observedTimeMs: Long,
	val latestPossibleTimeMs: Long,
	val readings: List<AllDayReadingTime>,
)

data class AllDayLocationPointStatus(val sourceEventId: String, val time: AllDayReadingTime)

/** Source-specific saved-data results; none claims that a provider is currently recording. */
sealed interface AllDaySourceStatusRead {
	data class Steps(
		val snapshot: TrackingHistoryReadSnapshot,
		val windows: List<AllDayStepsWindowStatus>,
		val gaps: List<AllDayStepsGapStatus>,
		val materializingDays: Set<AmbientStepsStructuralDay>,
		val unavailableDays: Set<AmbientStepsStructuralDay>,
	) : AllDaySourceStatusRead

	data class Wifi(
		val snapshot: TrackingHistoryReadSnapshot,
		val facts: List<AllDayWifiFactStatus>,
		val gaps: List<AmbientWifiGap>,
		val qualificationGaps: Set<RadioContextReadGapReason>,
	) : AllDaySourceStatusRead

	data class Cell(
		val snapshot: TrackingHistoryReadSnapshot,
		val facts: List<AllDayCellFactStatus>,
		val gaps: List<AmbientCellGap>,
		val qualificationGaps: Set<RadioContextReadGapReason>,
	) : AllDaySourceStatusRead

	data class Location(
		val snapshot: TrackingHistoryReadSnapshot,
		val points: List<AllDayLocationPointStatus>,
		val qualificationGaps: Set<RadioLocationContextGap>,
		val problem: HistoryProblemReason? = null,
	) : AllDaySourceStatusRead

	data object Unsupported : AllDaySourceStatusRead
	data class Unavailable(val reason: HistoryProblemReason) : AllDaySourceStatusRead
}
