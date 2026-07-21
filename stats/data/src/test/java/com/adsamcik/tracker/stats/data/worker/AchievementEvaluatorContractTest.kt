package com.adsamcik.tracker.stats.data.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.adsamcik.tracker.shared.base.database.dao.AchievementProgressDao
import com.adsamcik.tracker.shared.base.database.data.AchievementProgressEntity
import com.adsamcik.tracker.stats.api.achievement.AchievementCatalog
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.metric.MetricDirtyTracker
import com.adsamcik.tracker.stats.api.metric.MetricKey
import com.adsamcik.tracker.stats.api.metric.MetricSnapshot
import com.adsamcik.tracker.stats.api.repository.AchievementMetricsProvider
import com.adsamcik.tracker.stats.api.rule.RuleInstance
import com.adsamcik.tracker.stats.api.rule.RuleRegistry
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.data.achievement.AchievementRuleRegistry
import com.adsamcik.tracker.stats.data.metric.DefaultMetricDirtyTracker
import com.adsamcik.tracker.stats.engine.processor.AchievementProcessor
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Contract test (R1 round-6 P2 + round-7 P5 regression seam):
 *
 * The live [AchievementProcessor] (`:stats-engine`) and the persistence
 * [AchievementWorker] (`:stats-data`) MUST produce identical unlock decisions for
 * the same input. Before round-6 both paths had separate evaluator
 * implementations and the worker silently disagreed with the live UI on which
 * tier was unlocked — the live celebration could persist a different tier (or
 * none at all) on the background pass.
 *
 * The unification routes both paths through [RuleRegistry] +
 * [com.adsamcik.tracker.stats.api.rule.RuleEvaluator]. The round-6 version of
 * this test pinned only `(metric, max unlocked tier index)`, which left two
 * obvious drift dimensions uncovered:
 *
 *  * **Count** — a path could emit one event per metric while the other emits
 *    one event per crossed tier; the max-tier-per-metric check could not tell.
 *  * **Per-tier identity** — a path could swap which `achievementId` it tags a
 *    tier with (e.g. truncate `tier_3` into `tier_2`); again invisible to the
 *    max-tier check.
 *
 * Round-7 (R1 P5) extends the contract: for every interesting (snapshot, prior
 * progress, dirty set) triple we now compare the **full unlock-event stream**
 * the processor emits against a *virtual* event stream **derived** from the
 * worker's persisted upserts. For each upsert (`metric`, `priorTier`,
 * `newTier`) we synthesise the unlock events the worker MUST have walked
 * through (`tierIdx in (priorTier+1)..newTier`) by mapping back through
 * [AchievementCatalog.byMetric]. The two streams must agree on:
 *
 *  * the SET of `(metric, tierIndex)` unlocks,
 *  * the count of unlock events,
 *  * the stable ordering by `(metric.storageKey, tierIndex)`,
 *  * and the resolved `achievementId` per unlock.
 *
 * If either path drifts — e.g. someone re-introduces a per-call threshold loop
 * in the worker, the processor stops using the registry, the catalog mapping
 * changes, or a worker upsert lands on the wrong tier index — this test fails.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AchievementEvaluatorContractTest {

	@Test
	fun `processor and worker agree on a single-metric multi-tier unlock`() = runTest {
		assertEvaluatorsAgree(
			snapshot = MetricSnapshot.of(
				MetricKey.DISTANCE_TOTAL_M to 12_000,
				MetricKey.ACTIVE_DAYS_TOTAL to 3,
			),
			dirtyTables = setOf("daily_summary"),
			priorProgress = emptyList(),
		)
	}

	@Test
	fun `one motorized day unlocks only onboarding distance and active-day tiers`() = runTest {
		assertEvaluatorsAgree(
			snapshot = MetricSnapshot.of(
				MetricKey.DISTANCE_TOTAL_M to 500_000,
				MetricKey.ACTIVE_DAYS_TOTAL to 1,
				MetricKey.VEHICLE_DISTANCE_M to 500_000,
				MetricKey.VEHICLE_ACTIVE_DAYS to 1,
			),
			dirtyTables = setOf("daily_summary", "session_segment"),
			priorProgress = emptyList(),
			expectedUnlockCount = 3,
			expectedFinalTier = mapOf(
				MetricKey.DISTANCE_TOTAL_M to 0,
				MetricKey.VEHICLE_DISTANCE_M to 0,
			),
		)
	}

	@Test
	fun `processor and worker agree on multiple metrics changing in one pass`() = runTest {
		assertEvaluatorsAgree(
			snapshot = MetricSnapshot.of(
				MetricKey.DISTANCE_TOTAL_M to 50_000,
				MetricKey.ACTIVE_DAYS_TOTAL to 7,
				MetricKey.STEPS_TOTAL to 150_000,
				MetricKey.SESSIONS_TOTAL to 25,
			),
			dirtyTables = setOf("daily_summary", "aggregator_state"),
			priorProgress = emptyList(),
		)
	}

	@Test
	fun `processor and worker agree when some tiers are already unlocked`() = runTest {
		// Pre-existing progress: distance metric already at tier index 2 (i.e. 10_000).
		val priorDistanceRow = AchievementProgressEntity(
			metricKey = MetricKey.DISTANCE_TOTAL_M.storageKey,
			lastTierIndex = 2,
			lastValue = 10_000.0,
			updatedAt = 1L,
		)
		// Snapshot now 60_000 — should unlock tiers 3 and 4 but NOT re-fire 0..2.
		assertEvaluatorsAgree(
			snapshot = MetricSnapshot.of(
				MetricKey.DISTANCE_TOTAL_M to 60_000,
				MetricKey.ACTIVE_DAYS_TOTAL to 7,
			),
			dirtyTables = setOf("daily_summary"),
			priorProgress = listOf(priorDistanceRow),
		)
	}

	@Test
	fun `processor and worker agree when no tier crosses`() = runTest {
		val priorDistanceRow = AchievementProgressEntity(
			metricKey = MetricKey.DISTANCE_TOTAL_M.storageKey,
			lastTierIndex = 0,
			lastValue = 1_500.0,
			updatedAt = 1L,
		)
		// Bump of 200m — no new tier, no progress event at the long level.
		assertEvaluatorsAgree(
			snapshot = MetricSnapshot.of(
				MetricKey.DISTANCE_TOTAL_M to 1_700,
				MetricKey.ACTIVE_DAYS_TOTAL to 1,
			),
			dirtyTables = setOf("daily_summary"),
			priorProgress = listOf(priorDistanceRow),
		)
	}

	@Test
	fun `processor and worker agree when dirty tables map to nothing`() = runTest {
		// `nonexistent_table` is not in the sourceTables of any MetricKey, so the
		// registry must yield an empty instance list and BOTH paths must produce
		// empty output (zero events, zero upserts) without crashing.
		assertEvaluatorsAgree(
			snapshot = MetricSnapshot.of(MetricKey.DISTANCE_TOTAL_M to 99_000),
			dirtyTables = setOf("nonexistent_table"),
			priorProgress = emptyList(),
		)
	}

	// --- R1 round-7 P5: new scenarios that exercise the full event stream.

	@Test
	fun `processor and worker agree on a rapid multi-metric multi-tier cascade`() = runTest {
		// Three metrics, all starting from zero, all jumping across many tiers in
		// a SINGLE evaluation pass — the kind of bulk unlock that happens after
		// large data imports or first-launch backfill. This is the scenario that
		// most stresses ordering and count: 5 distance tiers (1k/5k/10k/50k/100k)
		// + 4 step tiers (1k/10k/100k/1M) + 4 session tiers (1/10/100/1000) =
		// 15 simultaneous unlock events including active-day tiers at days 1 and 7.
		// The max-tier-per-metric check sees all four metric families.
		assertEvaluatorsAgree(
			snapshot = MetricSnapshot.of(
				MetricKey.DISTANCE_TOTAL_M to 100_000,
				MetricKey.ACTIVE_DAYS_TOTAL to 14,
				MetricKey.STEPS_TOTAL to 1_000_000,
				MetricKey.SESSIONS_TOTAL to 1_000,
			),
			dirtyTables = setOf("daily_summary", "aggregator_state"),
			priorProgress = emptyList(),
			expectedUnlockCount = 15,
		)
	}

	@Test
	fun `processor and worker emit no events when prior value equals current value`() = runTest {
		// `RuleEvaluator.evaluate` short-circuits to `Unchanged` when
		// `previousValue == currentValue`. Both paths must therefore emit zero
		// events AND zero upserts even when the metric is well above several
		// tier thresholds — this guards against either path "re-emitting" a
		// stable value as a fresh unlock on every flush.
		val priorRow = AchievementProgressEntity(
			metricKey = MetricKey.DISTANCE_TOTAL_M.storageKey,
			lastTierIndex = 2,
			lastValue = 10_000.0,
			updatedAt = 1L,
		)
		assertEvaluatorsAgree(
			snapshot = MetricSnapshot.of(MetricKey.DISTANCE_TOTAL_M to 10_000),
			dirtyTables = setOf("daily_summary"),
			priorProgress = listOf(priorRow),
			expectedUnlockCount = 0,
		)
	}

	@Test
	fun `processor and worker emit no events for metrics removed from the registry`() = runTest {
		// Simulate a definition being removed from the catalog (e.g. a metric
		// retired in a future release): wrap the production registry with a
		// filter that drops every STEPS_TOTAL instance. Both paths must produce
		// zero events for the filtered metric AND must NOT overwrite its prior
		// progress row — the worker only writes when something changes, and a
		// retired rule has nothing to evaluate. Distance progress in the same
		// pass must still resolve normally so we can tell "registry filtering
		// works" from "registry returned nothing at all".
		val priorRows = listOf(
			AchievementProgressEntity(
				metricKey = MetricKey.STEPS_TOTAL.storageKey,
				lastTierIndex = 1,
				lastValue = 10_000.0,
				updatedAt = 1L,
			),
			AchievementProgressEntity(
				metricKey = MetricKey.DISTANCE_TOTAL_M.storageKey,
				lastTierIndex = -1,
				lastValue = 0.0,
				updatedAt = 1L,
			),
		)
		assertEvaluatorsAgree(
			snapshot = MetricSnapshot.of(
				// Without filtering, this would unlock several step tiers.
				MetricKey.STEPS_TOTAL to 5_000_000,
				MetricKey.DISTANCE_TOTAL_M to 12_000,
				MetricKey.ACTIVE_DAYS_TOTAL to 3,
			),
			dirtyTables = setOf("daily_summary", "aggregator_state"),
			priorProgress = priorRows,
			registryFactory = { dao ->
				FilteringRuleRegistry(
					delegate = AchievementRuleRegistry(dao),
					excludedMetrics = setOf(MetricKey.STEPS_TOTAL),
				)
			},
			// 3 distance tiers plus the first active-day tier; STEPS is filtered out entirely.
			expectedUnlockCount = 4,
		)
	}

	@Test
	fun `processor and worker do not un-unlock tiers when metric value regresses`() = runTest {
		// A metric value can legitimately move backward (data corrections, deleted
		// days, re-derivation of aggregates). The worker must NOT regress the
		// persisted `lastTierIndex`, and neither path may emit a fresh unlock
		// event for tiers that were already unlocked. This pins the
		// "achievements never un-unlock" invariant that users rely on.
		val priorRow = AchievementProgressEntity(
			metricKey = MetricKey.DISTANCE_TOTAL_M.storageKey,
			lastTierIndex = 3,
			lastValue = 50_000.0,
			updatedAt = 1L,
		)
		assertEvaluatorsAgree(
			snapshot = MetricSnapshot.of(MetricKey.DISTANCE_TOTAL_M to 10_000),
			dirtyTables = setOf("daily_summary"),
			priorProgress = listOf(priorRow),
			expectedUnlockCount = 0,
			expectedFinalTier = mapOf(MetricKey.DISTANCE_TOTAL_M to 3),
		)
	}

	// --- helpers -----------------------------------------------------------

	private suspend fun assertEvaluatorsAgree(
		snapshot: MetricSnapshot,
		dirtyTables: Set<String>,
		priorProgress: List<AchievementProgressEntity>,
		registryFactory: (AchievementProgressDao) -> RuleRegistry = { AchievementRuleRegistry(it) },
		expectedUnlockCount: Int? = null,
		expectedFinalTier: Map<MetricKey, Int>? = null,
	) {
		// --- Processor path ---------------------------------------------------
		val processorDao = stubAchievementProgressDao(MutableStateFlow(priorProgress).asStateFlow())
		val processorRegistry: RuleRegistry = registryFactory(processorDao)
		val processorDirty = DefaultMetricDirtyTracker().apply {
			markDirty(dirtyTables)
		}
		processorDirty.acknowledgeConsumer(MetricDirtyTracker.Consumer.PERSISTENCE)
		val processor = AchievementProcessor(
			registry = processorRegistry,
			metricsProvider = { snapshot },
			dirtyTracker = processorDirty,
		)
		processor.onStart(
			com.adsamcik.tracker.stats.api.processor.ProcessorContext(
				startTimestamp = EpochMs(0L),
			),
		)
		val processorEvents = processor.onFlush()
		val processorUnlocks = processorEvents.filterIsInstance<DomainEvent.AchievementUnlocked>()

		// --- Worker path ------------------------------------------------------
		val workerDao = stubAchievementProgressDao(MutableStateFlow(priorProgress).asStateFlow())
		val workerRegistry: RuleRegistry = registryFactory(workerDao)
		val workerDirty = DefaultMetricDirtyTracker().apply {
			markDirty(dirtyTables)
		}
		workerDirty.acknowledgeConsumer(MetricDirtyTracker.Consumer.LIVE)

		val metricsProvider = mockk<AchievementMetricsProvider>()
		coEvery { metricsProvider.collect() } returns snapshot

		val capturedUpserts = mutableListOf<AchievementProgressEntity>()
		val mutableWorkerDao = mockk<AchievementProgressDao>(relaxed = true)
		coEvery { mutableWorkerDao.getAll() } returns priorProgress
		coEvery { mutableWorkerDao.upsertAll(any()) } answers {
			capturedUpserts.addAll(firstArg<List<AchievementProgressEntity>>())
		}

		val worker = buildWorker(workerRegistry, metricsProvider, mutableWorkerDao, workerDirty)
		val result = worker.doWork()
		result shouldBe ListenableWorker.Result.success()

		// --- Compare unlock streams ------------------------------------------
		// 1. Derive processor's unlock keys: (metric, tierIndex, achievementId) per
		// real DomainEvent.AchievementUnlocked.
		val processorUnlockKeys: List<UnlockKey> = processorUnlocks.map { event ->
			val definition = AchievementCatalog.byId(event.achievementId)
				?: error("Processor emitted unknown achievementId=${event.achievementId}")
			UnlockKey(definition.metric, definition.tierIndex, definition.id)
		}

		// 2. Derive worker's *virtual* unlock keys: for each upsert, walk
		// (priorTier+1)..upsert.lastTierIndex and look up the matching definition.
		// This is the inverse of the processor's per-event emission: the worker
		// collapses the unlock cascade into one row but the unlocks it represents
		// MUST round-trip back to the same set of definitions.
		val priorTierByMetric: Map<MetricKey, Int> = priorProgress.mapNotNull { row ->
			MetricKey.fromStorageKey(row.metricKey)?.let { it to row.lastTierIndex }
		}.toMap()

		val workerUnlockKeys: List<UnlockKey> = capturedUpserts.flatMap { row ->
			val metric = MetricKey.fromStorageKey(row.metricKey) ?: return@flatMap emptyList()
			val priorTier = priorTierByMetric[metric] ?: -1
			if (row.lastTierIndex <= priorTier) return@flatMap emptyList()
			val definitionsByTier = AchievementCatalog.byMetric(metric).associateBy { it.tierIndex }
			((priorTier + 1)..row.lastTierIndex).mapNotNull { tierIdx ->
				definitionsByTier[tierIdx]?.let { UnlockKey(metric, tierIdx, it.id) }
			}
		}

		val sortKey: (UnlockKey) -> String = { k -> "${k.metric.storageKey}#${k.tierIndex}" }
		val processorSorted = processorUnlockKeys.sortedBy(sortKey)
		val workerSorted = workerUnlockKeys.sortedBy(sortKey)

		// Same COUNT of unlock events on both paths.
		workerUnlockKeys.size shouldBe processorUnlockKeys.size
		// Same SET of (metric, tierIndex) unlocks.
		workerUnlockKeys.toSet() shouldBe processorUnlockKeys.toSet()
		// Same stable ORDERING by (metric.storageKey, tierIndex).
		workerSorted shouldBe processorSorted
		// Same resolved achievementId per unlock — guards against catalog drift.
		workerSorted.map { it.achievementId } shouldBe processorSorted.map { it.achievementId }

		// Optional shape assertions ------------------------------------------
		if (expectedUnlockCount != null) {
			processorUnlocks.size shouldBe expectedUnlockCount
		}
		if (expectedFinalTier != null) {
			val workerFinalTier: Map<MetricKey, Int> = buildMap {
				for (row in priorProgress) {
					MetricKey.fromStorageKey(row.metricKey)?.let { put(it, row.lastTierIndex) }
				}
				for (row in capturedUpserts) {
					MetricKey.fromStorageKey(row.metricKey)?.let { put(it, row.lastTierIndex) }
				}
			}
			for ((metric, expectedTier) in expectedFinalTier) {
				(workerFinalTier[metric] ?: -1) shouldBe expectedTier
			}
		}

		// --- Backwards-compat: also pin the round-6 max-tier-per-metric view.
		// Both worker and processor MUST agree on the final max unlocked tier
		// per metric, accounting for prior progress preserved on either side.
		val processorMaxTierByMetric: Map<MetricKey, Int> = processorUnlockKeys
			.groupBy { it.metric }
			.mapValues { (_, keys) -> keys.maxOf { it.tierIndex } }

		val workerMaxTierByMetric: Map<MetricKey, Int> = buildMap {
			for (row in priorProgress) {
				MetricKey.fromStorageKey(row.metricKey)?.let { put(it, row.lastTierIndex) }
			}
			for (row in capturedUpserts) {
				MetricKey.fromStorageKey(row.metricKey)?.let { put(it, row.lastTierIndex) }
			}
		}.filterValues { it >= 0 }

		val processorViewWithPrior: Map<MetricKey, Int> = buildMap {
			for (row in priorProgress) {
				MetricKey.fromStorageKey(row.metricKey)?.let { put(it, row.lastTierIndex) }
			}
			for ((metric, tier) in processorMaxTierByMetric) {
				val current = get(metric) ?: -1
				if (tier > current) put(metric, tier)
			}
		}.filterValues { it >= 0 }

		workerMaxTierByMetric shouldBe processorViewWithPrior
	}

	private fun buildWorker(
		registry: RuleRegistry,
		metricsProvider: AchievementMetricsProvider,
		dao: AchievementProgressDao,
		dirtyTracker: MetricDirtyTracker,
	): AchievementWorker {
		val context = ApplicationProvider.getApplicationContext<Context>()
		return TestListenableWorkerBuilder<AchievementWorker>(context)
			.setWorkerFactory(
				object : androidx.work.WorkerFactory() {
					override fun createWorker(
						appContext: Context,
						workerClassName: String,
						workerParameters: androidx.work.WorkerParameters,
					): ListenableWorker = AchievementWorker(
						appContext,
						workerParameters,
						registry,
						metricsProvider,
						dao,
						dirtyTracker,
						object : AchievementEvaluationTransactionRunner {
							override suspend fun run(block: suspend () -> Unit) = block()
						},
					)
				},
			)
			.build()
	}

	private suspend fun MetricDirtyTracker.acknowledgeConsumer(consumer: MetricDirtyTracker.Consumer) {
		val snapshot = snapshotDirty(consumer)
		acknowledgeDirty(consumer, snapshot)
	}

	private fun stubAchievementProgressDao(
		state: kotlinx.coroutines.flow.StateFlow<List<AchievementProgressEntity>>,
	): AchievementProgressDao {
		val dao = mockk<AchievementProgressDao>(relaxed = true)
		coEvery { dao.getAll() } answers { state.value }
		coEvery { dao.getByMetric(any()) } answers {
			val key = firstArg<String>()
			state.value.firstOrNull { it.metricKey == key }
		}
		return dao
	}

	/**
	 * Triple identifying one unlock event independent of timestamp / processor id.
	 * Pins three drift dimensions at once: which METRIC the unlock belongs to,
	 * which TIER index it represents, and which catalog-resolved ID surfaces it.
	 */
	private data class UnlockKey(
		val metric: MetricKey,
		val tierIndex: Int,
		val achievementId: String,
	)

	/**
	 * Wraps a real [RuleRegistry] and drops every instance whose metric is in
	 * [excludedMetrics]. Used to simulate "rule removed from catalog" without
	 * mutating the singleton [AchievementCatalog].
	 */
	private class FilteringRuleRegistry(
		private val delegate: RuleRegistry,
		private val excludedMetrics: Set<MetricKey>,
	) : RuleRegistry {
		override suspend fun allInstances(): List<RuleInstance> =
			delegate.allInstances().filterNot { it.rule.metric in excludedMetrics }

		override suspend fun instancesAffectedByTables(
			dirtyTables: Set<String>,
		): List<RuleInstance> = delegate
			.instancesAffectedByTables(dirtyTables)
			.filterNot { it.rule.metric in excludedMetrics }
	}
}
