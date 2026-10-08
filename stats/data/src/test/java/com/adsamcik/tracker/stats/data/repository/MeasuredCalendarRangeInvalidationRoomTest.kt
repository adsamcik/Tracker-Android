package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import com.adsamcik.tracker.stats.api.repository.HistoryBrowseMode
import com.adsamcik.tracker.stats.api.repository.HistoryRange
import com.adsamcik.tracker.stats.api.repository.HistoryRangeRead
import com.adsamcik.tracker.stats.api.repository.HistoryRangeRequest
import com.adsamcik.tracker.stats.api.repository.HistorySource
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Room invalidation, facade and saved Location read. Unused capture entry collaborators are fixtures. NOT_RUN. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MeasuredCalendarRangeInvalidationRoomTest {
    private lateinit var database: AppDatabase
    private lateinit var application: Application
    @Before fun setup() = runBlocking {
        application = ApplicationProvider.getApplicationContext()
        database = AppDatabase.testDatabase(application)
        database.sourceEvidenceStateDao().ensure()
    }
    @After fun close() = database.close()

    @Test fun `an older measured mutation still invalidates the month when its first page is identical`() = runBlocking {
        withTimeout(10_000L) {
            point(2_000)
            point(1_000)
            val authority = SourceProductLaneExecutionAuthority { false }
            val steps = StepsSegmentHistorySelector(database, authority)
            val pressure = PressureHistorySelector(database, authority)
            val repository = DefaultTrackingHistoryRepository(database = database, stepsSelector = steps,
                logicalHistoryReader = LogicalTrackingHistoryReader(database, steps), pressureSelector = pressure,
                pressurePageReader = mockk(relaxed = true), sourceUnionReader = mockk(relaxed = true),
                ioDispatcher = Dispatchers.Unconfined, bootClockDomainProvider = mockk(relaxed = true),
                rangeReader = rangeReaderForRoomTest(database, application))
            val range = HistoryRange(0, 3_000, "UTC", sources = setOf(HistorySource.LOCATION),
                mode = HistoryBrowseMode.OBSERVATIONS)
            val observed = Channel<HistoryRangeRead>(4)
            val job = launch(Dispatchers.IO) {
                repository.observeRange(HistoryRangeRequest(range, limit = 1)).collect { observed.send(it) }
            }
            try {
                val first = observed.receive().shouldBeInstanceOf<HistoryRangeRead.Page>()
                first.rows.single().position.startTimeMs shouldBe 2_000L
                point(500)
                database.invalidationTracker.refreshAsync()
                // Equal page content must still tell Calendar to requalify its older day probes.
                val invalidated = observed.receive().shouldBeInstanceOf<HistoryRangeRead.Page>()
                invalidated shouldBe first
            } finally {
                job.cancelAndJoin()
                observed.close()
            }
        }
    }

    private suspend fun point(time: Long) = database.locationSampleDao().insert(LocationSample(timeMs = time,
        elapsedRealtimeNanos = 0, latE7 = 500_000_000, lonE7 = 140_000_000, altitudeM = null,
        rawGpsAltitudeM = null, hAccM = null, vAccM = null, speedMps = null, speedAccuracyMps = null,
        provider = "legacy-fixture", quality = SampleQuality.HIGH, motionState = null, policy = null,
        bucketId = null, createdAt = time))
}
