package com.adsamcik.tracker.map.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.MotionState
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import io.kotest.matchers.collections.shouldHaveSize
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
class GeoRepositorySqlAggregationTest {

    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext<Application>())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `Room returns one weighted row per SQL grid bucket rather than every source point`() = runTest {
        repeat(100) { index ->
            insertLocation(index.toLong(), latE7 = 1_000_000, lonE7 = 1_000_000, speedMps = 1f)
            insertLocation((index + 100).toLong(), latE7 = 11_000_000, lonE7 = 11_000_000, speedMps = 2f)
        }
        val repository = GeoRepositoryImpl(database.unifiedGeoDao())

        val rows = repository.queryWeightedAggregated(
            query = GeoQuery(
                source = GeoSource.LOCATION,
                bounds = Bounds(north = 2.0, east = 2.0, south = -1.0, west = -1.0),
            ),
            weightColumn = "speed",
            aggregation = Aggregation.Sum,
            cellSizeLatDeg = 0.5,
            cellSizeLonDeg = 0.5,
        ).first()

        rows shouldHaveSize 2
        rows.map { it.weight }.sorted() shouldBe listOf(100.0, 200.0)
    }

    private suspend fun insertLocation(
        timeMs: Long,
        latE7: Int,
        lonE7: Int,
        speedMps: Float,
    ) {
        database.locationSampleDao().insert(
            LocationSample(
                timeMs = timeMs,
                elapsedRealtimeNanos = timeMs * 1_000_000L,
                latE7 = latE7,
                lonE7 = lonE7,
                altitudeM = 100f,
                rawGpsAltitudeM = 100f,
                hAccM = 5f,
                vAccM = 10f,
                speedMps = speedMps,
                speedAccuracyMps = 0.5f,
                provider = "fused",
                quality = SampleQuality.HIGH,
                motionState = MotionState.MOVING,
                policy = null,
                bucketId = null,
                createdAt = timeMs,
            ),
        )
    }
}
