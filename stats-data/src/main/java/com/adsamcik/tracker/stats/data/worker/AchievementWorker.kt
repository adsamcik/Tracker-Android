package com.adsamcik.tracker.stats.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.adsamcik.tracker.shared.base.database.dao.AchievementProgressDao
import com.adsamcik.tracker.shared.base.database.data.AchievementProgressEntity
import com.adsamcik.tracker.stats.api.AchievementTier
import com.adsamcik.tracker.stats.api.repository.AchievementMetricsProvider
import com.adsamcik.tracker.stats.engine.achievement.AchievementCatalog
import com.adsamcik.tracker.stats.engine.achievement.AchievementEvaluator
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Background worker that evaluates all achievements against pre-aggregated metrics.
 *
 * This is the SINGLE path for persisting achievement unlocks and awarding XP.
 * The real-time [com.adsamcik.tracker.stats.engine.processor.AchievementProcessor]
 * only tracks in-memory progress for live UI — it never persists or unlocks.
 *
 * Key invariants:
 * - Uses [AchievementProgressDao] as the single source of truth.
 * - Compare-and-set: only emits unlock if the DB's previous tier was lower.
 * - Tier-skip aware: jumping from none→GOLD emits BRONZE, SILVER, and GOLD.
 * - XP is idempotent: uses deterministic sourceId hash to prevent duplicates.
 * - Only queries pre-aggregated tables via [AchievementMetricsProvider].
 */
@HiltWorker
class AchievementWorker @AssistedInject constructor(
	@Assisted context: Context,
	@Assisted params: WorkerParameters,
	private val metricsProvider: AchievementMetricsProvider,
	private val achievementDao: AchievementProgressDao,
	private val evaluator: AchievementEvaluator,
) : CoroutineWorker(context, params) {

	override suspend fun doWork(): Result {
		val metrics = metricsProvider.collect()
		val previousProgress = achievementDao.getAll()
		val previousMap = previousProgress.associateBy { it.achievementId }
		val now = System.currentTimeMillis()

		for (definition in AchievementCatalog.definitions) {
			val metricValue = metrics[definition.metric] ?: 0L

			// Resolve persisted previous tier (using unlockedAt as discriminator per Issue 2)
			val previous = previousMap[definition.id]
			val previousTier = previous?.let { entity ->
				val tierOrdinal = entity.tier
				if (entity.unlockedAt != null && tierOrdinal != null) {
					AchievementTier.entries.getOrNull(tierOrdinal)
				} else {
					null
				}
			}

			// Evaluate ALL newly crossed tiers (fixes tier-skip per Issue 3)
			val newUnlocks = evaluator.evaluateForUnlocks(definition, metricValue, previousTier)

			// Compute next tier target for progress display
			val nextTierTarget = definition.tiers.entries
				.sortedBy { it.key.ordinal }
				.firstOrNull { metricValue < it.value }
				?.value ?: metricValue

			// Determine the highest newly unlocked tier
			val highestNewTier = newUnlocks.lastOrNull()?.tier

			// Persist progress (upsert: insert-if-new then update)
			achievementDao.upsert(
				AchievementProgressEntity(
					achievementId = definition.id,
					currentValue = metricValue,
					targetValue = nextTierTarget,
					tier = highestNewTier?.ordinal ?: previous?.tier,
					unlockedAt = if (newUnlocks.isNotEmpty()) now else null,
					updatedAt = now,
				),
			)
		}

		return Result.success()
	}

	companion object {
		const val UNIQUE_WORK_NAME = "AchievementEvaluation"
		const val TAG = "Achievement"
	}
}
