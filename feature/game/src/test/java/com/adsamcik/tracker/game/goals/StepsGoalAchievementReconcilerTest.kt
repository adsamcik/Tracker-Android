package com.adsamcik.tracker.game.goals

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.AchievementProgressEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.StepsGoalEffectEntity
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.stats.api.metric.MetricKey
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.repository.DomainEventRepository
import com.adsamcik.tracker.stats.api.repository.UnconsumedEvent
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.data.repository.DefaultDomainEventRepository
import com.adsamcik.tracker.testing.TestDispatchersProvider
import io.kotest.matchers.shouldBe
import java.time.LocalDate
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
class StepsGoalAchievementReconcilerTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext<Application>())
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `complete daily decisions derive longest streak and exact ISO weeks`() = runTest {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(revision = 4L))
		(MONDAY..SUNDAY).forEach { day -> database.stepsGoalEffectDao().recordDecision(effect(day)) }
		database.achievementProgressDao().upsertMonotonic(
			metricKey = MetricKey.GOAL_STREAK_DAYS.storageKey,
			lastTierIndex = Int.MAX_VALUE,
			lastValue = 999.0,
			updatedAt = 50L,
		)
		val events = RecordingDomainEvents()
		reconciler(testScheduler, events).reconcile(200L) shouldBe
			StepsGoalAchievementReconcileResult.Applied(changed = true)

		val streak = requireNotNull(
			database.achievementProgressDao().getByMetric(MetricKey.GOAL_STREAK_DAYS.storageKey),
		)
		streak.lastValue shouldBe 7.0
		streak.lastTierIndex shouldBe 1
		streak.authorityState shouldBe AchievementProgressEntity.AUTHORITY_STATE_READY
		val weeks = requireNotNull(
			database.achievementProgressDao().getByMetric(MetricKey.PERFECT_WEEKS.storageKey),
		)
		weeks.lastValue shouldBe 1.0
		weeks.lastTierIndex shouldBe 0
		streak.qualifiedNotificationClaimedTierIndex shouldBe 1
		streak.lastUnlockedAt shouldBe null
		events.persisted shouldBe emptyList()
	}

	@Test
	fun `corrected daily decision can lower and retract a prior streak tier`() = runTest {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(revision = 1L))
		(MONDAY..MONDAY + 2L).forEach { day ->
			database.stepsGoalEffectDao().recordDecision(effect(day))
		}
		val reconciler = reconciler(testScheduler)
		reconciler.reconcile(200L) shouldBe StepsGoalAchievementReconcileResult.Applied(true)

		database.sourceEvidenceStateDao().incrementRevision(250L) shouldBe 1
		database.stepsGoalEffectDao().recordDecision(
			effect(MONDAY + 1L, complete = false, sourceRevision = 2L, digest = 'b'),
		)
		reconciler.reconcile(300L) shouldBe StepsGoalAchievementReconcileResult.Applied(true)

		val streak = requireNotNull(
			database.achievementProgressDao().getByMetric(MetricKey.GOAL_STREAK_DAYS.storageKey),
		)
		streak.lastValue shouldBe 1.0
		streak.lastTierIndex shouldBe -1
		streak.authorityRevision shouldBe 2L
		streak.qualifiedNotificationClaimedTierIndex shouldBe 0
	}

	@Test
	fun `pending historical repair hides existing qualified streak rows`() = runTest {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(revision = 1L))
		(MONDAY..MONDAY + 2L).forEach { day ->
			database.stepsGoalEffectDao().recordDecision(effect(day))
		}
		val reconciler = reconciler(testScheduler)
		reconciler.reconcile(200L) shouldBe StepsGoalAchievementReconcileResult.Applied(true)
		database.stepsGoalRepairDayDao().enqueue(MONDAY + 1L, 1L)

		reconciler.reconcile(300L) shouldBe
			StepsGoalAchievementReconcileResult.Materializing(changed = true)

		GOAL_METRICS.forEach { metric ->
			database.achievementProgressDao().getByMetric(metric.storageKey)?.authorityState shouldBe
				AchievementProgressEntity.AUTHORITY_STATE_MATERIALIZING
		}
	}

	@Test
	fun `unchanged authority is a no-op`() = runTest {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(revision = 1L))
		database.stepsGoalEffectDao().recordDecision(effect(MONDAY))
		val reconciler = reconciler(testScheduler)

		reconciler.reconcile(200L) shouldBe StepsGoalAchievementReconcileResult.Applied(true)
		reconciler.reconcile(300L) shouldBe StepsGoalAchievementReconcileResult.Applied(false)

	}

	@Test
	fun `empty and unverifiable daily history remain nonnumeric`() = runTest {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(revision = 1L))
		val reconciler = reconciler(testScheduler)

		reconciler.reconcile(100L) shouldBe StepsGoalAchievementReconcileResult.Unverifiable(true)
		GOAL_METRICS.forEach { metric ->
			val row = requireNotNull(database.achievementProgressDao().getByMetric(metric.storageKey))
			row.lastTierIndex shouldBe -1
			row.lastValue shouldBe 0.0
			row.authorityState shouldBe AchievementProgressEntity.AUTHORITY_STATE_UNVERIFIABLE
		}

		database.stepsGoalEffectDao().recordDecision(
			effect(MONDAY).copy(
				decisionState = StepsGoalEffectEntity.STATE_UNVERIFIABLE,
				unavailableReason = "LEGACY_AUTHORITY_UNAVAILABLE",
				qualifiedSteps = null,
				desiredPointsMicros = 0L,
				desiredXp = 0,
			),
		)
		database.stepsGoalEffectDao().recordDecision(effect(MONDAY + 1L))
		reconciler.reconcile(200L) shouldBe StepsGoalAchievementReconcileResult.Unverifiable(true)
		GOAL_METRICS.forEach { metric ->
			database.achievementProgressDao().getByMetric(metric.storageKey)?.authorityState shouldBe
				AchievementProgressEntity.AUTHORITY_STATE_UNVERIFIABLE
		}
	}

	@Test
	fun `qualified tier increase emits once while correction decrease does not`() = runTest {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(revision = 1L))
		database.stepsGoalEffectDao().recordDecision(effect(MONDAY))
		val events = RecordingDomainEvents()
		val reconciler = reconciler(testScheduler, events)
		reconciler.reconcile(100L) shouldBe StepsGoalAchievementReconcileResult.Applied(true)
		events.persisted shouldBe emptyList()

		database.sourceEvidenceStateDao().incrementRevision(150L) shouldBe 1
		database.stepsGoalEffectDao().recordDecision(effect(MONDAY + 1L, sourceRevision = 2L))
		database.stepsGoalEffectDao().recordDecision(effect(MONDAY + 2L, sourceRevision = 2L))
		reconciler.reconcile(200L) shouldBe StepsGoalAchievementReconcileResult.Applied(true)

		val unlock = events.persisted.single() as DomainEvent.AchievementUnlocked
		unlock.processorId shouldBe QUALIFIED_STEPS_ACHIEVEMENT_PROCESSOR_ID
		unlock.achievementId shouldBe "goal_streak_3"
		unlock.authorityRevision shouldBe 2L
		unlock.authorityDigest shouldBe requireNotNull(
			database.achievementProgressDao().getByMetric(MetricKey.GOAL_STREAK_DAYS.storageKey),
		).authorityDigest
		database.sourceEvidenceStateDao().incrementRevision(250L) shouldBe 1
		database.stepsGoalEffectDao().recordDecision(
			effect(MONDAY + 1L, complete = false, sourceRevision = 3L, digest = 'c'),
		)
		reconciler.reconcile(300L) shouldBe StepsGoalAchievementReconcileResult.Applied(true)
		events.persisted.size shouldBe 1

		database.sourceEvidenceStateDao().incrementRevision(350L) shouldBe 1
		database.stepsGoalEffectDao().recordDecision(
			effect(MONDAY + 1L, sourceRevision = 4L, digest = 'd'),
		)
		reconciler.reconcile(400L) shouldBe StepsGoalAchievementReconcileResult.Applied(true)
		events.persisted.size shouldBe 1

		(MONDAY + 3L..SUNDAY).forEach { day ->
			database.stepsGoalEffectDao().recordDecision(effect(day, sourceRevision = 4L, digest = 'd'))
		}
		reconciler.reconcile(500L) shouldBe StepsGoalAchievementReconcileResult.Applied(true)
		(events.persisted.last() as DomainEvent.AchievementUnlocked).achievementId shouldBe
			"goal_streak_7"
		events.persisted.size shouldBe 2
	}

	@Test
	fun `qualified row and exact unlock outbox commit atomically`() = runTest {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(revision = 1L))
		database.stepsGoalEffectDao().recordDecision(effect(MONDAY))
		val events = DefaultDomainEventRepository(database.domainEventDao())
		val reconciler = reconciler(testScheduler, events)
		reconciler.reconcile(100L) shouldBe StepsGoalAchievementReconcileResult.Applied(true)

		database.sourceEvidenceStateDao().incrementRevision(150L) shouldBe 1
		database.stepsGoalEffectDao().recordDecision(effect(MONDAY + 1L, sourceRevision = 2L))
		database.stepsGoalEffectDao().recordDecision(effect(MONDAY + 2L, sourceRevision = 2L))
		reconciler.reconcile(200L) shouldBe StepsGoalAchievementReconcileResult.Applied(true)

		val row = requireNotNull(
			database.achievementProgressDao().getByMetric(MetricKey.GOAL_STREAK_DAYS.storageKey),
		)
		val event = events.getUnconsumedBatchWithIds("test", 10).single().event as
			DomainEvent.AchievementUnlocked
		event.authorityRevision shouldBe row.authorityRevision
		event.authorityDigest shouldBe row.authorityDigest
		row.qualifiedNotificationClaimedTierIndex shouldBe 0
		row.lastUnlockedAt shouldBe 200L
	}

	@Test
	fun `failed unlock outbox write rolls back qualified tier advancement`() = runTest {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(revision = 1L))
		database.stepsGoalEffectDao().recordDecision(effect(MONDAY))
		val baseline = reconciler(testScheduler)
		baseline.reconcile(100L) shouldBe StepsGoalAchievementReconcileResult.Applied(true)

		database.sourceEvidenceStateDao().incrementRevision(150L) shouldBe 1
		database.stepsGoalEffectDao().recordDecision(effect(MONDAY + 1L, sourceRevision = 2L))
		database.stepsGoalEffectDao().recordDecision(effect(MONDAY + 2L, sourceRevision = 2L))
		reconciler(testScheduler, FailingDomainEvents).reconcile(200L) shouldBe
			StepsGoalAchievementReconcileResult.RetryableFailure

		val row = requireNotNull(
			database.achievementProgressDao().getByMetric(MetricKey.GOAL_STREAK_DAYS.storageKey),
		)
		row.lastTierIndex shouldBe -1
		row.qualifiedNotificationClaimedTierIndex shouldBe -1
		row.authorityRevision shouldBe 1L
	}

	private fun reconciler(
		testScheduler: TestCoroutineScheduler,
		domainEvents: DomainEventRepository = RecordingDomainEvents(),
	) = StepsGoalAchievementReconciler(
		database = database,
		dispatchers = TestDispatchersProvider(StandardTestDispatcher(testScheduler)),
		startupGate = ReadyGate,
		domainEvents = domainEvents,
	)

	private fun effect(
		day: Long,
		complete: Boolean = true,
		sourceRevision: Long = 1L,
		digest: Char = 'a',
	) = StepsGoalEffectEntity(
		effectIdentity = StepsGoalEffectEntity.identity(StepsGoalEffectEntity.PERIOD_DAY, day),
		periodKind = StepsGoalEffectEntity.PERIOD_DAY,
		periodStartEpochDay = day,
		periodEndEpochDay = day,
		qualifiedThroughEpochDay = day,
		calendarAuthority = "$day=Europe/Prague",
		targetSteps = 10_000L,
		weeklyDailyLimitBits = null,
		decisionState = if (complete) {
			StepsGoalEffectEntity.STATE_READY_COMPLETE
		} else {
			StepsGoalEffectEntity.STATE_READY_INCOMPLETE
		},
		unavailableReason = null,
		qualifiedSteps = if (complete) 12_000L else 8_000L,
		sourceAuthorityDigest = digest.toString().repeat(64),
		sourceEvidenceRevision = sourceRevision,
		effectRevision = 1L,
		completionPointsMicros = 100_000_000L,
		completionXp = 50,
		desiredPointsMicros = if (complete) 100_000_000L else 0L,
		desiredXp = if (complete) 50 else 0,
		firstCompletedAtMs = 100L.takeIf { complete },
		pointsAppliedRevision = 0L,
		xpAppliedRevision = 0L,
		notificationClaimedRevision = null,
		notificationClaimedAtMs = null,
		updatedAtMs = 100L,
	)

	private data object ReadyGate : TrackingStartupGate {
		override val isReady = true
		override val currentGeneration = 1L
		override suspend fun reconcile(retryFailedStorage: Boolean) =
			TrackingStartupResult.Ready(false, 0L)
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

	private companion object {
		val MONDAY = LocalDate.of(2026, 9, 7).toEpochDay()
		val SUNDAY = MONDAY + 6L
		val GOAL_METRICS = listOf(MetricKey.GOAL_STREAK_DAYS, MetricKey.PERFECT_WEEKS)
	}
}
