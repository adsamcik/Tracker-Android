package com.adsamcik.tracker.stats.api.repository

import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.stats.api.value.DistanceM
import com.adsamcik.tracker.stats.api.value.DurationMs
import com.adsamcik.tracker.stats.api.value.SpeedMps
import com.adsamcik.tracker.stats.api.value.StepCount
import kotlinx.coroutines.flow.Flow

data class LiveStats(
	val sessionDistance: DistanceM = DistanceM.ZERO,
	val sessionSteps: StepCount = StepCount.ZERO,
	val sessionDuration: DurationMs = DurationMs.ZERO,
	val currentSpeed: SpeedMps = SpeedMps.ZERO,
	val avgSpeed: SpeedMps = SpeedMps.ZERO,
	val maxSpeed: SpeedMps = SpeedMps.ZERO,
	val dayTotalDistance: DistanceM = DistanceM.ZERO,
	val dayTotalSteps: StepCount = StepCount.ZERO,
	val dominantActivity: DetectedActivityType? = null,
	val tripCount: Int = 0,
)

interface LiveStatsRepository {
	fun observeLiveStats(): Flow<LiveStats>
	suspend fun updateLiveStats(stats: LiveStats)
	suspend fun clear()
}
