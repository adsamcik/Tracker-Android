package com.adsamcik.tracker.game.goals

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.game.progression.PlayerProgressionRepository
import com.adsamcik.tracker.points.data.AwardSource
import com.adsamcik.tracker.points.database.PointsDatabase
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.StepsGoalEffectWriteResult
import com.adsamcik.tracker.shared.base.database.data.StepsGoalEffectEntity
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.base.startup.TrackingStartupStage
import com.adsamcik.tracker.stats.api.metric.MetricDirtyTracker
import com.adsamcik.tracker.testing.TestDispatchersProvider
import io.kotest.matchers.shouldBe
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
class StepsGoalRewardProjectorTest {
	private lateinit var appDatabase: AppDatabase
	private lateinit var pointsDatabase: PointsDatabase

	@Before
	fun setUp() {
		val context = ApplicationProvider.getApplicationContext<Application>()
		appDatabase = AppDatabase.testDatabase(context)
		pointsDatabase = PointsDatabase.testDatabase(context)
	}

	@After
	fun tearDown() {
		pointsDatabase.close()
		appDatabase.close()
	}

	@Test
	fun `completion and correction settle both ledgers without stale resurrection`() = runTest {
		val dirtyTracker = RecordingMetricDirtyTracker()
		val projector = projector(ReadyGate, dirtyTracker, testScheduler)
		appDatabase.stepsGoalEffectDao().recordDecision(effect(complete = true)) shouldBe
			StepsGoalEffectWriteResult.INSERTED

		projector.project(IDENTITY, 1L) shouldBe StepsGoalRewardProjectionResult.Settled(
			points = StepsGoalRewardComponentResult.APPLIED,
			xp = StepsGoalRewardComponentResult.APPLIED,
		)
		appDatabase.stepsGoalEffectDao().get(IDENTITY)?.pointsAppliedRevision shouldBe 1L
		appDatabase.stepsGoalEffectDao().get(IDENTITY)?.xpAppliedRevision shouldBe 1L
		pointsDatabase.pointsAwardedDao()
			.getRevisionedEffect(AwardSource.GOAL.value, IDENTITY)
			?.effectValueMicros shouldBe 40_000_000L
		appDatabase.xpLedgerDao().getRevisionedEffect("GOAL", IDENTITY)?.amount shouldBe 50
		appDatabase.playerProfileDao().get()?.totalXp shouldBe 50L
		dirtyTracker.markCalls shouldBe 1

		appDatabase.stepsGoalEffectDao().recordDecision(
			effect(complete = false, sourceRevision = 2L, digest = 'b', updatedAt = 200L),
		) shouldBe StepsGoalEffectWriteResult.REVISED
		projector.project(IDENTITY, 2L) shouldBe StepsGoalRewardProjectionResult.Settled(
			points = StepsGoalRewardComponentResult.APPLIED,
			xp = StepsGoalRewardComponentResult.APPLIED,
		)
		pointsDatabase.pointsAwardedDao()
			.getRevisionedEffect(AwardSource.GOAL.value, IDENTITY)
			?.effectValueMicros shouldBe 0L
		appDatabase.xpLedgerDao().getRevisionedEffect("GOAL", IDENTITY)?.amount shouldBe 0
		appDatabase.playerProfileDao().get()?.totalXp shouldBe 0L
		dirtyTracker.markCalls shouldBe 2

		projector.project(IDENTITY, 1L) shouldBe StepsGoalRewardProjectionResult.Settled(
			points = StepsGoalRewardComponentResult.SUPERSEDED,
			xp = StepsGoalRewardComponentResult.SUPERSEDED,
		)
		pointsDatabase.pointsAwardedDao()
			.getRevisionedEffect(AwardSource.GOAL.value, IDENTITY)
			?.effectRevision shouldBe 2L
		appDatabase.xpLedgerDao().getRevisionedEffect("GOAL", IDENTITY)?.sourceRevision shouldBe 2L
	}

	@Test
	fun `closed generation leaves pending effects untouched`() = runTest {
		appDatabase.stepsGoalEffectDao().recordDecision(effect(complete = true)) shouldBe
			StepsGoalEffectWriteResult.INSERTED

		projector(ClosedGate, RecordingMetricDirtyTracker(), testScheduler)
			.project(IDENTITY, 1L) shouldBe
			StepsGoalRewardProjectionResult.RetryableFailure(
				StepsGoalRewardRetryableReason.STARTUP_GENERATION_CHANGED,
			)
		appDatabase.stepsGoalEffectDao().get(IDENTITY)?.pointsAppliedRevision shouldBe 0L
		appDatabase.stepsGoalEffectDao().get(IDENTITY)?.xpAppliedRevision shouldBe 0L
		pointsDatabase.pointsAwardedDao()
			.getRevisionedEffect(AwardSource.GOAL.value, IDENTITY) shouldBe null
		appDatabase.xpLedgerDao().getRevisionedEffect("GOAL", IDENTITY) shouldBe null
	}

