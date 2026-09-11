package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.StepsGoalEffectEntity
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
class StepsGoalEffectDaoTest {
	private lateinit var database: AppDatabase
	private lateinit var dao: StepsGoalEffectDao

	@Before
	fun setUp() {
		database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext<Application>())
		dao = database.stepsGoalEffectDao()
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `identical evidence refresh does not rearm settled effects`() = runTest {
		dao.recordDecision(complete()) shouldBe StepsGoalEffectWriteResult.INSERTED
		dao.markPointsApplied(IDENTITY, 1L) shouldBe 1
		dao.markXpApplied(IDENTITY, 1L) shouldBe 1
		dao.recordDecision(
			complete().copy(sourceEvidenceRevision = 5L, updatedAtMs = 200L),
		) shouldBe StepsGoalEffectWriteResult.UNCHANGED

		dao.get(IDENTITY) shouldBe complete().copy(
			sourceEvidenceRevision = 5L,
			pointsAppliedRevision = 1L,
			xpAppliedRevision = 1L,
			updatedAtMs = 200L,
		)
		dao.observePending().first() shouldBe emptyList()
		dao.observeActionable().first() shouldBe listOf(requireNotNull(dao.get(IDENTITY)))
		dao.claimNotification(IDENTITY, 1L, 201L) shouldBe 1
		dao.observeActionable().first() shouldBe emptyList()
	}

	@Test
	fun `correction revises desired state and exact settlement fences stale projectors`() = runTest {
		dao.recordDecision(complete()) shouldBe StepsGoalEffectWriteResult.INSERTED
		dao.markPointsApplied(IDENTITY, 1L) shouldBe 1
		dao.markXpApplied(IDENTITY, 1L) shouldBe 1
		dao.claimNotification(IDENTITY, 1L, -1L) shouldBe 0
		dao.claimNotification(IDENTITY, 1L, 150L) shouldBe 1
		dao.claimNotification(IDENTITY, 1L, 151L) shouldBe 0

		val corrected = incomplete(digest = 'b', evidenceRevision = 5L, updatedAtMs = 200L)
		dao.recordDecision(corrected) shouldBe StepsGoalEffectWriteResult.REVISED
		val revisionTwo = requireNotNull(dao.get(IDENTITY))
		revisionTwo.effectRevision shouldBe 2L
		revisionTwo.pointsAppliedRevision shouldBe 1L
		revisionTwo.xpAppliedRevision shouldBe 1L
		revisionTwo.notificationClaimedRevision shouldBe 1L
		revisionTwo.notificationClaimedAtMs shouldBe 150L
		revisionTwo.firstCompletedAtMs shouldBe 100L
		revisionTwo.desiredPointsMicros shouldBe 0L
		revisionTwo.desiredXp shouldBe 0
		dao.observePending().first() shouldBe listOf(revisionTwo)

		dao.markPointsApplied(IDENTITY, 1L) shouldBe 0
		dao.markXpApplied(IDENTITY, 1L) shouldBe 0
		dao.claimNotification(IDENTITY, 2L, 250L) shouldBe 0
		dao.markPointsApplied(IDENTITY, 2L) shouldBe 1
		dao.markXpApplied(IDENTITY, 2L) shouldBe 1
		dao.observePending().first() shouldBe emptyList()

		dao.recordDecision(
			complete(digest = 'c', evidenceRevision = 6L, updatedAtMs = 300L),
		) shouldBe StepsGoalEffectWriteResult.REVISED
		dao.claimNotification(IDENTITY, 3L, 350L) shouldBe 0
	}

	@Test
	fun `older source evidence cannot replace or refresh newer authority`() = runTest {
		dao.recordDecision(complete(evidenceRevision = 5L, updatedAtMs = 200L)) shouldBe
			StepsGoalEffectWriteResult.INSERTED
		dao.recordDecision(
			incomplete(digest = 'b', evidenceRevision = 4L, updatedAtMs = 300L),
		) shouldBe StepsGoalEffectWriteResult.STALE
		dao.recordDecision(
			complete(evidenceRevision = 4L, updatedAtMs = 300L),
		) shouldBe StepsGoalEffectWriteResult.STALE

		dao.get(IDENTITY) shouldBe complete(evidenceRevision = 5L, updatedAtMs = 200L)
	}

	@Test
	fun `policy correction can revise against the same source snapshot`() = runTest {
		dao.recordDecision(complete()) shouldBe StepsGoalEffectWriteResult.INSERTED
		val policyCorrection = complete().copy(
			targetSteps = 11_000L,
			updatedAtMs = 200L,
		)

		dao.recordDecision(policyCorrection) shouldBe StepsGoalEffectWriteResult.REVISED
		dao.get(IDENTITY) shouldBe policyCorrection.copy(effectRevision = 2L)
	}

	@Test
	fun `full collected data clear removes dormant goal effects`() = runTest {
		dao.recordDecision(complete()) shouldBe StepsGoalEffectWriteResult.INSERTED
		dao.countAll() shouldBe 1L
		AppDatabase.deleteAllCollectedData(database, 2L, null, 500L)
		dao.countAll() shouldBe 0L
	}

	private fun complete(
		digest: Char = 'a',
		evidenceRevision: Long = 4L,
		updatedAtMs: Long = 100L,
	) = StepsGoalEffectEntity(
		effectIdentity = IDENTITY,
		periodKind = StepsGoalEffectEntity.PERIOD_DAY,
		periodStartEpochDay = START_DAY,
		periodEndEpochDay = START_DAY,
		qualifiedThroughEpochDay = START_DAY,
		calendarAuthority = "$START_DAY=Europe/Prague",
		targetSteps = 10_000L,
		weeklyDailyLimitBits = null,
		decisionState = StepsGoalEffectEntity.STATE_READY_COMPLETE,
		unavailableReason = null,
		qualifiedSteps = 12_000L,
		sourceAuthorityDigest = digest.toString().repeat(64),
		sourceEvidenceRevision = evidenceRevision,
		effectRevision = 1L,
		completionPointsMicros = 100_000_000L,
		completionXp = 25,
		desiredPointsMicros = 100_000_000L,
		desiredXp = 25,
		firstCompletedAtMs = 100L,
		pointsAppliedRevision = 0L,
		xpAppliedRevision = 0L,
		notificationClaimedRevision = null,
		notificationClaimedAtMs = null,
		updatedAtMs = updatedAtMs,
	)

	private fun incomplete(
		digest: Char,
		evidenceRevision: Long,
		updatedAtMs: Long,
	) = complete(digest, evidenceRevision, updatedAtMs).copy(
		decisionState = StepsGoalEffectEntity.STATE_READY_INCOMPLETE,
		qualifiedSteps = 8_000L,
		desiredPointsMicros = 0L,
		desiredXp = 0,
	)

	private companion object {
		const val START_DAY = 20_000L
		val IDENTITY = StepsGoalEffectEntity.identity(StepsGoalEffectEntity.PERIOD_DAY, START_DAY)
	}
}
