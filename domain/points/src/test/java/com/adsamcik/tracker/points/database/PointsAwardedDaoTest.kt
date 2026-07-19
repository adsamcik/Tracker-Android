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
}
