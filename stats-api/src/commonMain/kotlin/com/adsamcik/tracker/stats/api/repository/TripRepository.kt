package com.adsamcik.tracker.stats.api.repository

import arrow.core.Either
import com.adsamcik.tracker.stats.api.TransportMode
import com.adsamcik.tracker.stats.api.error.StatsError
import com.adsamcik.tracker.stats.api.value.DistanceM
import com.adsamcik.tracker.stats.api.value.DurationMs
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.api.value.StepCount
import kotlinx.coroutines.flow.Flow

/** Read model for a completed trip. */
data class TripSummary(
	val id: Long,
	val startTimeMs: EpochMs,
	val endTimeMs: EpochMs,
	val distance: DistanceM,
	val steps: StepCount,
	val duration: DurationMs,
	val primaryMode: TransportMode,
	val sampleCount: Int,
)

interface TripRepository {
	fun observeTrips(): Flow<List<TripSummary>>
	fun observeTripsBetween(from: EpochMs, to: EpochMs): Flow<List<TripSummary>>
	suspend fun getTripDetail(id: Long): Either<StatsError, TripSummary>
}
