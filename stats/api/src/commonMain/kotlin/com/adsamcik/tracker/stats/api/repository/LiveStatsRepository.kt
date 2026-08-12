package com.adsamcik.tracker.stats.api.repository

import com.adsamcik.tracker.stats.api.value.DistanceM
import com.adsamcik.tracker.stats.api.value.DurationMs
import com.adsamcik.tracker.stats.api.value.StepCount
import kotlinx.coroutines.flow.Flow

data class LiveStats(
	val dateEpochDay: Long = 0L,
	val sessionDistance: DistanceM = DistanceM.ZERO,
	val sessionSteps: StepCount = StepCount.ZERO,
	val sessionDuration: DurationMs = DurationMs.ZERO,
	val dayTotalDistance: DistanceM = DistanceM.ZERO,
	val dayTotalSteps: StepCount = StepCount.ZERO,
	val dayTotalDuration: DurationMs = DurationMs.ZERO,
	val lastUpdatedMs: Long = 0L,
)

interface LiveStatsRepository {
	fun observeLiveStats(): Flow<LiveStats>
	suspend fun updateLiveStats(stats: LiveStats)
	suspend fun clear()
}
