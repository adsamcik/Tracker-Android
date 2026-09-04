package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.dao.AchievementProgressDao
import com.adsamcik.tracker.shared.base.database.data.AchievementProgressEntity
import com.adsamcik.tracker.stats.api.AchievementSnapshot
import com.adsamcik.tracker.stats.api.AchievementSummary
import com.adsamcik.tracker.stats.api.achievement.AchievementCatalog
import com.adsamcik.tracker.stats.api.metric.MetricKey
import com.adsamcik.tracker.stats.api.repository.AchievementRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class DefaultAchievementRepository @Inject constructor(
	private val progressDao: AchievementProgressDao,
) : AchievementRepository {
	override fun observeSummary(): Flow<AchievementSummary> = progressDao.getAllFlow().map { rows ->
		val progressByMetric = rows.byMetric()
		val visibleDefinitions = AchievementCatalog.definitions.filter {
			AchievementMetricQualification.isTrustedPersistedProgress(it.metric)
		}
		val unlocked = visibleDefinitions.count { definition ->
			(progressByMetric[definition.metric]?.lastTierIndex ?: -1) >= definition.tierIndex
		}
		AchievementSummary(
			total = visibleDefinitions.size,
			unlocked = unlocked,
			inProgress = (visibleDefinitions.size - unlocked).coerceAtLeast(0),
		)
	}

	override fun observeRecentUnlocks(limit: Int): Flow<List<AchievementSnapshot>> = progressDao.getAllFlow().map { rows ->
		rows.mapNotNull { it.toLatestUnlockSnapshot() }.take(limit.coerceAtLeast(0))
	}

	override suspend fun getAllSnapshots(): List<AchievementSnapshot> {
		val progressByMetric = progressDao.getAll().byMetric()
		return AchievementCatalog.definitions
			.filter { AchievementMetricQualification.isTrustedPersistedProgress(it.metric) }
			.map { definition ->
				val row = progressByMetric[definition.metric]
				AchievementSnapshot(
					id = definition.id,
					nameRes = definition.nameRes,
					descriptionRes = definition.descriptionRes,
					category = definition.category,
					tier = definition.tier,
					metric = definition.metric,
					currentValue = row?.lastValue ?: 0.0,
					threshold = definition.threshold,
					isUnlocked = (row?.lastTierIndex ?: -1) >= definition.tierIndex,
					updatedAt = row?.updatedAt ?: 0L,
				)
			}
	}

	private fun List<AchievementProgressEntity>.byMetric(): Map<MetricKey, AchievementProgressEntity> =
		mapNotNull { row ->
			MetricKey.fromStorageKey(row.metricKey)
				?.takeIf(AchievementMetricQualification::isTrustedPersistedProgress)
				?.let { it to row }
		}.toMap()

	private fun AchievementProgressEntity.toLatestUnlockSnapshot(): AchievementSnapshot? {
		val metric = MetricKey.fromStorageKey(metricKey) ?: return null
		if (!AchievementMetricQualification.isTrustedPersistedProgress(metric) || lastTierIndex < 0) return null
		val definition = AchievementCatalog.byMetric(metric).getOrNull(lastTierIndex) ?: return null
		return AchievementSnapshot(definition.id, definition.nameRes, definition.descriptionRes, definition.category, definition.tier, definition.metric, lastValue, definition.threshold, true, updatedAt)
	}
}
