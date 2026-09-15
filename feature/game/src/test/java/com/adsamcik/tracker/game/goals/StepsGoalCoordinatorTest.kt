package com.adsamcik.tracker.game.goals

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.game.goals.settings.GoalsSettingsRepository
import com.adsamcik.tracker.game.goals.settings.GoalsSettingsState
import com.adsamcik.tracker.game.progression.PlayerProgressionRepository
import com.adsamcik.tracker.game.repository.stepsCalendarAuthority
import com.adsamcik.tracker.points.data.AwardSource
import com.adsamcik.tracker.points.database.PointsDatabase
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.StepsGoalEffectEntity
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.stats.api.metric.MetricDirtyTracker
import com.adsamcik.tracker.stats.api.repository.DomainEventRepository
import com.adsamcik.tracker.stats.api.repository.StepsNumericCalendarAuthority
import com.adsamcik.tracker.stats.api.repository.StepsNumericCalendarDay
import com.adsamcik.tracker.stats.api.repository.StepsNumericDay
import com.adsamcik.tracker.stats.api.repository.StepsNumericDecisionBatch
import com.adsamcik.tracker.stats.api.repository.StepsNumericDecisionRepository
import com.adsamcik.tracker.stats.api.repository.StepsNumericDecisionWindow
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummary
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummaryRequest
import com.adsamcik.tracker.testing.TestDispatchersProvider
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StepsGoalCoordinatorTest {
	private lateinit var context: Application
	private lateinit var appDatabase: AppDatabase
	private lateinit var pointsDatabase: PointsDatabase

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		appDatabase = AppDatabase.testDatabase(context)
		pointsDatabase = PointsDatabase.testDatabase(context)
	}

	@After
	fun tearDown() {
		pointsDatabase.close()
		appDatabase.close()
	}

	@Test
	fun `qualified source change records and settles the current daily goal`() = runTest {
		appDatabase.sourceEvidenceStateDao().ensure(SourceEvidenceState(revision = 1L))
		val settings = GoalsSettingsState.defaults().copy(notificationsEnabled = false)
		val settingsRepository = mockk<GoalsSettingsRepository>()
		every { settingsRepository.data } returns MutableStateFlow(settings)
		val dispatchers = TestDispatchersProvider(StandardTestDispatcher(testScheduler))
		val dirtyTracker = RecordingMetricDirtyTracker()
		val progression = PlayerProgressionRepository(
			appDatabase,
			dispatchers,
			dirtyTracker,
			ReadyGate,
		)
		val coordinator = StepsGoalCoordinator(
			database = appDatabase,
			source = ReadyDecisionRepository,
			settingsRepository = settingsRepository,
			decisionReconciler = StepsGoalDecisionReconciler(
				appDatabase,
				ReadyDecisionRepository,
				dispatchers,
				ReadyGate,
			),
			historicalReconciler = StepsGoalHistoricalReconciler(
				appDatabase,
				ReadyDecisionRepository,
				dispatchers,
				ReadyGate,
			),
			achievementReconciler = StepsGoalAchievementReconciler(
				appDatabase,
				dispatchers,
				ReadyGate,
				mockk<DomainEventRepository>(relaxed = true),
			),
			rewardProjector = StepsGoalRewardProjector(
				appDatabase,
				pointsDatabase,
				progression,
				dispatchers,
				ReadyGate,
			),
			notificationDispatcher = StepsGoalNotificationDispatcher(
				context,
				appDatabase,
				dispatchers,
				ReadyGate,
			),
		)
		backgroundScope.launch { coordinator.run() }

		advanceUntilIdle()

		val authority = stepsCalendarAuthority(Time.now, Locale.getDefault())
		val identity = StepsGoalEffectEntity.identity(
			StepsGoalEffectEntity.PERIOD_DAY,
			authority.today.toEpochDay(),
		)
		val effect = requireNotNull(appDatabase.stepsGoalEffectDao().get(identity))
		effect.decisionState shouldBe StepsGoalEffectEntity.STATE_READY_COMPLETE
		effect.pointsAppliedRevision shouldBe effect.effectRevision
		effect.xpAppliedRevision shouldBe effect.effectRevision
		effect.notificationClaimedRevision shouldBe effect.effectRevision
		pointsDatabase.pointsAwardedDao()
			.getRevisionedEffect(AwardSource.GOAL.value, identity)
			?.effectValueMicros shouldBe settings.dailyStepGoal.toLong() * 10_000L
		appDatabase.xpLedgerDao().getRevisionedEffect("GOAL", identity)?.amount shouldBe 50
		appDatabase.playerProfileDao().get()?.totalXp shouldBe 50L
	}

	private data object ReadyDecisionRepository : StepsNumericDecisionRepository {
		override suspend fun readDecisionBatch(
			requests: List<StepsNumericSummaryRequest>,
		): StepsNumericDecisionBatch = snapshot(requests)

		override suspend fun readExactDecisionBatch(
			requests: List<com.adsamcik.tracker.stats.api.repository.StepsNumericExactDecisionRequest>,
		): StepsNumericDecisionBatch = snapshot(requests.map { it.request })

		override fun observeDecisionBatch(
			requests: List<StepsNumericSummaryRequest>,
		): Flow<StepsNumericDecisionBatch> = flowOf(snapshot(requests))

		private fun snapshot(requests: List<StepsNumericSummaryRequest>) =
			StepsNumericDecisionBatch.Snapshot(
				sourceEvidenceRevision = 1L,
				windows = requests.map { request ->
					StepsNumericDecisionWindow(
						request = request,
						summary = StepsNumericSummary.Ready(
							(request.firstEpochDay..request.lastEpochDayInclusive).map { day ->
								StepsNumericDay(day, 5_000L)
							},
						),
						calendarAuthority = StepsNumericCalendarAuthority.Exact(
							(request.firstEpochDay..request.lastEpochDayInclusive).map { day ->
								StepsNumericCalendarDay(day, request.fallbackCalendarZoneId)
							},
						),
						sourceResultDigest = "a".repeat(64),
					)
				},
			)
	}

	private data object ReadyGate : TrackingStartupGate {
		override val isReady = true
		override val currentGeneration = 1L
		override suspend fun reconcile(retryFailedStorage: Boolean) =
			TrackingStartupResult.Ready(false, 0L)
	}

	private class RecordingMetricDirtyTracker : MetricDirtyTracker {
		override fun markDirty(table: String) = Unit
		override fun markDirty(tables: Set<String>) = Unit
		override suspend fun snapshotDirty(consumer: MetricDirtyTracker.Consumer) =
			MetricDirtyTracker.DirtySnapshot(emptyMap())

		override suspend fun acknowledgeDirty(
			consumer: MetricDirtyTracker.Consumer,
			snapshot: MetricDirtyTracker.DirtySnapshot,
		) = true
	}
}
