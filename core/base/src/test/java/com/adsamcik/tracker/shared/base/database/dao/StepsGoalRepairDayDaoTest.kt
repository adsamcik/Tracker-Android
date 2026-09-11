package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.enqueueStepsGoalRepairDay
import com.adsamcik.tracker.shared.base.database.data.AchievementProgressEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.StepsGoalEffectEntity
import com.adsamcik.tracker.shared.base.database.data.StepsGoalRepairDayEntity
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StepsGoalRepairDayDaoTest {
	private lateinit var database: AppDatabase
	private lateinit var dao: StepsGoalRepairDayDao

	@Before
	fun setUp() {
		database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext<Application>())
		dao = database.stepsGoalRepairDayDao()
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `same day collapses to newest source evidence revision`() = runTest {
		dao.enqueue(DAY, 4L)
		dao.enqueue(DAY, 3L)
		dao.enqueue(DAY, 7L)

		dao.next() shouldBe StepsGoalRepairDayEntity(DAY, 7L)
		dao.removeIfExact(DAY, 4L) shouldBe 0
		dao.removeIfExact(DAY, 7L) shouldBe 1
		dao.next() shouldBe null
	}

	@Test
	fun `oldest requested revision is observed first`() = runTest {
		dao.enqueue(DAY + 2L, 9L)
		dao.enqueue(DAY, 8L)
		dao.enqueue(DAY + 1L, 8L)

		dao.observeNext().first { it != null } shouldBe StepsGoalRepairDayEntity(DAY, 8L)
	}

	@Test
	fun `database helper queues only an existing decision made stale by newer source evidence`() =
		runTest {
			database.sourceEvidenceStateDao().ensure(SourceEvidenceState(revision = 1L))
			database.stepsGoalEffectDao().recordDecision(effect())
			listOf(
				AchievementProgressEntity.METRIC_GOAL_STREAK_DAYS,
				AchievementProgressEntity.METRIC_PERFECT_WEEKS,
			).forEach { metric ->
				database.achievementProgressDao().replaceQualified(
					metricKey = metric,
					lastTierIndex = 0,
					lastValue = 3.0,
					updatedAt = 100L,
					authorityKind = AchievementProgressEntity.AUTHORITY_QUALIFIED_STEPS_V1,
					authorityRevision = 1L,
					authorityDigest = "b".repeat(64),
					authorityState = AchievementProgressEntity.AUTHORITY_STATE_READY,
				)
			}

			database.withTransaction { database.enqueueStepsGoalRepairDay(DAY) }
			dao.next() shouldBe null

			database.withTransaction {
				database.sourceEvidenceStateDao().incrementRevision(200L) shouldBe 1
				database.enqueueStepsGoalRepairDay(DAY)
			}
			dao.next() shouldBe StepsGoalRepairDayEntity(DAY, 2L)
			listOf(
				AchievementProgressEntity.METRIC_GOAL_STREAK_DAYS,
				AchievementProgressEntity.METRIC_PERFECT_WEEKS,
			).forEach { metric ->
				val progress = requireNotNull(database.achievementProgressDao().getByMetric(metric))
				progress.authorityRevision shouldBe 2L
				progress.authorityState shouldBe
					AchievementProgressEntity.AUTHORITY_STATE_MATERIALIZING
			}
		}

	private fun effect() = StepsGoalEffectEntity(
		effectIdentity = StepsGoalEffectEntity.identity(StepsGoalEffectEntity.PERIOD_DAY, DAY),
		periodKind = StepsGoalEffectEntity.PERIOD_DAY,
		periodStartEpochDay = DAY,
		periodEndEpochDay = DAY,
		qualifiedThroughEpochDay = DAY,
		calendarAuthority = "$DAY=Europe/Prague",
		targetSteps = 10_000L,
		weeklyDailyLimitBits = null,
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

	private companion object {
		const val DAY = 20_000L
	}
}
