package com.adsamcik.tracker.points.database

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.points.data.AwardSource
import com.adsamcik.tracker.points.data.Points
import com.adsamcik.tracker.points.data.PointsAwarded
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

@ExtendWith(RobolectricExtension::class)
class PointsAwardedDaoTest {

	private lateinit var database: PointsDatabase
	private lateinit var dao: PointsAwardedDao

	@BeforeEach
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = PointsDatabase.testDatabase(context)
		dao = database.pointsAwardedDao()
	}

    @AfterEach
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
}
