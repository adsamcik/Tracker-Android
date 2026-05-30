package com.adsamcik.tracker.stats.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.adsamcik.tracker.shared.base.database.dao.AchievementProgressDao
import com.adsamcik.tracker.shared.base.database.data.AchievementProgressEntity
import com.adsamcik.tracker.stats.api.achievement.AchievementEvaluator
import com.adsamcik.tracker.stats.api.metric.MetricDirtyTracker
import com.adsamcik.tracker.stats.api.metric.MetricKey
import com.adsamcik.tracker.stats.api.repository.AchievementMetricsProvider
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Background WorkManager worker that recomputes persisted achievement progress.
 *
 * **Battery-critical short-circuit (R2 round-6 perf review):** the worker mirrors
 * the snapshot pattern from `AchievementProcessor.runEvaluation`:
 *
 *  1. Atomically consume the dirty-tracker set at the very top of `doWork()`.
 *  2. If the set is empty, return `Result.success()` IMMEDIATELY without calling
 *     `metricsProvider.collect()` or hitting the achievement DAO — so the
 *     idle-flush cost on a stationary device is one atomic CAS read, period.
 *  3. On any non-cancellation throwable AFTER consuming the dirty set, re-mark
 *     the consumed tables so the next worker pass still observes the changes
 *     (mirrors `AchievementProcessor`'s exception path).
 *  4. Cancellation re-throws so structured concurrency is honoured.
 *
 * The `metricsProvider.collect()` path materialises full lifetime aggregates from
 * `daily_summary` + `session_segment` + `exploration_*`. Running it on every
 * scheduled tick when nothing has been written was the bulk of the avoidable
 * work. The processor in `:stats-engine` already uses this guard for the in-session
 * 60-second flush; the worker is the persistence-tier mirror used by the periodic
 * background scheduler.
 */
@HiltWorker
class AchievementWorker @AssistedInject constructor(
	@Assisted context: Context,
	@Assisted params: WorkerParameters,
	private val metricsProvider: AchievementMetricsProvider,
	private val achievementDao: AchievementProgressDao,
	private val dirtyTracker: MetricDirtyTracker,
) : CoroutineWorker(context, params) {
	private val evaluator = AchievementEvaluator()

	override suspend fun doWork(): Result {
		// Snapshot the dirty set FIRST. consumeDirty() is an atomic swap — writes that
		// race this check land in the next scheduled window, never lost.
		val consumed = dirtyTracker.consumeDirty()
		if (consumed.isEmpty()) return Result.success()

		return try {
			evaluate()
		} catch (e: kotlinx.coroutines.CancellationException) {
			throw e
		} catch (t: Throwable) {
			// Preserve the dirty bit so the next scheduled run still observes the
			// underlying changes. Without this, an exception below would silently
			// drop the next update window.
			dirtyTracker.markDirty(consumed)
			throw t
		}
	}

	private suspend fun evaluate(): Result {
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
