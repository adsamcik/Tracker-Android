package com.adsamcik.tracker.game.goals

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.StepsGoalEffectWriteResult
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.StepsGoalEffectEntity
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.stats.api.repository.StepsNumericCalendarAuthority
import com.adsamcik.tracker.stats.api.repository.StepsNumericDay
import com.adsamcik.tracker.stats.api.repository.StepsNumericDecisionBatch
import com.adsamcik.tracker.stats.api.repository.StepsNumericDecisionRepository
import com.adsamcik.tracker.stats.api.repository.StepsNumericDecisionWindow
import com.adsamcik.tracker.stats.api.repository.StepsNumericExactDecisionRequest
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummary
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummaryRequest
import com.adsamcik.tracker.testing.TestDispatchersProvider
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StepsGoalHistoricalReconcilerTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext<Application>())
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `old daily correction retracts rewards under stored calendar authority`() = runTest {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(revision = 2L))
		database.stepsGoalEffectDao().recordDecision(effect()) shouldBe StepsGoalEffectWriteResult.INSERTED
		database.stepsGoalRepairDayDao().enqueue(DAY, 2L)
		val source = FakeSource(2L) { request ->
			StepsNumericSummary.Ready(listOf(StepsNumericDay(request.request.firstEpochDay, 4_000L)))
		}

		reconciler(source, StandardTestDispatcher(testScheduler)).reconcileNext(200L) shouldBe
			StepsGoalHistoricalReconcileResult.Applied(DAY, 1)

		val repaired = requireNotNull(database.stepsGoalEffectDao().get(IDENTITY))
		repaired.effectRevision shouldBe 2L
		repaired.decisionState shouldBe StepsGoalEffectEntity.STATE_READY_INCOMPLETE
		repaired.desiredPointsMicros shouldBe 0L
		repaired.desiredXp shouldBe 0
		repaired.completionPointsMicros shouldBe 100_000_000L
		repaired.completionXp shouldBe 50
		repaired.firstCompletedAtMs shouldBe 100L
		database.stepsGoalRepairDayDao().next() shouldBe null
		source.requests.single().calendarAuthority.canonical shouldBe "$DAY=Europe/Prague"
	}

	@Test
	fun `materializing history keeps exact repair request pending`() = runTest {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(revision = 2L))
		database.stepsGoalEffectDao().recordDecision(effect())
		database.stepsGoalRepairDayDao().enqueue(DAY, 2L)

		reconciler(
			FakeSource(2L) { StepsNumericSummary.Materializing },
			StandardTestDispatcher(testScheduler),
		)
			.reconcileNext(200L) shouldBe StepsGoalHistoricalReconcileResult.RetryableFailure(
			StepsGoalHistoricalRetryableReason.MATERIALIZING,
		)

		database.stepsGoalRepairDayDao().next()?.epochDay shouldBe DAY
		database.stepsGoalEffectDao().get(IDENTITY)?.effectRevision shouldBe 1L
	}

	@Test
	fun `repair cannot acknowledge a source snapshot older than queued mutation`() = runTest {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(revision = 3L))
		database.stepsGoalEffectDao().recordDecision(effect())
		database.stepsGoalRepairDayDao().enqueue(DAY, 3L)

		reconciler(
			FakeSource(2L) { StepsNumericSummary.Ready(listOf(StepsNumericDay(DAY, 0L))) },
			StandardTestDispatcher(testScheduler),
		)
			.reconcileNext(200L) shouldBe StepsGoalHistoricalReconcileResult.RetryableFailure(
			StepsGoalHistoricalRetryableReason.SOURCE_CHANGED,
		)

		database.stepsGoalRepairDayDao().next()?.sourceEvidenceRevision shouldBe 3L
		database.stepsGoalEffectDao().get(IDENTITY)?.effectRevision shouldBe 1L
	}

	@Test
	fun `day after recorded weekly cutoff does not rewrite the unread week`() = runTest {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(revision = 2L))
		val week = effect(
			periodKind = StepsGoalEffectEntity.PERIOD_WEEK,
			periodStartEpochDay = DAY - 2L,
			qualifiedThroughEpochDay = DAY - 1L,
		)
		database.stepsGoalEffectDao().recordDecision(week)
		database.stepsGoalRepairDayDao().enqueue(DAY, 2L)
		val source = FakeSource(2L) { error("No exact read is authorized beyond the cutoff") }

		reconciler(source, StandardTestDispatcher(testScheduler)).reconcileNext(200L) shouldBe
			StepsGoalHistoricalReconcileResult.Applied(DAY, 0)

		source.requests shouldBe emptyList()
		database.stepsGoalEffectDao().get(week.effectIdentity) shouldBe week
		database.stepsGoalRepairDayDao().next() shouldBe null
	}

	@Test
	fun `overlapping historical weeks are repaired in bounded pages`() = runTest {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(revision = 2L))
		val weeks = (0L..2L).map { offset ->
			effect(
				periodKind = StepsGoalEffectEntity.PERIOD_WEEK,
				periodStartEpochDay = DAY - offset,
				qualifiedThroughEpochDay = DAY,
			)
		}.sortedBy { it.periodStartEpochDay }
		weeks.forEach { database.stepsGoalEffectDao().recordDecision(it) }
		database.stepsGoalRepairDayDao().enqueue(DAY, 2L)
		val source = FakeSource(2L) { request ->
			StepsNumericSummary.Ready(
				(request.request.firstEpochDay..request.request.lastEpochDayInclusive).map { day ->
					StepsNumericDay(day, 1_000L)
				},
			)
		}
		val reconciler = reconciler(source, StandardTestDispatcher(testScheduler))

		reconciler.reconcileNext(200L) shouldBe StepsGoalHistoricalReconcileResult.Applied(DAY, 2)
		database.stepsGoalRepairDayDao().next()?.epochDay shouldBe DAY
		reconciler.reconcileNext(300L) shouldBe StepsGoalHistoricalReconcileResult.Applied(DAY, 1)

		source.batchSizes shouldBe listOf(2, 1)
		weeks.forEach { original ->
			database.stepsGoalEffectDao().get(original.effectIdentity)?.sourceEvidenceRevision shouldBe 2L
		}
		database.stepsGoalRepairDayDao().next() shouldBe null
	}

	private fun reconciler(
		source: StepsNumericDecisionRepository,
		dispatcher: TestDispatcher,
	) = StepsGoalHistoricalReconciler(
		database = database,
		source = source,
		dispatchers = TestDispatchersProvider(dispatcher),
		startupGate = ReadyGate,
	)

	private fun effect(
		periodKind: String = StepsGoalEffectEntity.PERIOD_DAY,
		periodStartEpochDay: Long = DAY,
		qualifiedThroughEpochDay: Long = periodStartEpochDay,
	) = StepsGoalEffectEntity(
		effectIdentity = StepsGoalEffectEntity.identity(periodKind, periodStartEpochDay),
		periodKind = periodKind,
		periodStartEpochDay = periodStartEpochDay,
		periodEndEpochDay = periodStartEpochDay +
			if (periodKind == StepsGoalEffectEntity.PERIOD_DAY) 0L else 6L,
		qualifiedThroughEpochDay = qualifiedThroughEpochDay,
		calendarAuthority = (periodStartEpochDay..qualifiedThroughEpochDay)
			.joinToString("\n") { day -> "$day=Europe/Prague" },
		targetSteps = 10_000L,
		weeklyDailyLimitBits = if (periodKind == StepsGoalEffectEntity.PERIOD_WEEK) {
			0.25f.toBits()
		} else {
			null
		},
		decisionState = StepsGoalEffectEntity.STATE_READY_COMPLETE,
		unavailableReason = null,
		qualifiedSteps = 12_000L,
		sourceAuthorityDigest = "a".repeat(64),
		sourceEvidenceRevision = 1L,
		effectRevision = 1L,
		completionPointsMicros = 100_000_000L,
		completionXp = 50,
		desiredPointsMicros = 100_000_000L,
		desiredXp = 50,
		firstCompletedAtMs = 100L,
		pointsAppliedRevision = 0L,
		xpAppliedRevision = 0L,
		notificationClaimedRevision = null,
		notificationClaimedAtMs = null,
		updatedAtMs = 100L,
	)

	private class FakeSource(
		private val revision: Long,
		private val summary: (StepsNumericExactDecisionRequest) -> StepsNumericSummary,
	) : StepsNumericDecisionRepository {
		val requests = mutableListOf<StepsNumericExactDecisionRequest>()
		val batchSizes = mutableListOf<Int>()

		override suspend fun readDecisionBatch(
			requests: List<StepsNumericSummaryRequest>,
		): StepsNumericDecisionBatch = error("Current-period read is not expected")

		override suspend fun readExactDecisionBatch(
			requests: List<StepsNumericExactDecisionRequest>,
		): StepsNumericDecisionBatch {
			batchSizes += requests.size
			this.requests += requests
			return StepsNumericDecisionBatch.Snapshot(
				revision,
				requests.map { exact ->
					StepsNumericDecisionWindow(
						request = exact.request,
						summary = summary(exact),
						calendarAuthority = exact.calendarAuthority,
						sourceResultDigest = "b".repeat(64),
					)
				},
			)
		}

		override fun observeDecisionBatch(
			requests: List<StepsNumericSummaryRequest>,
		): Flow<StepsNumericDecisionBatch> = emptyFlow()
	}

	private data object ReadyGate : TrackingStartupGate {
		override val isReady = true
		override val currentGeneration = 1L
		override suspend fun reconcile(retryFailedStorage: Boolean) =
			TrackingStartupResult.Ready(false, 0L)
	}

	private companion object {
		const val DAY = 20_000L
		val IDENTITY = StepsGoalEffectEntity.identity(StepsGoalEffectEntity.PERIOD_DAY, DAY)
	}
}
