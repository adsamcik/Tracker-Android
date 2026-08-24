package com.adsamcik.tracker.stats.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.adsamcik.tracker.shared.base.database.dao.AchievementProgressDao
import com.adsamcik.tracker.shared.base.database.data.AchievementProgressEntity
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.stats.api.AchievementDefinition
import com.adsamcik.tracker.stats.api.metric.MetricDirtyTracker
import com.adsamcik.tracker.stats.api.metric.MetricKey
import com.adsamcik.tracker.stats.api.metric.MetricKeys
import com.adsamcik.tracker.stats.api.repository.AchievementMetricsProvider
import com.adsamcik.tracker.stats.api.rule.AchievementRules
import com.adsamcik.tracker.stats.api.rule.RuleEvaluationResult
import com.adsamcik.tracker.stats.api.rule.RuleEvaluator
import com.adsamcik.tracker.stats.api.rule.RuleRegistry
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import javax.inject.Provider

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
 *  1. Snapshot the PERSISTENCE-consumer dirty generations at the top of each pass.
 *     They remain pending until the transaction commits and the snapshot is
 *     acknowledged.
 *  2. If the set is empty, return `Result.success()` IMMEDIATELY — no registry
 *     query, no `metricsProvider.collect()`, no DAO read. Idle-flush cost is one
 *     atomic CAS read, period.
 *  3. If the registry maps the dirty tables to NO rule instances, short-circuit
 *     again before paying for [AchievementMetricsProvider.collect].
 *  4. Failed, cancelled, or empty-snapshot evaluations are not acknowledged.
 *  5. Continue taking snapshots until no dirty generation remains, so KEEP-suppressed
 *     schedule requests that arrive mid-run are still handled.
 */
