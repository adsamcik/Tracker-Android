package com.adsamcik.tracker.points.database

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.points.data.AwardSource
import com.adsamcik.tracker.points.data.Points
import com.adsamcik.tracker.points.data.PointsAwarded
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PointsAwardedDaoTest {

	private lateinit var database: PointsDatabase
	private lateinit var dao: PointsAwardedDao

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = PointsDatabase.testDatabase(context)
		dao = database.pointsAwardedDao()
	}

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun countBetweenReturnsZeroWhenNoRows() {
        val now = 1_000L
        val result = dao.countBetween(0L, now)
        result shouldBe 0.0
    }

    @Test
    fun countBetweenFlowEmitsZeroAndUpdatesAfterInsert(): Unit = runBlocking {
        val now = 10_000L
        val flow = dao.countBetweenFlow(0L, now)

        val initial = flow.first()
        initial shouldBe 0.0

        dao.insert(
            PointsAwarded(
                time = now - 1_000L,
                value = Points(42.0),
                source = AwardSource.GOAL
            )
        )

        val updated = flow.first()
        updated shouldBe 42.0
    }

    @Test
    fun deleteAllRemovesAwardHistory(): Unit = runBlocking {
        dao.insert(
            PointsAwarded(
                time = 1_000L,
                value = Points(42.0),
                source = AwardSource.GOAL,
            ),
        )

        dao.deleteAll()

        dao.countBetween(Long.MIN_VALUE, Long.MAX_VALUE) shouldBe 0.0
    }

    @Test
    fun applyGoalEffectPersistsExactIdentityRevisionAndMicros(): Unit = runBlocking {
        dao.applyGoalEffect(
            effectKey = "steps-goal-v1:DAY:20000",
            effectRevision = 7L,
            time = 12_345L,
            valueMicros = 25_000_000L,
        ) shouldBe true

        val stored = dao.getRevisionedEffect(
            source = AwardSource.GOAL.value,
            effectKey = "steps-goal-v1:DAY:20000",
        )
        checkNotNull(stored)
        stored.effectRevision shouldBe 7L
        stored.effectValueMicros shouldBe 25_000_000L
        stored.value shouldBe Points(25.0)
        dao.countBetween(Long.MIN_VALUE, Long.MAX_VALUE) shouldBe 25.0
    }

    @Test
    fun newerZeroRevisionPreventsOlderRewardFromResurrecting(): Unit = runBlocking {
        val effectKey = "steps-goal-v1:DAY:20000"
        dao.applyGoalEffect(effectKey, effectRevision = 7L, time = 12_345L, valueMicros = 25_000_000L)
        dao.applyGoalEffect(effectKey, effectRevision = 8L, time = 12_345L, valueMicros = 0L) shouldBe true
        dao.applyGoalEffect(effectKey, effectRevision = 7L, time = 12_345L, valueMicros = 25_000_000L) shouldBe false

        val stored = dao.getRevisionedEffect(AwardSource.GOAL.value, effectKey)
        checkNotNull(stored)
        stored.effectRevision shouldBe 8L
        stored.effectValueMicros shouldBe 0L
        stored.value shouldBe Points(0.0)
        dao.countBetween(Long.MIN_VALUE, Long.MAX_VALUE) shouldBe 0.0
    }

    @Test
    fun exactSameRevisionReplayKeepsTheExistingReceipt(): Unit = runBlocking {
        val effectKey = "steps-goal-v1:DAY:20000"
        dao.applyGoalEffect(effectKey, 7L, 12_345L, 25_000_000L) shouldBe true
        val before = checkNotNull(dao.getRevisionedEffect(AwardSource.GOAL.value, effectKey))

        dao.applyGoalEffect(effectKey, 7L, 12_345L, 25_000_000L) shouldBe true
        dao.getRevisionedEffect(AwardSource.GOAL.value, effectKey) shouldBe before
        dao.updateRevisionedEffect(AwardSource.GOAL.value, effectKey, 7L, 99L, 99.0, 99_000_000L) shouldBe 0
        dao.getRevisionedEffect(AwardSource.GOAL.value, effectKey) shouldBe before
        dao.countBetween(Long.MIN_VALUE, Long.MAX_VALUE) shouldBe 25.0
    }

    @Test
    fun sameRevisionCannotRewriteValueOrEarnedTime(): Unit = runBlocking {
        val effectKey = "steps-goal-v1:DAY:20000"
        dao.applyGoalEffect(effectKey, 7L, 12_345L, 25_000_000L)
        val before = checkNotNull(dao.getRevisionedEffect(AwardSource.GOAL.value, effectKey))

        dao.applyGoalEffect(effectKey, 7L, 12_345L, 0L) shouldBe false
        dao.applyGoalEffect(effectKey, 7L, 12_346L, 25_000_000L) shouldBe false
        dao.getRevisionedEffect(AwardSource.GOAL.value, effectKey) shouldBe before
    }

    @Test
    fun zeroReceiptOnlyAllowsRewardThroughANewerRevision(): Unit = runBlocking {
        val effectKey = "steps-goal-v1:DAY:20000"
        dao.applyGoalEffect(effectKey, 8L, 12_345L, 0L)
        val zero = checkNotNull(dao.getRevisionedEffect(AwardSource.GOAL.value, effectKey))

        dao.applyGoalEffect(effectKey, 8L, 12_345L, 25_000_000L) shouldBe false
        dao.applyGoalEffect(effectKey, 8L, 12_346L, 0L) shouldBe false
        dao.getRevisionedEffect(AwardSource.GOAL.value, effectKey) shouldBe zero
        dao.countBetween(Long.MIN_VALUE, Long.MAX_VALUE) shouldBe 0.0

        dao.applyGoalEffect(effectKey, 9L, 12_345L, 25_000_000L) shouldBe true
        val corrected = checkNotNull(dao.getRevisionedEffect(AwardSource.GOAL.value, effectKey))
        corrected.id shouldBe zero.id
        corrected.effectRevision shouldBe 9L
        corrected.effectValueMicros shouldBe 25_000_000L
        dao.countBetween(Long.MIN_VALUE, Long.MAX_VALUE) shouldBe 25.0
    }

    @Test
    fun sameRevisionRejectsInconsistentStoredPointValue(): Unit = runBlocking {
        val effectKey = "steps-goal-v1:DAY:20000"
        dao.applyGoalEffect(effectKey, 7L, 12_345L, 25_000_000L)
        val corrupted = checkNotNull(dao.getRevisionedEffect(AwardSource.GOAL.value, effectKey))
            .copy(value = Points(99.0))
        dao.update(corrupted)

        dao.applyGoalEffect(effectKey, 7L, 12_345L, 25_000_000L) shouldBe false
        dao.getRevisionedEffect(AwardSource.GOAL.value, effectKey) shouldBe corrupted
    }

    @Test
    fun legacyGoalAwardsRemainIndependentWithoutEffectKeys(): Unit = runBlocking {
        repeat(2) {
            dao.insert(
                PointsAwarded(
                    time = 1_000L + it,
                    value = Points(1.0),
                    source = AwardSource.GOAL,
                ),
            )
        }

        dao.countBetween(Long.MIN_VALUE, Long.MAX_VALUE) shouldBe 2.0
    }

    @Test fun sessionCorrectionAndDeletionReplaceOneReceiptWithoutDoubleCounting(): Unit = runBlocking {
        val key = "recording:0:42"
        dao.applySessionEffect(key, 1, 1_000, 25_000_000) shouldBe true
        val id = checkNotNull(dao.getRevisionedEffect(AwardSource.SESSION.value, key)).id
        dao.applySessionEffect(key, 2, 1_000, 5_000_000) shouldBe true
        dao.countBetween(0, 2_000) shouldBe 5.0
        dao.applySessionEffect(key, 3, 1_000, 0) shouldBe true
        dao.applySessionEffect(key, 1, 1_000, 25_000_000) shouldBe false
        val removed = checkNotNull(dao.getRevisionedEffect(AwardSource.SESSION.value, key))
        removed.id shouldBe id
        removed.effectRevision shouldBe 3L
        removed.effectValueMicros shouldBe 0L
        dao.countBetween(0, 2_000) shouldBe 0.0
    }

    @Test fun timestampOnlyLegacySessionAwardIsPreservedWithoutGuessingNewOwnership(): Unit = runBlocking {
        val original = PointsAwarded(1_000, Points(7.0), AwardSource.SESSION)
        dao.insert(original)
        dao.applySessionEffect("recording:0:42", 1, 1_000, 7_000_000) shouldBe false
        dao.getRevisionedEffect(AwardSource.SESSION.value, "recording:0:42") shouldBe null
        dao.countBetween(0, 2_000) shouldBe 7.0
        dao.applySessionEffect("recording:0:43", 1, 1_001, 3_000_000) shouldBe true
        dao.countBetween(0, 2_000) shouldBe 10.0
    }
}
