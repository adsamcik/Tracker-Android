package com.adsamcik.tracker.stats.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.adsamcik.tracker.shared.base.database.dao.AchievementProgressDao
import com.adsamcik.tracker.shared.base.database.data.AchievementProgressEntity
import com.adsamcik.tracker.stats.api.achievement.AchievementEvaluator
import com.adsamcik.tracker.stats.api.metric.MetricKey
import com.adsamcik.tracker.stats.api.repository.AchievementMetricsProvider
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class AchievementWorker @AssistedInject constructor(
	@Assisted context: Context,
	@Assisted params: WorkerParameters,
	private val metricsProvider: AchievementMetricsProvider,
	private val achievementDao: AchievementProgressDao,
) : CoroutineWorker(context, params) {
	private val evaluator = AchievementEvaluator()

	override suspend fun doWork(): Result {
		val snapshot = metricsProvider.collect()
		val rows = achievementDao.getAll()
		val progressByMetric = rows.mapNotNull { row -> MetricKey.fromStorageKey(row.metricKey)?.let { it to row } }.toMap()
		val changedMetrics = snapshot.asMap().keys.filterTo(LinkedHashSet()) { metric ->
			val row = progressByMetric[metric]
			row == null || row.lastValue != snapshot.valueOf(metric)
		}
		if (changedMetrics.isEmpty()) return Result.success()
		val unlocks = evaluator.evaluate(changedMetrics, snapshot, progressByMetric.mapValues { it.value.lastTierIndex })
		val maxUnlockedByMetric = HashMap<MetricKey, Int>()
		for (event in unlocks) {
			val metric = event.definition.metric
			val current = maxUnlockedByMetric[metric] ?: -1
			if (event.definition.tierIndex > current) maxUnlockedByMetric[metric] = event.definition.tierIndex
		}
		val now = System.currentTimeMillis()
		val updates = changedMetrics.map { metric ->
			val previous = progressByMetric[metric]
			AchievementProgressEntity(
				metricKey = metric.storageKey,
				lastTierIndex = maxOf(previous?.lastTierIndex ?: -1, maxUnlockedByMetric[metric] ?: -1),
				lastValue = snapshot.valueOf(metric),
				updatedAt = now,
			)
		}
		achievementDao.upsertAll(updates)
		return Result.success()
	}

	companion object {
		const val UNIQUE_WORK_NAME = "AchievementEvaluation"
		const val TAG = "Achievement"
	}
}
