package com.adsamcik.tracker.stats.api.repository

import com.adsamcik.tracker.shared.model.tracking.TrackingSource

/** Saved native evidence only. Reading status never starts collection or changes its availability. */
fun interface AllDaySourceStatusQuery {
	/**
	 * Reads the selected source's saved all-day status without requiring a session or starting its
	 * provider.
	 */
	suspend fun read(request: AllDaySourceStatusRequest): AllDaySourceStatusRead
}

/**
 * Source-specific saved all-day scope; reading this request does not activate collection.
 */
sealed interface AllDaySourceStatusRequest {
	val source: TrackingSource

	/**
	 * Selects the original provider windows for the requested all-day Steps dates.
	 */
	data class Steps(val range: AmbientStepsHistoryRangeRequest) : AllDaySourceStatusRequest {
		override val source: TrackingSource = TrackingSource.STEPS
	}

	/**
	 * Selects saved all-day Wi-Fi clocks for the measured window with a limit from 1 to 4096.
	 */
	data class Wifi(val window: HistoryObservationWindow, val limit: Int = 4_096) : AllDaySourceStatusRequest {
		override val source: TrackingSource = TrackingSource.WIFI
		init { require(limit in 1..4_096) }
	}

	/**
	 * Selects saved all-day Cell clocks for the measured window with a limit from 1 to 4096.
	 */
	data class Cell(val window: HistoryObservationWindow, val limit: Int = 4_096) : AllDaySourceStatusRequest {
		override val source: TrackingSource = TrackingSource.CELL
		init { require(limit in 1..4_096) }
	}

	/**
	 * Selects saved all-day Location point clocks for the measured window with a limit from 1 to
	 * 4096.
	 */
	data class Location(val window: HistoryObservationWindow, val limit: Int = 4_096) : AllDaySourceStatusRequest {
		override val source: TrackingSource = TrackingSource.LOCATION
		init { require(limit in 1..4_096) }
	}

	/** Original saved Activity observations or Pressure aggregates in the measured window. */
	data class Sensor(override val source: TrackingSource, val window: HistoryObservationWindow,
		val limit: Int = 4_096) : AllDaySourceStatusRequest {
		init {
			require(source == TrackingSource.ACTIVITY || source == TrackingSource.PRESSURE)
			require(limit in 1..4_096)
		}
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
	init { require(identity.isNotBlank()); require(semanticRevision > 0L); require(startTimeMs >= 0L &&
        endTimeMs > startTimeMs) }
}

/**
 * A saved Steps gap for its structural day with UTC millisecond start/end bounds.
 */
data class AllDayStepsGapStatus(val day: AmbientStepsStructuralDay, val startTimeMs: Long, val endTimeMs: Long)

/** Original reading clocks; this carries no network, tower, signal or coordinate values. */
data class AllDayReadingTime(val observedTimeMs: Long, val wallTimeUncertaintyMs: Long) {
	init { require(observedTimeMs >= 0L && wallTimeUncertaintyMs >= 0L) }
}

/**
 * Identity, revision, coverage and original reading clocks of a saved all-day Wi-Fi fact.
 */
data class AllDayWifiFactStatus(
	val identity: String,
	val semanticRevision: Long,
	val coverage: AmbientWifiCoverage,
	val coverageStartTimeMs: Long,
	val observedTimeMs: Long,
	val latestPossibleTimeMs: Long,
	val readings: List<AllDayReadingTime>,
)

/**
 * Identity, revision, coverage, subscription completeness and original clocks of a saved all-day
 * Cell fact.
 */
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

/**
 * Original Location source-event identity with its measured UTC millisecond time and uncertainty.
 */
data class AllDayLocationPointStatus(val sourceEventId: String, val time: AllDayReadingTime)

/** Original native identity and measured clock; Pressure keeps its real provider window, when saved. */
data class AllDaySensorObservationStatus(
	val identity: String,
	val observedTimeMs: Long,
	val wallTimeUncertaintyMs: Long?,
	val windowStartElapsedRealtimeNanos: Long? = null,
	val windowEndElapsedRealtimeNanos: Long? = null,
	val isPartial: Boolean = false,
)

/** Source-specific saved-data results; none claims that a provider is currently recording. */
sealed interface AllDaySourceStatusRead {
	/**
	 * Saved provider windows and gaps under one read snapshot, keeping materializing/unavailable days
	 * explicit.
	 */
	data class Steps(
		val snapshot: TrackingHistoryReadSnapshot,
		val windows: List<AllDayStepsWindowStatus>,
		val gaps: List<AllDayStepsGapStatus>,
		val materializingDays: Set<AmbientStepsStructuralDay>,
		val unavailableDays: Set<AmbientStepsStructuralDay>,
	) : AllDaySourceStatusRead

	/**
	 * Saved Wi-Fi facts and timed gaps with independent qualification problems under one read
	 * snapshot.
	 */
	data class Wifi(
		val snapshot: TrackingHistoryReadSnapshot,
		val facts: List<AllDayWifiFactStatus>,
		val gaps: List<AmbientWifiGap>,
		val qualificationGaps: Set<RadioContextReadGapReason>,
	) : AllDaySourceStatusRead

	/**
	 * Saved Cell facts and timed gaps with independent qualification problems under one read
	 * snapshot.
	 */
	data class Cell(
		val snapshot: TrackingHistoryReadSnapshot,
		val facts: List<AllDayCellFactStatus>,
		val gaps: List<AmbientCellGap>,
		val qualificationGaps: Set<RadioContextReadGapReason>,
	) : AllDaySourceStatusRead

	/**
	 * Saved Location point clocks and qualification gaps; an optional problem does not erase
	 * available points.
	 */
	data class Location(
		val snapshot: TrackingHistoryReadSnapshot,
		val points: List<AllDayLocationPointStatus>,
		val qualificationGaps: Set<RadioLocationContextGap>,
		val problem: HistoryProblemReason? = null,
	) : AllDaySourceStatusRead

	/** Saved native sensor observations; a bad row does not discard independently usable readings. */
	data class Sensor(
		val snapshot: TrackingHistoryReadSnapshot,
		val source: TrackingSource,
		val observations: List<AllDaySensorObservationStatus>,
		val problem: HistoryProblemReason? = null,
	) : AllDaySourceStatusRead {
		init { require(source == TrackingSource.ACTIVITY || source == TrackingSource.PRESSURE) }
	}
	/**
	 * The requested data or authority is unavailable; any named reason preserves the original failure
	 * cause.
	 */
	data class Unavailable(val reason: HistoryProblemReason) : AllDaySourceStatusRead
}
