package com.adsamcik.tracker.impexp.exporter.data

import android.content.Context
import com.adsamcik.tracker.shared.base.data.SessionActivity
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.ActivityDao
import com.adsamcik.tracker.shared.base.database.dao.TripDao
import com.adsamcik.tracker.shared.base.database.data.Trip
import com.adsamcik.tracker.shared.model.SegmentSource
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RoomImportExportDataRepositoryTest {

    private val context: Context = mockk()
    private val appDatabase: AppDatabase = mockk()
    private val tripDao: TripDao = mockk()
    private val activityDao: ActivityDao = mockk()
    private lateinit var repository: RoomImportExportDataRepository

    @BeforeEach
    fun setUp() {
        every { appDatabase.tripDao() } returns tripDao
        every { appDatabase.activityDao() } returns activityDao
        repository = RoomImportExportDataRepository(context, appDatabase)
    }

    @Test
    fun `share snapshot aggregates trips and resolves the primary activity`() = runTest {
        every { tripDao.countTripsBetween(100L, 500L) } returns 2L
        coEvery { tripDao.getBetween(100L, 500L) } returns listOf(
            trip(
                id = 2L,
                startTimeMs = 300L,
                endTimeMs = 500L,
                distanceM = 1_250f,
                steps = 800,
                primaryActivity = 7,
            ),
            trip(
                id = 1L,
                startTimeMs = 100L,
                endTimeMs = 250L,
                distanceM = 750f,
                steps = null,
                primaryActivity = null,
            ),
        )
        coEvery {
            activityDao.getLocalized(context, 7L)
        } returns SessionActivity(id = 7L, name = "Walking")

        repository.loadTripShareSnapshot(100L, 500L) shouldBe TripShareSnapshot(
            startTimeMs = 300L,
            totalDistanceM = 2_000f,
            totalDurationMs = 350L,
            totalSteps = 800,
            activityName = "Walking",
            activityId = 7L,
        )
    }

    @Test
    fun `share snapshot avoids loading trips when the range is empty`() = runTest {
        every { tripDao.countTripsBetween(100L, 500L) } returns 0L

        repository.loadTripShareSnapshot(100L, 500L) shouldBe null

        coVerify(exactly = 0) { tripDao.getBetween(any(), any()) }
    }

    private fun trip(
        id: Long,
        startTimeMs: Long,
        endTimeMs: Long,
        distanceM: Float,
        steps: Int?,
        primaryActivity: Int?,
    ) = Trip(
        id = id,
        startTimeMs = startTimeMs,
        endTimeMs = endTimeMs,
        distanceM = distanceM,
        steps = steps,
        primaryActivity = primaryActivity,
        activityConfidence = null,
        sampleCount = 1,
        source = SegmentSource.USER_CREATED,
        createdAt = startTimeMs,
    )
}
