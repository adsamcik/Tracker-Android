package com.adsamcik.tracker.stats.api.repository

import arrow.core.Either
import com.adsamcik.tracker.stats.api.error.StatsError
import com.adsamcik.tracker.stats.api.value.DistanceM
import com.adsamcik.tracker.stats.api.value.DurationMs
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.api.value.StepCount

data class SessionStatsSnapshot(
	val duration: DurationMs,
	val collections: Long,
	val totalDistance: DistanceM,
	val steps: StepCount,
	val tripCount: Long,
	val locationCount: Long,
	val wifiCount: Long,
	val cellCount: Long,
)

interface SessionStatsRepository {
	suspend fun getAllTime(): Either<StatsError, SessionStatsSnapshot>
	suspend fun getBetween(fromMs: EpochMs, toMs: EpochMs): Either<StatsError, SessionStatsSnapshot>
}
