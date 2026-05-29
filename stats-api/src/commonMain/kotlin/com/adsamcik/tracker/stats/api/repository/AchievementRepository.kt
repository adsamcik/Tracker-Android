package com.adsamcik.tracker.stats.api.repository

import com.adsamcik.tracker.stats.api.AchievementSnapshot
import com.adsamcik.tracker.stats.api.AchievementSummary
import kotlinx.coroutines.flow.Flow

interface AchievementRepository {
	fun observeSummary(): Flow<AchievementSummary>
	fun observeRecentUnlocks(limit: Int = 5): Flow<List<AchievementSnapshot>>
	suspend fun getAllSnapshots(): List<AchievementSnapshot>
}