@HiltWorker
class AchievementWorker @AssistedInject constructor(
	@Assisted context: Context,
	@Assisted params: WorkerParameters,
	@AchievementRules private val ruleRegistryProvider: Provider<RuleRegistry>,
	private val metricsProviderProvider: Provider<AchievementMetricsProvider>,
	private val achievementDaoProvider: Provider<AchievementProgressDao>,
	private val dirtyTracker: MetricDirtyTracker,
	private val transactionRunnerProvider: Provider<AchievementEvaluationTransactionRunner>,
	private val trackingStartupGate: TrackingStartupGate,
) : CoroutineWorker(context, params) {

	override suspend fun doWork(): Result {
		var dependencies: EvaluationDependencies? = null
		var startupGeneration = -1L
		while (true) {
			val snapshot = dirtyTracker.snapshotDirty(MetricDirtyTracker.Consumer.PERSISTENCE)
			if (snapshot.isEmpty) {
				if (dirtyTracker.acknowledgeDirty(MetricDirtyTracker.Consumer.PERSISTENCE, snapshot)) {
					return Result.success()
				}
				continue
			}

			if (dependencies == null) {
				startupGeneration = trackingStartupGate.currentGeneration
				when (trackingStartupGate.reconcile()) {
					is TrackingStartupResult.Ready -> Unit
					is TrackingStartupResult.RetryableFailure -> return Result.retry()
					is TrackingStartupResult.Blocked -> return Result.success()
				}
				if (!isReadyGeneration(startupGeneration)) return Result.success()
				val ruleRegistry = ruleRegistryProvider.get()
				if (!isReadyGeneration(startupGeneration)) return Result.success()
				val metricsProvider = metricsProviderProvider.get()
				if (!isReadyGeneration(startupGeneration)) return Result.success()
				val achievementDao = achievementDaoProvider.get()
				if (!isReadyGeneration(startupGeneration)) return Result.success()
				val transactionRunner = transactionRunnerProvider.get()
				dependencies = EvaluationDependencies(
					ruleRegistry = ruleRegistry,
					metricsProvider = metricsProvider,
					achievementDao = achievementDao,
					transactionRunner = transactionRunner,
				)
			}

			val completed = try {
				evaluate(snapshot.tables, requireNotNull(dependencies), startupGeneration)
			} catch (_: StartupGenerationChangedException) {
				return Result.success()
			}
			if (!completed) return Result.retry()

			if (!isReadyGeneration(startupGeneration)) return Result.success()
			dirtyTracker.acknowledgeDirty(MetricDirtyTracker.Consumer.PERSISTENCE, snapshot)
		}
	}

	private suspend fun evaluate(
		dirtyTables: Set<String>,
		dependencies: EvaluationDependencies,
		startupGeneration: Long,
	): Boolean {
		var completed = false
		var wroteUpdates = false
		dependencies.transactionRunner.run {
			requireReadyGeneration(startupGeneration)
			try {
				val instances = dependencies.ruleRegistry.instancesAffectedByTables(dirtyTables)
				if (instances.isEmpty()) {
					completed = true
					return@run
				}

				requireReadyGeneration(startupGeneration)
				val snapshot = dependencies.metricsProvider.collect()
				if (snapshot.asMap().isEmpty()) {
					return@run
				}

			// Read existing progress so we can seed the per-metric accumulator with the
			// last persisted lastTierIndex / lastValue. The registry already loaded the
			// same table to compute previousTier/previousValue on each RuleInstance, but
			// we keep this read here because the entity carries the FULL precision
			// `lastValue: Double` we must preserve when only some instances of a metric
			// change. The extra query is dwarfed by `metricsProvider.collect()` and only
			// runs on non-idle passes.
				requireReadyGeneration(startupGeneration)
				val rows = dependencies.achievementDao.getAll()
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
				if (accum.lastValue != currentValue) {
					accum.lastValue = currentValue
					accum.changed = true
				}

				val definition = instance.attachment as? AchievementDefinition
				if (definition != null && definition.minimumActiveDays > 1) {
					val pacingDays = snapshot.valueOf(definition.pacingMetric).toLong()
					if (!definition.isEligible(pacingDays)) continue
				}
				// Eligibility can change while the raw metric is unchanged. Force a
				// threshold check; previousTier still prevents duplicate unlocks.
				val effectiveInstance = if ((definition?.minimumActiveDays ?: 1) > 1) instance.copy(previousValue = null) else instance
				when (val result = RuleEvaluator.evaluate(effectiveInstance, currentValue.toLong())) {
					is RuleEvaluationResult.Unchanged -> Unit
					is RuleEvaluationResult.TierUnlocked -> {
						if (definition != null && definition.tierIndex > accum.lastTierIndex) {
							accum.lastTierIndex = definition.tierIndex
						}
						accum.changed = true
					}
					is RuleEvaluationResult.ProgressUpdated -> Unit
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
				if (updates.isNotEmpty()) {
					requireReadyGeneration(startupGeneration)
					dependencies.achievementDao.upsertAll(updates)
					wroteUpdates = true
				}
				completed = true
			} finally {
				// The Room transaction runner must see the fence failure so it rolls back
				// an evaluation that crossed into a newer startup generation.
				requireReadyGeneration(startupGeneration)
			}
		}
		if (wroteUpdates) {
			// Mark only after the transaction commits. The loop will evaluate this
			// newer generation before returning.
			dirtyTracker.markDirty(MetricKeys.TABLE_ACHIEVEMENT_PROGRESS)
		}
		return completed
	}

	private fun isReadyGeneration(startupGeneration: Long): Boolean =
		trackingStartupGate.isReady && trackingStartupGate.currentGeneration == startupGeneration

	private fun requireReadyGeneration(startupGeneration: Long) {
		if (!isReadyGeneration(startupGeneration)) throw StartupGenerationChangedException
	}

	private data class EvaluationDependencies(
		val ruleRegistry: RuleRegistry,
		val metricsProvider: AchievementMetricsProvider,
		val achievementDao: AchievementProgressDao,
		val transactionRunner: AchievementEvaluationTransactionRunner,
	)

	private class ProgressAccumulator(
		var lastTierIndex: Int,
		var lastValue: Double,
		var changed: Boolean = false,
	)

	companion object {
		const val UNIQUE_WORK_NAME = "AchievementEvaluation"
		const val WORK_TAG = "Achievement"
	}

	private object StartupGenerationChangedException : RuntimeException()
}
