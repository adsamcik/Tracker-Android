package com.adsamcik.tracker.stats.api.repository

import arrow.core.Either
import com.adsamcik.tracker.stats.api.error.StatsError
import com.adsamcik.tracker.stats.api.value.DistanceM
import com.adsamcik.tracker.stats.api.value.DurationMs
import kotlinx.coroutines.flow.Flow

/** Independent day metrics. Steps require [StepsNumericSummaryRepository] source qualification. */
data class DailySummary(
	val dayEpoch: Long,
	val totalDistance: DistanceM,
	val totalDuration: DurationMs,
	val tripCount: Int,
	val activeTrackingDuration: DurationMs,
)

interface DailySummaryRepository {
	fun observeToday(): Flow<DailySummary?>
	fun observeWeek(): Flow<List<DailySummary>>
	suspend fun getBetween(fromDay: Long, toDay: Long): Either<StatsError, List<DailySummary>>
	fun observeBetween(fromDay: Long, toDay: Long): Flow<List<DailySummary>>
}
