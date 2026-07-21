package com.adsamcik.tracker.stats.data.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.AchievementProgressDao
import com.adsamcik.tracker.shared.base.database.data.AchievementProgressEntity
import com.adsamcik.tracker.stats.api.AchievementDefinition
import com.adsamcik.tracker.stats.api.AchievementTier
import com.adsamcik.tracker.stats.api.achievement.AchievementCatalog
import com.adsamcik.tracker.stats.api.metric.MetricDirtyTracker
import com.adsamcik.tracker.stats.api.metric.MetricKey
import com.adsamcik.tracker.stats.api.metric.MetricSnapshot
import com.adsamcik.tracker.stats.api.metric.TimeWindow
import com.adsamcik.tracker.stats.api.repository.AchievementMetricsProvider
import com.adsamcik.tracker.stats.api.rule.Rule
import com.adsamcik.tracker.stats.api.rule.RuleInstance
import com.adsamcik.tracker.stats.api.rule.RuleKind
import com.adsamcik.tracker.stats.api.rule.RuleRegistry
import com.adsamcik.tracker.stats.api.rule.RuleTarget
import com.adsamcik.tracker.stats.data.metric.DefaultMetricDirtyTracker
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the AchievementWorker contract on the unified rule engine:
 *
 *  * the battery-critical short-circuit added in R2 round-6: the worker must not
 *    invoke [AchievementMetricsProvider.collect] (which fans out to a stack of
 *    aggregate queries) when the dirty tracker is empty;
 *  * the registry short-circuit: if the dirty tables map to NO rule instances,
 *    skip the metrics collection too;
 *  * the failure path: if evaluation throws, the consumed dirty bits are
 *    re-marked so the next scheduled run still observes the underlying writes;
 *  * the persisted progress shape: a successful run upserts one
 *    [AchievementProgressEntity] per metric with the max unlocked tier index and
 *    the latest snapshot value.
 *
 *  Together with `AchievementEvaluatorContractTest` (which pins worker + processor
 *  to identical evaluator semantics) and `DefaultMetricDirtyTrackerTest` (which
 *  pins per-consumer starvation safety), this is the regression seam for the R1
 *  round-6 unification finding.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AchievementWorkerTest {

	private fun newWorker(
		ruleRegistry: RuleRegistry,
		metricsProvider: AchievementMetricsProvider,
		achievementDao: AchievementProgressDao,
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
						ruleRegistry,
						metricsProvider,
						achievementDao,
						dirtyTracker,
						object : AchievementEvaluationTransactionRunner {
							override suspend fun run(block: suspend () -> Unit) = block()
						},
					)
				},
			)
			.build()
	}

	private fun ruleInstanceFor(definition: AchievementDefinition): RuleInstance = RuleInstance(
		rule = Rule(
			id = definition.id,
			kind = RuleKind.Achievement,
			metric = definition.metric,
			target = RuleTarget.Single(definition.threshold, definition.tier),
		),
		window = TimeWindow.Cumulative,
		previousValue = null,
		previousTier = null,
		attachment = definition,
	)

	@Test
	fun `empty dirty tracker short-circuits without touching registry or metrics provider`() = runTest {
		val registry = mockk<RuleRegistry>(relaxed = true)
		val metricsProvider = mockk<AchievementMetricsProvider>(relaxed = true)
		val achievementDao = mockk<AchievementProgressDao>(relaxed = true)
		val dirtyTracker = DefaultMetricDirtyTracker()

		val worker = newWorker(registry, metricsProvider, achievementDao, dirtyTracker)
		val result = worker.doWork()

		result shouldBe ListenableWorker.Result.success()
		coVerify(exactly = 0) { registry.instancesAffectedByTables(any()) }
		coVerify(exactly = 0) { registry.allInstances() }
		coVerify(exactly = 0) { metricsProvider.collect() }
		coVerify(exactly = 0) { achievementDao.getAll() }
		coVerify(exactly = 0) { achievementDao.upsertAll(any()) }
	}

	@Test
	fun `non-empty dirty but registry returns no instances short-circuits before metrics collection`() = runTest {
		val registry = mockk<RuleRegistry>()
		val metricsProvider = mockk<AchievementMetricsProvider>(relaxed = true)
		val achievementDao = mockk<AchievementProgressDao>(relaxed = true)
		coEvery { registry.instancesAffectedByTables(any()) } returns emptyList()

		val dirtyTracker = DefaultMetricDirtyTracker()
		dirtyTracker.markDirty("export_log") // no achievement maps to this in this test

		val worker = newWorker(registry, metricsProvider, achievementDao, dirtyTracker)
		val result = worker.doWork()

		result shouldBe ListenableWorker.Result.success()
		coVerify(exactly = 1) { registry.instancesAffectedByTables(any()) }
		coVerify(exactly = 0) { metricsProvider.collect() }
		coVerify(exactly = 0) { achievementDao.getAll() }
		coVerify(exactly = 0) { achievementDao.upsertAll(any()) }
	}

	@Test
	fun emptyMetricSnapshot_keepsDirtyOrRetries() = runTest {
		val definition = AchievementCatalog.byMetric(MetricKey.DISTANCE_TOTAL_M).first()
		val registry = mockk<RuleRegistry>()
		coEvery { registry.instancesAffectedByTables(any()) } returns listOf(ruleInstanceFor(definition))

		val metricsProvider = mockk<AchievementMetricsProvider>()
		coEvery { metricsProvider.collect() } returns MetricSnapshot.Empty

		val achievementDao = mockk<AchievementProgressDao>(relaxed = true)

		val dirtyTracker = DefaultMetricDirtyTracker()
		dirtyTracker.markDirty("daily_summary")

		val worker = newWorker(registry, metricsProvider, achievementDao, dirtyTracker)
		val result = worker.doWork()

		result shouldBe ListenableWorker.Result.retry()
		coVerify(exactly = 1) { metricsProvider.collect() }
		coVerify(exactly = 0) { achievementDao.getAll() }
		coVerify(exactly = 0) { achievementDao.upsertAll(any()) }
		dirtyTracker.snapshotDirty(MetricDirtyTracker.Consumer.PERSISTENCE).tables shouldContainExactlyInAnyOrder
			setOf("daily_summary")
	}

	@Test
	fun `unlocked instances persist max tier index per metric`() = runTest {
		val definitions = AchievementCatalog.byMetric(MetricKey.DISTANCE_TOTAL_M).take(3)
		// All three thresholds (1k, 5k, 10k) crossed by 10_000 — worker should write a
		// SINGLE row whose lastTierIndex equals the max tierIndex of the unlocked set.
		val registry = mockk<RuleRegistry>()
		coEvery { registry.instancesAffectedByTables(any()) } returnsMany listOf(
			definitions.map(::ruleInstanceFor),
			emptyList(),
		)

		val metricsProvider = mockk<AchievementMetricsProvider>()
		coEvery { metricsProvider.collect() } returns MetricSnapshot.of(
			MetricKey.DISTANCE_TOTAL_M to 10_000,
			MetricKey.ACTIVE_DAYS_TOTAL to 3,
		)

		val achievementDao = mockk<AchievementProgressDao>(relaxed = true)
		coEvery { achievementDao.getAll() } returns emptyList()
		val upsertSlot = slot<List<AchievementProgressEntity>>()
		coEvery { achievementDao.upsertAll(capture(upsertSlot)) } returns Unit

		val dirtyTracker = DefaultMetricDirtyTracker()
		dirtyTracker.markDirty("daily_summary")

		val worker = newWorker(registry, metricsProvider, achievementDao, dirtyTracker)
		val result = worker.doWork()

		result shouldBe ListenableWorker.Result.success()
		coVerify(exactly = 1) { metricsProvider.collect() }
		coVerify(exactly = 1) { achievementDao.upsertAll(any()) }

		val written = upsertSlot.captured
		written.size shouldBe 1
		val row = written.single()
		row.metricKey shouldBe MetricKey.DISTANCE_TOTAL_M.storageKey
		row.lastTierIndex shouldBe definitions.maxOf { it.tierIndex }
		row.lastValue shouldBe 10_000.0
	}

	@Test
	fun `dirty generation arriving during evaluation is drained before success`() = runTest {
		val definition = AchievementCatalog.byMetric(MetricKey.DISTANCE_TOTAL_M).first()
		val registry = mockk<RuleRegistry>()
		coEvery { registry.instancesAffectedByTables(any()) } returnsMany listOf(
			listOf(ruleInstanceFor(definition)),
			emptyList(),
		)
		val dirtyTracker = DefaultMetricDirtyTracker().apply {
			markDirty("daily_summary")
		}
		val metricsProvider = mockk<AchievementMetricsProvider>()
		coEvery { metricsProvider.collect() } answers {
			dirtyTracker.markDirty("session_segment")
			MetricSnapshot.of(
				MetricKey.DISTANCE_TOTAL_M to 2_000,
				MetricKey.ACTIVE_DAYS_TOTAL to 1,
			)
		}
		val achievementDao = mockk<AchievementProgressDao>(relaxed = true)
		coEvery { achievementDao.getAll() } returns emptyList()

		val result = newWorker(registry, metricsProvider, achievementDao, dirtyTracker).doWork()

		result shouldBe ListenableWorker.Result.success()
		coVerify(exactly = 2) { registry.instancesAffectedByTables(any()) }
		dirtyTracker.snapshotDirty(MetricDirtyTracker.Consumer.PERSISTENCE).isEmpty shouldBe true
	}

	@Test
	fun `metrics provider throwable leaves dirty snapshot unacknowledged`() = runTest {
		val definition = AchievementCatalog.byMetric(MetricKey.DISTANCE_TOTAL_M).first()
		val registry = mockk<RuleRegistry>()
		coEvery { registry.instancesAffectedByTables(any()) } returns listOf(ruleInstanceFor(definition))

		val metricsProvider = mockk<AchievementMetricsProvider>()
		coEvery { metricsProvider.collect() } throws IllegalStateException("boom")

		val achievementDao = mockk<AchievementProgressDao>(relaxed = true)

		val dirtyTracker = DefaultMetricDirtyTracker()
		dirtyTracker.markDirty(setOf("daily_summary", "session_segment"))
		// Acknowledge LIVE so the test only observes PERSISTENCE preservation.
		val liveSnapshot = dirtyTracker.snapshotDirty(MetricDirtyTracker.Consumer.LIVE)
		dirtyTracker.acknowledgeDirty(MetricDirtyTracker.Consumer.LIVE, liveSnapshot)

		val worker = newWorker(registry, metricsProvider, achievementDao, dirtyTracker)

		val thrown = runCatching { worker.doWork() }
		thrown.exceptionOrNull()!!.shouldBeInstanceOf<IllegalStateException>()
		// The unacknowledged snapshot must remain observable to the next worker pass.
		dirtyTracker.snapshotDirty(MetricDirtyTracker.Consumer.PERSISTENCE).tables shouldContainExactlyInAnyOrder setOf(
			"daily_summary", "session_segment",
		)
	}

	@Test
	fun overlappingWorkers_cannotRegressLastTierIndex() = runTest {
		val context = ApplicationProvider.getApplicationContext<Context>()
		val database = AppDatabase.testDatabase(context)
		try {
			val dao = database.achievementProgressDao()
			coroutineScope {
				listOf(
					async {
						dao.upsert(
							AchievementProgressEntity(
								metricKey = MetricKey.DISTANCE_TOTAL_M.storageKey,
								lastTierIndex = 7,
								lastValue = 70_000.0,
								updatedAt = 1L,
							),
						)
					},
					async {
						dao.upsert(
							AchievementProgressEntity(
								metricKey = MetricKey.DISTANCE_TOTAL_M.storageKey,
								lastTierIndex = 2,
								lastValue = 10_000.0,
								updatedAt = 2L,
							),
						)
					},
				).awaitAll()
			}

			dao.getByMetric(MetricKey.DISTANCE_TOTAL_M.storageKey)!!.lastTierIndex shouldBe 7
		} finally {
			database.close()
		}
	}
}
