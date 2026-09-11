package com.adsamcik.tracker.stats.api.repository

import arrow.core.Either
import com.adsamcik.tracker.stats.api.TransportMode
import com.adsamcik.tracker.stats.api.error.StatsError
import com.adsamcik.tracker.stats.api.value.DistanceM
import com.adsamcik.tracker.stats.api.value.DurationMs
import com.adsamcik.tracker.stats.api.value.EpochMs
import kotlinx.coroutines.flow.Flow

/**
 * Read model for a completed trip's non-Steps presentation fields.
 *
 * Steps require [TrackingHistoryRepository] because the legacy trip aggregate cannot establish
 * capture ownership, completeness, or source qualification.
 */
data class TripSummary(
	val id: Long,
	val startTimeMs: EpochMs,
	val endTimeMs: EpochMs,
	val distance: DistanceM,
	val duration: DurationMs,
	val primaryMode: TransportMode,
	val sampleCount: Int,
	/** Exact presentation origin; imported Steps must not trigger time-overlap route enrichment. */
	val source: com.adsamcik.tracker.shared.model.SegmentSource? = null,
)

interface TripRepository {
	fun observeTrips(): Flow<List<TripSummary>>
	fun observeTripsBetween(from: EpochMs, to: EpochMs): Flow<List<TripSummary>>
	suspend fun getTripDetail(id: Long): Either<StatsError, TripSummary>
}
