package com.adsamcik.tracker.game.goals

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.game.goals.settings.GoalsSettingsState
import com.adsamcik.tracker.game.repository.StepsCalendarAuthority
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.StepsGoalEffectEntity
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.base.startup.TrackingStartupStage
import com.adsamcik.tracker.stats.api.repository.StepsNumericCalendarAuthority
import com.adsamcik.tracker.stats.api.repository.StepsNumericCalendarDay
import com.adsamcik.tracker.stats.api.repository.StepsNumericDay
import com.adsamcik.tracker.stats.api.repository.StepsNumericDecisionBatch
import com.adsamcik.tracker.stats.api.repository.StepsNumericDecisionRepository
import com.adsamcik.tracker.stats.api.repository.StepsNumericDecisionWindow
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummary
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummaryRequest
import com.adsamcik.tracker.stats.api.repository.StepsNumericUnverifiableReason
import com.adsamcik.tracker.testing.TestDispatchersProvider
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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
class StepsGoalDecisionReconcilerTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext<Application>())
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `qualified daily completion and capped weekly progress become durable desired effects`() = runTest {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(revision = 4L))
		val source = FakeDecisionRepository(
			snapshot(
				revision = 4L,
				daily = StepsNumericSummary.Ready(listOf(StepsNumericDay(TODAY, 12_000L))),
				weekly = StepsNumericSummary.Ready(
					listOf(
						StepsNumericDay(WEEK_START, 12_000L),
						StepsNumericDay(WEEK_START + 1L, 5_000L),
						StepsNumericDay(TODAY, 9_000L),
					),
				),
			),
		)
		val reconciler = reconciler(source, StandardTestDispatcher(testScheduler))

		reconciler.reconcile(AUTHORITY, SETTINGS, 100L) shouldBe
			StepsGoalDecisionReconcileResult.Applied(
				StepsGoalPeriodDecisionWriteResult.INSERTED,
				StepsGoalPeriodDecisionWriteResult.INSERTED,
			)
		val daily = requireNotNull(database.stepsGoalEffectDao().get(dailyIdentity()))
		daily.decisionState shouldBe StepsGoalEffectEntity.STATE_READY_COMPLETE
		daily.qualifiedSteps shouldBe 12_000L
		daily.desiredPointsMicros shouldBe 100_000_000L
		daily.desiredXp shouldBe 50
		daily.firstCompletedAtMs shouldBe 100L
		daily.sourceEvidenceRevision shouldBe 4L

		val weekly = requireNotNull(database.stepsGoalEffectDao().get(weeklyIdentity()))
		weekly.decisionState shouldBe StepsGoalEffectEntity.STATE_READY_INCOMPLETE
		weekly.qualifiedSteps shouldBe 17_000L
		weekly.desiredPointsMicros shouldBe 0L
		weekly.desiredXp shouldBe 0
		weekly.firstCompletedAtMs shouldBe null
		weekly.periodEndEpochDay shouldBe WEEK_START + 6L
		weekly.weeklyDailyLimitBits shouldBe 0.3f.toBits()
	}

	@Test
	fun `source correction retracts desired effects but preserves first completion time`() = runTest {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(revision = 4L))
		val source = FakeDecisionRepository(
			snapshot(
				4L,
				StepsNumericSummary.Ready(listOf(StepsNumericDay(TODAY, 12_000L))),
				readyWeek(12_000L),
			),
		)
		val reconciler = reconciler(source, StandardTestDispatcher(testScheduler))
		reconciler.reconcile(AUTHORITY, SETTINGS, 100L)
		database.sourceEvidenceStateDao().incrementRevision(150L) shouldBe 1
		source.result = snapshot(
			5L,
			StepsNumericSummary.Ready(listOf(StepsNumericDay(TODAY, 8_000L))),
			readyWeek(8_000L),
			digest = 'b',
		)

		reconciler.reconcile(AUTHORITY, SETTINGS, 200L) shouldBe
			StepsGoalDecisionReconcileResult.Applied(
				StepsGoalPeriodDecisionWriteResult.REVISED,
				StepsGoalPeriodDecisionWriteResult.REVISED,
			)
		val daily = requireNotNull(database.stepsGoalEffectDao().get(dailyIdentity()))
		daily.effectRevision shouldBe 2L
		daily.decisionState shouldBe StepsGoalEffectEntity.STATE_READY_INCOMPLETE
		daily.desiredPointsMicros shouldBe 0L
		daily.desiredXp shouldBe 0
		daily.firstCompletedAtMs shouldBe 100L
	}

	@Test
	fun `materializing period is deferred and stale snapshot writes nothing`() = runTest {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(revision = 5L))
		val source = FakeDecisionRepository(
			snapshot(
				4L,
				StepsNumericSummary.Materializing,
				readyWeek(8_000L),
			),
		)
		val reconciler = reconciler(source, StandardTestDispatcher(testScheduler))

		reconciler.reconcile(AUTHORITY, SETTINGS, 100L) shouldBe
			StepsGoalDecisionReconcileResult.RetryableFailure(
				StepsGoalDecisionRetryableReason.SOURCE_CHANGED,
			)
		database.stepsGoalEffectDao().countAll() shouldBe 0L

		source.result = snapshot(
			5L,
			StepsNumericSummary.Materializing,
			readyWeek(8_000L),
		)
		reconciler.reconcile(AUTHORITY, SETTINGS, 200L) shouldBe
			StepsGoalDecisionReconcileResult.Applied(
				StepsGoalPeriodDecisionWriteResult.DEFERRED_MATERIALIZING,
				StepsGoalPeriodDecisionWriteResult.INSERTED,
			)
		database.stepsGoalEffectDao().get(dailyIdentity()) shouldBe null
		database.stepsGoalEffectDao().get(weeklyIdentity())?.qualifiedSteps shouldBe 8_000L
	}

	@Test
	fun `unverifiable source is retained as typed absence and never fabricated zero`() = runTest {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(revision = 4L))
		val partial = StepsNumericSummary.Unverifiable(
			StepsNumericUnverifiableReason.PARTIAL_CAPTURE,
		)
		val reconciler = reconciler(
			FakeDecisionRepository(snapshot(4L, partial, partial)),
			StandardTestDispatcher(testScheduler),
		)

		reconciler.reconcile(AUTHORITY, SETTINGS, 100L) shouldBe
			StepsGoalDecisionReconcileResult.Applied(
				StepsGoalPeriodDecisionWriteResult.INSERTED,
				StepsGoalPeriodDecisionWriteResult.INSERTED,
			)
		listOf(dailyIdentity(), weeklyIdentity()).forEach { identity ->
			val effect = requireNotNull(database.stepsGoalEffectDao().get(identity))
			effect.decisionState shouldBe StepsGoalEffectEntity.STATE_UNVERIFIABLE
			effect.unavailableReason shouldBe StepsNumericUnverifiableReason.PARTIAL_CAPTURE.name
			effect.qualifiedSteps shouldBe null
			effect.desiredPointsMicros shouldBe 0L
			effect.desiredXp shouldBe 0
			effect.firstCompletedAtMs shouldBe null
		}
	}

	@Test
	fun `closed startup generation cannot race full deletion with an effect write`() = runTest {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(revision = 4L))
		val reconciler = reconciler(
			FakeDecisionRepository(snapshot(4L, readyDay(12_000L), readyWeek(12_000L))),
			StandardTestDispatcher(testScheduler),
			ClosedGate,
		)

		reconciler.reconcile(AUTHORITY, SETTINGS, 100L) shouldBe
			StepsGoalDecisionReconcileResult.RetryableFailure(
				StepsGoalDecisionRetryableReason.STARTUP_GENERATION_CHANGED,
			)
		database.stepsGoalEffectDao().countAll() shouldBe 0L
	}

	private fun reconciler(
		source: StepsNumericDecisionRepository,
		dispatcher: TestDispatcher,
		gate: TrackingStartupGate = ReadyGate,
	) = StepsGoalDecisionReconciler(
		database = database,
		source = source,
		dispatchers = TestDispatchersProvider(dispatcher),
		startupGate = gate,
	)

	private fun snapshot(
		revision: Long,
		daily: StepsNumericSummary,
		weekly: StepsNumericSummary,
		digest: Char = 'a',
	): StepsNumericDecisionBatch.Snapshot {
		val dailyRequest = StepsNumericSummaryRequest(TODAY, TODAY, ZONE)
		val weeklyRequest = StepsNumericSummaryRequest(WEEK_START, TODAY, ZONE)
		return StepsNumericDecisionBatch.Snapshot(
			revision,
			listOf(
				window(dailyRequest, daily, digest),
				window(weeklyRequest, weekly, digest),
			),
		)
	}

	private fun window(
		request: StepsNumericSummaryRequest,
		summary: StepsNumericSummary,
		digest: Char,
	) = StepsNumericDecisionWindow(
		request = request,
		summary = summary,
		calendarAuthority = StepsNumericCalendarAuthority.Exact(
			(request.firstEpochDay..request.lastEpochDayInclusive).map { day ->
				StepsNumericCalendarDay(day, ZONE)
			},
		),
		sourceResultDigest = digest.toString().repeat(64),
	)

	private fun readyWeek(stepsToday: Long) = StepsNumericSummary.Ready(
		listOf(
			StepsNumericDay(WEEK_START, 0L),
			StepsNumericDay(WEEK_START + 1L, 0L),
			StepsNumericDay(TODAY, stepsToday),
		),
	)

	private fun readyDay(steps: Long) = StepsNumericSummary.Ready(
		listOf(StepsNumericDay(TODAY, steps)),
	)

	private class FakeDecisionRepository(
		var result: StepsNumericDecisionBatch,
	) : StepsNumericDecisionRepository {
		override suspend fun readDecisionBatch(
			requests: List<StepsNumericSummaryRequest>,
		): StepsNumericDecisionBatch = result

		override fun observeDecisionBatch(
			requests: List<StepsNumericSummaryRequest>,
		): Flow<StepsNumericDecisionBatch> = flowOf(result)
	}

	private data object ReadyGate : TrackingStartupGate {
		override val isReady: Boolean = true
		override val currentGeneration: Long = 1L
		override suspend fun reconcile(retryFailedStorage: Boolean): TrackingStartupResult =
			TrackingStartupResult.Ready(false, 0L)
	}

	private data object ClosedGate : TrackingStartupGate {
		override val isReady: Boolean = false
		override val currentGeneration: Long = 2L
		override suspend fun reconcile(retryFailedStorage: Boolean): TrackingStartupResult =
			TrackingStartupResult.Blocked(
				TrackingStartupStage.STORAGE,
				"TEST_CLOSED",
			)
	}

	private companion object {
		const val ZONE = "Europe/Prague"
		val WEEK_START: Long = LocalDate.of(2026, 8, 31).toEpochDay()
		val TODAY: Long = LocalDate.of(2026, 9, 2).toEpochDay()
		val AUTHORITY = StepsCalendarAuthority(
			today = LocalDate.ofEpochDay(TODAY),
			startOfWeek = LocalDate.ofEpochDay(WEEK_START),
			zoneId = ZONE,
		)
		val SETTINGS = GoalsSettingsState.defaults().copy(
			dailyStepGoal = 10_000,
			weeklyStepGoal = 20_000,
			weeklyProgressDailyLimit = 0.3f,
		)
		fun dailyIdentity(): String = StepsGoalEffectEntity.identity(
			StepsGoalEffectEntity.PERIOD_DAY,
			TODAY,
		)
		fun weeklyIdentity(): String = StepsGoalEffectEntity.identity(
			StepsGoalEffectEntity.PERIOD_WEEK,
			WEEK_START,
		)
	}
}
