package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.dao.LiveStatsDao
import com.adsamcik.tracker.stats.api.repository.LiveStats
import com.adsamcik.tracker.stats.api.repository.LiveStatsRepository
import com.adsamcik.tracker.stats.api.value.DistanceM
import com.adsamcik.tracker.stats.api.value.DurationMs
import com.adsamcik.tracker.stats.api.value.StepCount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class DefaultLiveStatsRepository @Inject constructor(
	private val liveStatsDao: LiveStatsDao,
) : LiveStatsRepository {

	override fun observeLiveStats(): Flow<LiveStats> {
		return liveStatsDao.getFlow().map { entity ->
			if (entity == null) {
				LiveStats()
			} else {
				LiveStats(
					dateEpochDay = entity.dateEpochDay,
					sessionDistance = DistanceM.coerced(entity.sessionDistanceM),
					sessionSteps = StepCount.coerced(entity.sessionSteps),
					sessionDuration = DurationMs(entity.sessionDurationMs.coerceAtLeast(0L)),
					dayTotalDistance = DistanceM.coerced(entity.dayTotalDistanceM),
					dayTotalSteps = StepCount.coerced(entity.dayTotalSteps),
					dayTotalDuration = DurationMs(entity.dayTotalDurationMs.coerceAtLeast(0L)),
					lastUpdatedMs = entity.lastUpdatedMs,
				)
			}
		}
	}

	override suspend fun updateLiveStats(stats: LiveStats) {
		liveStatsDao.upsert(
			dateEpochDay = stats.dateEpochDay,
			sessionDistanceM = stats.sessionDistance.raw,
			sessionSteps = stats.sessionSteps.raw,
			sessionDurationMs = stats.sessionDuration.raw,
			dayTotalDistanceM = stats.dayTotalDistance.raw,
			dayTotalSteps = stats.dayTotalSteps.raw,
			dayTotalDurationMs = stats.dayTotalDuration.raw,
			lastUpdatedMs = stats.lastUpdatedMs,
		)
	}

	override suspend fun clear() {
		liveStatsDao.clear()
	}
}
