package com.adsamcik.tracker.points.database

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.points.data.AwardSource
import com.adsamcik.tracker.points.data.Points
import com.adsamcik.tracker.points.data.PointsAwarded
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
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
        assertEquals(0, result)
    }

    @Test
    fun countBetweenFlowEmitsZeroAndUpdatesAfterInsert() = runBlocking {
        val now = 10_000L
        val flow = dao.countBetweenFlow(0L, now)

        val initial = flow.first()
        assertEquals(0, initial)

        dao.insert(
            PointsAwarded(
                time = now - 1_000L,
                value = Points(42.0),
                source = AwardSource.GOAL
            )
        )

        val updated = flow.first()
        assertEquals(42, updated)
    }
}
