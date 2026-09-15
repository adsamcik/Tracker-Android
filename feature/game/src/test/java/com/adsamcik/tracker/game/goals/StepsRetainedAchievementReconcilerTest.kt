package com.adsamcik.tracker.game.goals

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.AchievementProgressEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.stats.api.achievement.AchievementCatalog
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.metric.MetricKey
import com.adsamcik.tracker.stats.api.repository.DomainEventRepository
import com.adsamcik.tracker.stats.api.repository.StepsNumericUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.StepsRetainedMetrics
import com.adsamcik.tracker.stats.api.repository.StepsRetainedMetricsDecision
import com.adsamcik.tracker.stats.api.repository.StepsRetainedMetricsRepository
import com.adsamcik.tracker.stats.api.repository.UnconsumedEvent
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.data.repository.DefaultDomainEventRepository
import com.adsamcik.tracker.testing.TestDispatchersProvider
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StepsRetainedAchievementReconcilerTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext<Application>())
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `first ready snapshot bootstraps both retained metrics without notification`() = runTest {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(revision = 1L))
		val source = FakeRetainedMetricsRepository(ready(1L, 10_000L, 5_000L, 'a'))
		val events = RecordingDomainEvents()

		reconciler(testScheduler, source, events).reconcile(100L) shouldBe
			StepsRetainedAchievementReconcileResult.Applied(changed = true)

		val total = requireNotNull(row(MetricKey.STEPS_TOTAL))
		total.lastTierIndex shouldBe 1
		total.lastValue shouldBe 10_000.0
		total.lastUnlockedAt shouldBe null
		total.qualifiedNotificationClaimedTierIndex shouldBe 1
		total.assertReadyAuthority(1L, digest('a'))
		val best = requireNotNull(row(MetricKey.BEST_DAILY_STEPS))
		best.lastTierIndex shouldBe 0
		best.lastValue shouldBe 5_000.0
		best.lastUnlockedAt shouldBe null
		best.qualifiedNotificationClaimedTierIndex shouldBe 0
		best.assertReadyAuthority(1L, digest('a'))
		events.persisted shouldBe emptyList()
	}

	@Test
	fun `later multi tier advance emits exact events and correction restoration does not renotify`() =
		runTest {
			database.sourceEvidenceStateDao().ensure(SourceEvidenceState(revision = 1L))
			val source = FakeRetainedMetricsRepository(ready(1L, 5_000L, 5_000L, 'a'))
			val events = RecordingDomainEvents()
			val reconciler = reconciler(testScheduler, source, events)
			reconciler.reconcile(100L) shouldBe
				StepsRetainedAchievementReconcileResult.Applied(true)
			events.persisted shouldBe emptyList()

			database.sourceEvidenceStateDao().incrementRevision(150L) shouldBe 1
			source.decision = ready(2L, 1_000_000L, 40_000L, 'b')
			reconciler.reconcile(200L) shouldBe
				StepsRetainedAchievementReconcileResult.Applied(true)

			val expectedUnlocks = listOf(
				MetricKey.STEPS_TOTAL to 1..3,
				MetricKey.BEST_DAILY_STEPS to 1..3,
			).flatMap { (metric, tiers) ->
				AchievementCatalog.byMetric(metric)
					.filter { definition -> definition.tierIndex in tiers }
					.map { definition ->
						DomainEvent.AchievementUnlocked(
							timestampMs = EpochMs(200L),
							processorId = QUALIFIED_STEPS_ACHIEVEMENT_PROCESSOR_ID,
							achievementId = definition.id,
							tier = definition.tier.name,
							authorityRevision = 2L,
							authorityDigest = digest('b'),
						)
					}
			}
			events.persisted shouldBe expectedUnlocks

			database.sourceEvidenceStateDao().incrementRevision(250L) shouldBe 1
			source.decision = ready(3L, 100_000L, 10_000L, 'c')
			reconciler.reconcile(300L) shouldBe
				StepsRetainedAchievementReconcileResult.Applied(true)
			row(MetricKey.STEPS_TOTAL)?.lastTierIndex shouldBe 2
			row(MetricKey.STEPS_TOTAL)?.qualifiedNotificationClaimedTierIndex shouldBe 3
			row(MetricKey.BEST_DAILY_STEPS)?.lastTierIndex shouldBe 1
			row(MetricKey.BEST_DAILY_STEPS)?.qualifiedNotificationClaimedTierIndex shouldBe 3
			events.persisted shouldBe expectedUnlocks

			database.sourceEvidenceStateDao().incrementRevision(350L) shouldBe 1
			source.decision = ready(4L, 1_000_000L, 40_000L, 'd')
			reconciler.reconcile(400L) shouldBe
				StepsRetainedAchievementReconcileResult.Applied(true)
			row(MetricKey.STEPS_TOTAL)?.lastTierIndex shouldBe 3
			row(MetricKey.STEPS_TOTAL)?.qualifiedNotificationClaimedTierIndex shouldBe 3
			row(MetricKey.BEST_DAILY_STEPS)?.lastTierIndex shouldBe 3
			row(MetricKey.BEST_DAILY_STEPS)?.qualifiedNotificationClaimedTierIndex shouldBe 3
			events.persisted shouldBe expectedUnlocks

			database.sourceEvidenceStateDao().incrementRevision(450L) shouldBe 1
			source.decision = ready(5L, 10_000_000L, 60_000L, 'e')
			reconciler.reconcile(500L) shouldBe
				StepsRetainedAchievementReconcileResult.Applied(true)
			val newUnlocks = events.persisted.drop(expectedUnlocks.size)
			newUnlocks.map { event -> (event as DomainEvent.AchievementUnlocked).achievementId } shouldBe
				RETAINED_METRICS.map { metric ->
					AchievementCatalog.byMetric(metric).single { definition ->
						definition.tierIndex == 4
					}.id
				}
		}

	@Test
	fun `nonnumeric snapshots preserve existing values and high water without creating rows`() =
		runTest {
			database.sourceEvidenceStateDao().ensure(SourceEvidenceState(revision = 1L))
			seedQualifiedTotal()
			val source = FakeRetainedMetricsRepository(
				StepsRetainedMetricsDecision.Snapshot(
					sourceEvidenceRevision = 2L,
					result = StepsRetainedMetrics.Materializing,
					sourceResultDigest = digest('b'),
				),
			)
			val events = RecordingDomainEvents()
			val reconciler = reconciler(testScheduler, source, events)
			database.sourceEvidenceStateDao().incrementRevision(150L) shouldBe 1

			reconciler.reconcile(200L) shouldBe
				StepsRetainedAchievementReconcileResult.Materializing(changed = true)
			val materializing = requireNotNull(row(MetricKey.STEPS_TOTAL))
			materializing.lastTierIndex shouldBe 2
			materializing.lastValue shouldBe 100_000.0
			materializing.lastUnlockedAt shouldBe 90L
			materializing.qualifiedNotificationClaimedTierIndex shouldBe 3
			materializing.authorityState shouldBe
				AchievementProgressEntity.AUTHORITY_STATE_MATERIALIZING
			row(MetricKey.BEST_DAILY_STEPS) shouldBe null

			database.sourceEvidenceStateDao().incrementRevision(250L) shouldBe 1
			source.decision = StepsRetainedMetricsDecision.Snapshot(
				sourceEvidenceRevision = 3L,
				result = StepsRetainedMetrics.Unverifiable(
					StepsNumericUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE,
				),
				sourceResultDigest = digest('c'),
			)
			reconciler.reconcile(300L) shouldBe
				StepsRetainedAchievementReconcileResult.Unverifiable(changed = true)
			val unverifiable = requireNotNull(row(MetricKey.STEPS_TOTAL))
			unverifiable.lastTierIndex shouldBe 2
			unverifiable.lastValue shouldBe 100_000.0
			unverifiable.lastUnlockedAt shouldBe 90L
			unverifiable.qualifiedNotificationClaimedTierIndex shouldBe 3
			unverifiable.authorityState shouldBe
				AchievementProgressEntity.AUTHORITY_STATE_UNVERIFIABLE
			row(MetricKey.BEST_DAILY_STEPS) shouldBe null
			events.persisted shouldBe emptyList()
		}

	@Test
	fun `source revision mismatch rejects snapshot before any row or event commit`() = runTest {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(revision = 2L))
		val source = FakeRetainedMetricsRepository(ready(1L, 10_000L, 5_000L, 'a'))
		val events = RecordingDomainEvents()

		reconciler(testScheduler, source, events).reconcile(100L) shouldBe
			StepsRetainedAchievementReconcileResult.RetryableFailure

		retainedRows() shouldBe emptyList()
		events.persisted shouldBe emptyList()
	}

	@Test
	fun `startup generation change after retained read rejects stale snapshot`() = runTest {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(revision = 1L))
		val gate = MutableTrackingStartupGate()
		val source = FakeRetainedMetricsRepository(ready(1L, 10_000L, 5_000L, 'a')).apply {
			onRead = gate::advanceGeneration
		}
		val events = RecordingDomainEvents()

		reconciler(testScheduler, source, events, gate).reconcile(100L) shouldBe
			StepsRetainedAchievementReconcileResult.RetryableFailure

		retainedRows() shouldBe emptyList()
		events.persisted shouldBe emptyList()
	}

	@Test
	fun `storage unavailable is retryable and retained read cancellation propagates`() = runTest {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(revision = 1L))
		val source = FakeRetainedMetricsRepository(StepsRetainedMetricsDecision.StorageUnavailable)
		val events = RecordingDomainEvents()
		val reconciler = reconciler(testScheduler, source, events)

		reconciler.reconcile(100L) shouldBe StepsRetainedAchievementReconcileResult.RetryableFailure
		retainedRows() shouldBe emptyList()

		source.failure = CancellationException("retained read cancelled")
		val failure = runCatching { reconciler.reconcile(200L) }.exceptionOrNull()

		(failure is CancellationException) shouldBe true
		retainedRows() shouldBe emptyList()
		events.persisted shouldBe emptyList()
	}

	@Test
	fun `failed unlock outbox write rolls back both retained metric advances`() = runTest {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(revision = 1L))
		val source = FakeRetainedMetricsRepository(ready(1L, 5_000L, 5_000L, 'a'))
		reconciler(testScheduler, source).reconcile(100L) shouldBe
			StepsRetainedAchievementReconcileResult.Applied(true)
		val baseline = retainedRows()
		database.sourceEvidenceStateDao().incrementRevision(150L) shouldBe 1
		source.decision = ready(2L, 10_000L, 10_000L, 'b')

		reconciler(testScheduler, source, FailingDomainEvents).reconcile(200L) shouldBe
			StepsRetainedAchievementReconcileResult.RetryableFailure

		retainedRows() shouldBe baseline
	}

	@Test
	fun `qualified rows and exact unlock outbox commit together in Room`() = runTest {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(revision = 1L))
		val source = FakeRetainedMetricsRepository(ready(1L, 5_000L, 5_000L, 'a'))
		val events = DefaultDomainEventRepository(database.domainEventDao())
		val reconciler = reconciler(testScheduler, source, events)
		reconciler.reconcile(100L) shouldBe StepsRetainedAchievementReconcileResult.Applied(true)
		database.sourceEvidenceStateDao().incrementRevision(150L) shouldBe 1
		source.decision = ready(2L, 10_000L, 10_000L, 'b')

		reconciler.reconcile(200L) shouldBe StepsRetainedAchievementReconcileResult.Applied(true)

		val unlocks = events.getUnconsumedBatchWithIds("test", 10)
			.map { it.event as DomainEvent.AchievementUnlocked }
		unlocks.size shouldBe 2
		unlocks.forEach { event ->
			val metric = requireNotNull(AchievementCatalog.byId(event.achievementId)).metric
			val row = requireNotNull(row(metric))
			event.authorityRevision shouldBe row.authorityRevision
			event.authorityDigest shouldBe row.authorityDigest
			row.lastUnlockedAt shouldBe 200L
		}
	}

	@Test
	fun `unchanged retained snapshot is an exact no op`() = runTest {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(revision = 1L))
		val source = FakeRetainedMetricsRepository(ready(1L, 10_000L, 5_000L, 'a'))
		val events = RecordingDomainEvents()
		val reconciler = reconciler(testScheduler, source, events)
		reconciler.reconcile(100L) shouldBe
			StepsRetainedAchievementReconcileResult.Applied(true)
		val baseline = retainedRows()

		reconciler.reconcile(200L) shouldBe
			StepsRetainedAchievementReconcileResult.Applied(changed = false)

		retainedRows() shouldBe baseline
		events.persisted shouldBe emptyList()
	}

	private fun reconciler(
		testScheduler: TestCoroutineScheduler,
		source: StepsRetainedMetricsRepository,
		domainEvents: DomainEventRepository = RecordingDomainEvents(),
		startupGate: TrackingStartupGate = ReadyGate,
	) = StepsRetainedAchievementReconciler(
		database = database,
		retainedMetrics = source,
		dispatchers = TestDispatchersProvider(StandardTestDispatcher(testScheduler)),
		startupGate = startupGate,
		domainEvents = domainEvents,
	)

	private suspend fun row(metric: MetricKey): AchievementProgressEntity? =
		database.achievementProgressDao().getByMetric(metric.storageKey)

	private suspend fun retainedRows(): List<AchievementProgressEntity> = RETAINED_METRICS
		.mapNotNull { metric -> row(metric) }

	private suspend fun seedQualifiedTotal() {
		database.achievementProgressDao().replaceQualified(
			metricKey = MetricKey.STEPS_TOTAL.storageKey,
			lastTierIndex = 2,
			lastValue = 100_000.0,
			updatedAt = 90L,
			lastUnlockedAt = 90L,
			authorityKind = AchievementProgressEntity.AUTHORITY_QUALIFIED_STEPS_V1,
			authorityRevision = 1L,
			authorityDigest = digest('a'),
			authorityState = AchievementProgressEntity.AUTHORITY_STATE_READY,
			qualifiedNotificationClaimedTierIndex = 3,
		)
	}

	private fun AchievementProgressEntity.assertReadyAuthority(revision: Long, digest: String) {
		authorityKind shouldBe AchievementProgressEntity.AUTHORITY_QUALIFIED_STEPS_V1
		authorityRevision shouldBe revision
		authorityDigest shouldBe digest
		authorityState shouldBe AchievementProgressEntity.AUTHORITY_STATE_READY
	}

	private class FakeRetainedMetricsRepository(
		var decision: StepsRetainedMetricsDecision,
	) : StepsRetainedMetricsRepository {
		var onRead: (() -> Unit)? = null
		var failure: Throwable? = null

		override suspend fun readDecision(): StepsRetainedMetricsDecision {
			failure?.let { throw it }
			onRead?.invoke()
			return decision
		}
	}

	private class RecordingDomainEvents : DomainEventRepository {
		val persisted = mutableListOf<DomainEvent>()

		override suspend fun persist(events: List<DomainEvent>) {
			persisted += events
		}

		override fun observeEvents(since: EpochMs): Flow<List<DomainEvent>> = emptyFlow()

		override suspend fun getUnconsumedBatchWithIds(
			consumerId: String,
			limit: Int,
		): List<UnconsumedEvent> = emptyList()

		override suspend fun markBatchConsumed(
			consumerId: String,
			upToTimestamp: EpochMs,
			upToEventId: Long,
		) = Unit
	}

	private data object FailingDomainEvents : DomainEventRepository {
		override suspend fun persist(events: List<DomainEvent>): Unit = error("outbox unavailable")
		override fun observeEvents(since: EpochMs): Flow<List<DomainEvent>> = emptyFlow()
		override suspend fun getUnconsumedBatchWithIds(
			consumerId: String,
			limit: Int,
		): List<UnconsumedEvent> = emptyList()
		override suspend fun markBatchConsumed(
			consumerId: String,
			upToTimestamp: EpochMs,
			upToEventId: Long,
		) = Unit
	}

	private data object ReadyGate : TrackingStartupGate {
		override val isReady = true
		override val currentGeneration = 1L
		override suspend fun reconcile(retryFailedStorage: Boolean) =
			TrackingStartupResult.Ready(false, 0L)
	}

	private class MutableTrackingStartupGate : TrackingStartupGate {
		private var generation = 1L
		override val isReady = true
		override val currentGeneration: Long
			get() = generation

		override suspend fun reconcile(retryFailedStorage: Boolean) =
			TrackingStartupResult.Ready(false, 0L)

		fun advanceGeneration() {
			generation += 1L
		}
	}

	private companion object {
		val RETAINED_METRICS = listOf(MetricKey.STEPS_TOTAL, MetricKey.BEST_DAILY_STEPS)

		fun digest(character: Char): String = character.toString().repeat(64)

		fun ready(
			revision: Long,
			totalSteps: Long,
			bestDailySteps: Long,
			digestCharacter: Char,
		) = StepsRetainedMetricsDecision.Snapshot(
			sourceEvidenceRevision = revision,
			result = StepsRetainedMetrics.Ready(
				totalSteps = totalSteps,
				bestDailySteps = bestDailySteps,
				qualifiedDayCount = 1L,
			),
			sourceResultDigest = digest(digestCharacter),
		)
	}
}