	@Test
	fun `existing Points receipt repairs a crash gap without duplicate value`() = runTest {
		appDatabase.stepsGoalEffectDao().recordDecision(effect(complete = true)) shouldBe
			StepsGoalEffectWriteResult.INSERTED
		pointsDatabase.pointsAwardedDao().applyGoalEffect(
			effectKey = IDENTITY,
			effectRevision = 1L,
			time = 100L,
			valueMicros = 40_000_000L,
		) shouldBe true

		projector(ReadyGate, RecordingMetricDirtyTracker(), testScheduler)
			.project(IDENTITY, 1L) shouldBe StepsGoalRewardProjectionResult.Settled(
			points = StepsGoalRewardComponentResult.APPLIED,
			xp = StepsGoalRewardComponentResult.APPLIED,
		)
		pointsDatabase.pointsAwardedDao()
			.countBetween(Long.MIN_VALUE, Long.MAX_VALUE) shouldBe 40.0
		appDatabase.stepsGoalEffectDao().get(IDENTITY)?.pointsAppliedRevision shouldBe 1L
	}

	private fun projector(
		gate: TrackingStartupGate,
		dirtyTracker: MetricDirtyTracker,
		testScheduler: TestCoroutineScheduler,
	): StepsGoalRewardProjector {
		val dispatchers = TestDispatchersProvider(StandardTestDispatcher(testScheduler))
		val progression = PlayerProgressionRepository(
			database = appDatabase,
			dispatchers = dispatchers,
			metricDirtyTracker = dirtyTracker,
			trackingStartupGate = gate,
		)
		return StepsGoalRewardProjector(
			database = appDatabase,
			pointsDatabase = pointsDatabase,
			progressionRepository = progression,
			dispatchers = dispatchers,
			startupGate = gate,
		)
	}

	private fun effect(
		complete: Boolean,
		sourceRevision: Long = 1L,
		digest: Char = 'a',
		updatedAt: Long = 100L,
	) = StepsGoalEffectEntity(
		effectIdentity = IDENTITY,
		periodKind = StepsGoalEffectEntity.PERIOD_DAY,
		periodStartEpochDay = EPOCH_DAY,
		periodEndEpochDay = EPOCH_DAY,
		qualifiedThroughEpochDay = EPOCH_DAY,
		calendarAuthority = "$EPOCH_DAY=Europe/Prague",
		targetSteps = 4_000L,
		weeklyDailyLimitBits = null,
		decisionState = if (complete) {
			StepsGoalEffectEntity.STATE_READY_COMPLETE
		} else {
			StepsGoalEffectEntity.STATE_READY_INCOMPLETE
		},
		unavailableReason = null,
		qualifiedSteps = if (complete) 4_500L else 3_500L,
		sourceAuthorityDigest = digest.toString().repeat(64),
		sourceEvidenceRevision = sourceRevision,
		effectRevision = 1L,
		desiredPointsMicros = if (complete) 40_000_000L else 0L,
		desiredXp = if (complete) 50 else 0,
		firstCompletedAtMs = updatedAt.takeIf { complete },
		pointsAppliedRevision = 0L,
		xpAppliedRevision = 0L,
		notificationClaimedRevision = null,
		notificationClaimedAtMs = null,
		updatedAtMs = updatedAt,
	)

	private data object ReadyGate : TrackingStartupGate {
		override val isReady = true
		override val currentGeneration = 1L
		override suspend fun reconcile(retryFailedStorage: Boolean) =
			TrackingStartupResult.Ready(false, 0L)
	}

	private data object ClosedGate : TrackingStartupGate {
		override val isReady = false
		override val currentGeneration = 2L
		override suspend fun reconcile(retryFailedStorage: Boolean) = TrackingStartupResult.Blocked(
			TrackingStartupStage.STORAGE,
			"TEST_CLOSED",
		)
	}

	private class RecordingMetricDirtyTracker : MetricDirtyTracker {
		var markCalls = 0
			private set

		override fun markDirty(table: String) {
			markCalls += 1
		}

		override fun markDirty(tables: Set<String>) {
			if (tables.isNotEmpty()) markCalls += 1
		}

		override suspend fun snapshotDirty(consumer: MetricDirtyTracker.Consumer) =
			MetricDirtyTracker.DirtySnapshot(emptyMap())

		override suspend fun acknowledgeDirty(
			consumer: MetricDirtyTracker.Consumer,
			snapshot: MetricDirtyTracker.DirtySnapshot,
		) = true
	}

	private companion object {
		const val EPOCH_DAY = 20_000L
		val IDENTITY: String = StepsGoalEffectEntity.identity(
			StepsGoalEffectEntity.PERIOD_DAY,
			EPOCH_DAY,
		)
	}
}
