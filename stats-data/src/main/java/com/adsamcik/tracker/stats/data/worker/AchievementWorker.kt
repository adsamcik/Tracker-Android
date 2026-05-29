package com.adsamcik.tracker.stats.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.adsamcik.tracker.shared.base.database.dao.AchievementProgressDao
import com.adsamcik.tracker.shared.base.database.data.AchievementProgressEntity
import com.adsamcik.tracker.stats.api.AchievementTier
import com.adsamcik.tracker.stats.api.repository.AchievementMetricsProvider
import com.adsamcik.tracker.stats.api.rule.RuleEvaluationResult
import com.adsamcik.tracker.stats.api.rule.RuleEvaluator
import com.adsamcik.tracker.stats.api.rule.RuleTarget
import com.adsamcik.tracker.stats.data.achievement.AchievementRuleRegistry
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
	private val achievementRuleRegistry: AchievementRuleRegistry,
) : CoroutineWorker(context, params) {

	private val ruleEvaluator = RuleEvaluator()

	override suspend fun doWork(): Result {
		val metrics = metricsProvider.collect()
		val instances = achievementRuleRegistry.allInstances()
		val now = System.currentTimeMillis()

		for (instance in instances) {
			val metricValue = metrics[instance.rule.metric] ?: 0L
			val target = instance.rule.target
			check(target is RuleTarget.Tiered) {
				"Achievement rule ${instance.rule.id} must have RuleTarget.Tiered"
			}

			val result = ruleEvaluator.evaluate(instance, metricValue)
			val newUnlocks = ruleEvaluator.crossedTiers(
				target = target,
				currentValue = metricValue,
				previousTier = instance.previousTier,
			)
			val nextTierTarget = when (result) {
				is RuleEvaluationResult.TierUnlocked -> result.nextTierTarget ?: metricValue
				is RuleEvaluationResult.ProgressUpdated -> result.nextTierTarget ?: metricValue
				is RuleEvaluationResult.Unchanged -> nextTierTarget(target, instance.previousTier) ?: metricValue
				is RuleEvaluationResult.Completed,
				is RuleEvaluationResult.ChallengeProgress -> metricValue
			}
			val highestNewTier = newUnlocks.lastOrNull()

			achievementDao.upsert(
				AchievementProgressEntity(
					achievementId = instance.rule.id,
					currentValue = metricValue,
					targetValue = nextTierTarget,
					tier = highestNewTier?.ordinal ?: instance.previousTier?.ordinal,
					unlockedAt = if (newUnlocks.isNotEmpty()) now else null,
					updatedAt = now,
				),
			)
		}

		return Result.success()
	}

	private fun nextTierTarget(
		target: RuleTarget.Tiered,
		currentTier: AchievementTier?,
	): Long? {
		val sorted = target.tiers.entries.sortedBy { it.key.ordinal }
		return if (currentTier == null) {
			sorted.firstOrNull()?.value
		} else {
			sorted.firstOrNull { it.key.ordinal > currentTier.ordinal }?.value
		}
	}

	companion object {
		const val UNIQUE_WORK_NAME = "AchievementEvaluation"
		const val TAG = "Achievement"
	}
}
