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
 * Contract test (R1 round-6 P2 regression seam):
 *
 * The live [AchievementProcessor] (`:stats-engine`) and the persistence
 * [AchievementWorker] (`:stats-data`) MUST produce identical unlock decisions for
 * the same input. Before this round both paths had separate evaluator
 * implementations and the worker silently disagreed with the live UI on which
 * tier was unlocked — the live celebration could persist a different tier (or
 * none at all) on the background pass.
 *
 * The unification routes both paths through [RuleRegistry] + [com.adsamcik.tracker.stats.api.rule.RuleEvaluator].
 * This test pins that property: for every interesting (snapshot, prior progress,
 * dirty set) triple, the SET of {(metric, max unlocked tier index)} from the
 * processor's emitted [DomainEvent.AchievementUnlocked] events equals the set
 * written to `achievement_progress` by the worker.
 *
 * If either path drifts — e.g. someone re-introduces a per-call threshold loop
 * in the worker, or the processor stops using the registry — this test fails.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AchievementEvaluatorContractTest {

	@Test
	fun `processor and worker agree on a single-metric multi-tier unlock`() = runTest {
		assertProcessorWorkerAgree(
			snapshot = MetricSnapshot.of(MetricKey.DISTANCE_TOTAL_M to 12_000),
			dirtyTables = setOf("daily_summary"),
			priorProgress = emptyList(),
		)
	}

	@Test
	fun `processor and worker agree on multiple metrics changing in one pass`() = runTest {
		assertProcessorWorkerAgree(
			snapshot = MetricSnapshot.of(
				MetricKey.DISTANCE_TOTAL_M to 50_000,
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
		assertProcessorWorkerAgree(
			snapshot = MetricSnapshot.of(MetricKey.DISTANCE_TOTAL_M to 60_000),
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
		assertProcessorWorkerAgree(
			snapshot = MetricSnapshot.of(MetricKey.DISTANCE_TOTAL_M to 1_700),
			dirtyTables = setOf("daily_summary"),
			priorProgress = listOf(priorDistanceRow),
		)
	}

	@Test
	fun `processor and worker agree when dirty tables map to nothing`() = runTest {
		// `export_log` is not in the catalog (no MetricKey has it in sourceTables
		// — only EXPORTS_TOTAL does, and that exists, but we use a synthetic table
		// that no metric maps to). Both paths must produce empty output and not
		// crash on the empty instance list.
		assertProcessorWorkerAgree(
			snapshot = MetricSnapshot.of(MetricKey.DISTANCE_TOTAL_M to 99_000),
			dirtyTables = setOf("nonexistent_table"),
			priorProgress = emptyList(),
		)
	}

	// --- helpers -----------------------------------------------------------

	private suspend fun assertProcessorWorkerAgree(
		snapshot: MetricSnapshot,
		dirtyTables: Set<String>,
		priorProgress: List<AchievementProgressEntity>,
	) {
		// Build registry + dao with shared state — processor and worker get the SAME
		// view of prior progress to avoid testing irrelevant divergence.
		val daoState = MutableStateFlow(priorProgress)
		val processorDao = stubAchievementProgressDao(daoState.asStateFlow())
		val workerDao = stubAchievementProgressDao(daoState.asStateFlow())

		val processorRegistry: RuleRegistry = AchievementRuleRegistry(processorDao)
		val workerRegistry: RuleRegistry = AchievementRuleRegistry(workerDao)

		// --- Processor path ---------------------------------------------------
		val processorDirty = DefaultMetricDirtyTracker()
		processorDirty.markDirty(dirtyTables)
		// Drain PERSISTENCE so this tracker only exercises the LIVE consumer.
		processorDirty.consumeDirty(MetricDirtyTracker.Consumer.PERSISTENCE)
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
		val events = processor.onFlush()
		val processorMaxTierByMetric: Map<MetricKey, Int> = events
			.filterIsInstance<DomainEvent.AchievementUnlocked>()
			.groupBy { event ->
				AchievementCatalog.byId(event.achievementId)!!.metric
			}
			.mapValues { (_, evs) -> evs.maxOf { AchievementCatalog.byId(it.achievementId)!!.tierIndex } }

		// --- Worker path ------------------------------------------------------
		val workerDirty = DefaultMetricDirtyTracker()
		workerDirty.markDirty(dirtyTables)
		workerDirty.consumeDirty(MetricDirtyTracker.Consumer.LIVE)

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

		// Compose worker's view of (metric → max tier index) from BOTH prior rows that
		// were preserved and new upserts. The worker only writes when something
		// changed, so unchanged rows remain at their prior tier.
		val workerMaxTierByMetric: Map<MetricKey, Int> = buildMap {
			for (row in priorProgress) {
				MetricKey.fromStorageKey(row.metricKey)?.let { put(it, row.lastTierIndex) }
			}
			for (row in capturedUpserts) {
				MetricKey.fromStorageKey(row.metricKey)?.let { put(it, row.lastTierIndex) }
			}
		}.filterValues { it >= 0 }

		// Reconstruct the processor's view too, accounting for prior rows the worker
		// would have to preserve (the processor's events only carry NEW unlocks).
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
					)
				},
			)
			.build()
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
}
