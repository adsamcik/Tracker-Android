package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.dao.AchievementProgressDao
import com.adsamcik.tracker.stats.api.repository.AchievementProgressData
import com.adsamcik.tracker.stats.api.repository.AchievementRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class DefaultAchievementRepository @Inject constructor(
	private val achievementProgressDao: AchievementProgressDao,
) : AchievementRepository {

	override fun observeAll(): Flow<List<AchievementProgressData>> {
		return achievementProgressDao.getAllFlow().map { entities ->
			entities.map { entity ->
				AchievementProgressData(
					achievementId = entity.achievementId,
					currentValue = entity.currentValue,
					targetValue = entity.targetValue,
					tier = entity.tier.toString(),
					isUnlocked = entity.unlockedAt != null,
				)
			}
		}
	}

	override fun observeRecent(): Flow<List<AchievementProgressData>> {
		return observeAll().map { all ->
			all.filter { it.isUnlocked }.takeLast(10)
		}
	}
}
