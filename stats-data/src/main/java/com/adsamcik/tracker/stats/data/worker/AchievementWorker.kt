package com.adsamcik.tracker.stats.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.adsamcik.tracker.shared.base.database.dao.AchievementProgressDao
import com.adsamcik.tracker.shared.base.database.data.AchievementProgressEntity
import com.adsamcik.tracker.stats.api.AchievementDefinition
import com.adsamcik.tracker.stats.api.metric.MetricDirtyTracker
import com.adsamcik.tracker.stats.api.metric.MetricKey
import com.adsamcik.tracker.stats.api.repository.AchievementMetricsProvider
import com.adsamcik.tracker.stats.api.rule.AchievementRules
import com.adsamcik.tracker.stats.api.rule.RuleEvaluationResult
import com.adsamcik.tracker.stats.api.rule.RuleEvaluator
import com.adsamcik.tracker.stats.api.rule.RuleRegistry
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Background WorkManager worker that recomputes persisted achievement progress.
 *
 * **Engine unification (R1 round-6 P2):** this worker shares the SAME evaluation
 * stack as the in-session [com.adsamcik.tracker.stats.engine.processor.AchievementProcessor]:
 *
 *  * [RuleRegistry] (qualified with [AchievementRules]) resolves which rule instances
 *    are affected by the dirty tables — applying the catalog → metric → tables mapping
 *    in ONE place.
 *  * [RuleEvaluator] decides per-instance whether evaluation advances a tier,
 *    updates progress, or is unchanged — also in ONE place.
 *
 * The legacy `AchievementEvaluator` (catalog-iteration + threshold-loop) is gone,
 * so the live UI path and the persistence path can no longer drift apart: a tier
 * the processor celebrates is, by construction, a tier the worker will persist
 * (and vice versa).
 *
 * **Battery-critical short-circuit (R2 round-6 perf review):** the worker mirrors
 * the snapshot pattern from `AchievementProcessor.runEvaluation`:
 *
 *  1. Atomically consume the PERSISTENCE-consumer dirty set at the very top of
 *     `doWork()`. Per-consumer state means the live flush can never drain our bits
 *     and vice versa (R1+R2 round-6 starvation fix).
 *  2. If the set is empty, return `Result.success()` IMMEDIATELY — no registry
 *     query, no `metricsProvider.collect()`, no DAO read. Idle-flush cost is one
 *     atomic CAS read, period.
 *  3. If the registry maps the dirty tables to NO rule instances, short-circuit
 *     again before paying for [AchievementMetricsProvider.collect].
 *  4. On any non-cancellation throwable AFTER consuming the dirty set, re-mark
 *     the consumed tables so the next worker pass still observes the changes
 *     (mirrors `AchievementProcessor`'s exception path).
 *  5. Cancellation re-throws so structured concurrency is honoured.
 */
@HiltWorker
class AchievementWorker @AssistedInject constructor(
	@Assisted context: Context,
	@Assisted params: WorkerParameters,
	@AchievementRules private val ruleRegistry: RuleRegistry,
	private val metricsProvider: AchievementMetricsProvider,
	private val achievementDao: AchievementProgressDao,
	private val dirtyTracker: MetricDirtyTracker,
) : CoroutineWorker(context, params) {

	override suspend fun doWork(): Result {
		// Snapshot the dirty set FIRST for the PERSISTENCE consumer view. consumeDirty
		// is an atomic swap on the per-consumer state — writes that race this check
		// land in the next scheduled window for PERSISTENCE, never lost. The LIVE
		// consumer's view (used by the in-session AchievementProcessor) is NOT
		// affected by this drain, so the periodic worker can never starve the live
		// flush of its dirty bits (R1+R2 round-6 finding).
		val consumed = dirtyTracker.consumeDirty(MetricDirtyTracker.Consumer.PERSISTENCE)
		if (consumed.isEmpty()) return Result.success()

		return try {
			evaluate(consumed)
		} catch (e: kotlinx.coroutines.CancellationException) {
			throw e
		} catch (t: Throwable) {
			// Preserve the dirty bit so the next scheduled run still observes the
			// underlying changes. Without this, an exception below would silently
			// drop the next update window. (Re-marking fans out to every consumer
			// view, including LIVE — that's accepted overhead; LIVE will short-circuit
			// on unchanged values anyway.)
			dirtyTracker.markDirty(consumed)
			throw t
		}
	}

	private suspend fun evaluate(consumed: Set<String>): Result {
		val instances = ruleRegistry.instancesAffectedByTables(consumed)
		if (instances.isEmpty()) return Result.success()

		val snapshot = metricsProvider.collect()
		if (snapshot.asMap().isEmpty()) return Result.success()

		// Read existing progress so we can seed the per-metric accumulator with the
		// last persisted lastTierIndex / lastValue. The registry already loaded the
		// same table to compute previousTier/previousValue on each RuleInstance, but
		// we keep this read here because the entity carries the FULL precision
		// `lastValue: Double` we must preserve when only some instances of a metric
		// change. The extra query is dwarfed by `metricsProvider.collect()` and only
		// runs on non-idle passes.
		val rows = achievementDao.getAll()
		val progressByMetric = rows.mapNotNull { row ->
			MetricKey.fromStorageKey(row.metricKey)?.let { it to row }
		}.toMap()

		val accumulators = HashMap<MetricKey, ProgressAccumulator>()
		for (instance in instances) {
			val metric = instance.rule.metric
			val currentValue = snapshot.valueOf(metric)
			val accum = accumulators.getOrPut(metric) {
				val existing = progressByMetric[metric]
				ProgressAccumulator(
					lastTierIndex = existing?.lastTierIndex ?: -1,
					lastValue = existing?.lastValue ?: 0.0,
				)
			}
			when (val result = RuleEvaluator.evaluate(instance, currentValue.toLong())) {
				is RuleEvaluationResult.Unchanged -> {
					// Nothing to persist — the instance's previousValue already
					// matches the current quantized value.
				}
				is RuleEvaluationResult.TierUnlocked -> {
					val definition = instance.attachment as? AchievementDefinition
					if (definition != null && definition.tierIndex > accum.lastTierIndex) {
						accum.lastTierIndex = definition.tierIndex
					}
					if (accum.lastValue != currentValue) accum.lastValue = currentValue
					accum.changed = true
				}
				is RuleEvaluationResult.ProgressUpdated -> {
					if (accum.lastValue != currentValue) {
						accum.lastValue = currentValue
						accum.changed = true
					}
				}
			}
		}

		val now = System.currentTimeMillis()
		val updates = accumulators
			.asSequence()
			.filter { it.value.changed }
			.map { (metric, a) ->
				AchievementProgressEntity(
					metricKey = metric.storageKey,
					lastTierIndex = a.lastTierIndex,
					lastValue = a.lastValue,
					updatedAt = now,
				)
			}
			.toList()
		if (updates.isNotEmpty()) achievementDao.upsertAll(updates)
		return Result.success()
	}

	private class ProgressAccumulator(
		var lastTierIndex: Int,
		var lastValue: Double,
		var changed: Boolean = false,
	)

	companion object {
		const val UNIQUE_WORK_NAME = "AchievementEvaluation"
		const val TAG = "Achievement"
	}
}
