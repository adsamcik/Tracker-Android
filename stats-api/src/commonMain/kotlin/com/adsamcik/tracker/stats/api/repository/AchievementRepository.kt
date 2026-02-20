package com.adsamcik.tracker.stats.api.repository

import kotlinx.coroutines.flow.Flow

data class AchievementProgressData(
	val achievementId: String,
	val currentValue: Long,
	val targetValue: Long,
	val tier: String?,
	val isUnlocked: Boolean,
)

interface AchievementRepository {
	fun observeAll(): Flow<List<AchievementProgressData>>
	fun observeRecent(): Flow<List<AchievementProgressData>>
}
