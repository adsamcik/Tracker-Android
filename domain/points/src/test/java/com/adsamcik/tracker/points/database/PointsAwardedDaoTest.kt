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
}
