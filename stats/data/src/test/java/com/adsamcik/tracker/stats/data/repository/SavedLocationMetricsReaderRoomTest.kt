package com.adsamcik.tracker.stats.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LocationPointCorrection
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.MotionState
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.model.SegmentSource
import io.kotest.matchers.shouldBe
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/** Saved rows, current corrections and all numeric readers share actual Room data. Tests not run. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SavedLocationMetricsReaderRoomTest {
    private lateinit var database: AppDatabase
    private lateinit var reader: SavedLocationMetricsReader
    private val queries = CopyOnWriteArrayList<String>()

    @Before fun setup() {
        database = AppDatabase.inMemoryBuilder(ApplicationProvider.getApplicationContext<Context>())
            .allowMainThreadQueries().setQueryCallback({ sql, _ -> queries += sql }, java.util.concurrent.Executor(Runnable::run)).build()
        reader = SavedLocationMetricsReader(database, TestDispatchersProvider(Dispatchers.Unconfined))
    }
    @After fun close() = database.close()

    @Test fun `correction changes shared trip and summary values while original rows remain intact`() = runTest {
        database.sourceEvidenceStateDao().ensure()
        val start = point(1_000L, 0, 3f)
        val end = point(2_000L, 10_000, 8f)
        val ids = database.locationSampleDao().insert(listOf(start, end))
        val points = database.locationSampleDao().byIds(ids)
        val segmentId = database.sessionSegmentDao().insert(SessionSegment(startTimeMs = 1_000L,
            endTimeMs = 2_000L, distanceM = 999_999f, steps = null, primaryActivity = 7,
            activityConfidence = 100, sampleCount = 2, source = SegmentSource.LEGACY_MIGRATION,
            inferenceVersion = null, createdAt = 2_000L))
        val before = reader.read()
        assertTrue(requireNotNull(before.distanceMeters) > 1.0)
		val day = java.time.Instant.ofEpochMilli(start.timeMs).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay()
		database.dailySummaryDao().upsert(day, 999_999f, 0, 1_000L, 1, 1_000L, 2_000L, java.time.ZoneId.systemDefault().id)
		val daily = DefaultDailySummaryRepository(database.dailySummaryDao(), reader)
		daily.getBetween(day, day).getOrNull()!!.single().totalDistance.raw shouldBe before.distanceMeters!!.toFloat()
        before.maxObservedSpeedMetersPerSecond shouldBe 8.0
        val original = points.last()
        database.locationPointCorrectionDao().replace(LocationPointCorrection.from(original.id, 0L, 1L,
            original.copy(lonE7 = 0, speedMps = 1f)))
        val after = reader.read()
        after.distanceMeters shouldBe 0.0
        after.maxObservedSpeedMetersPerSecond shouldBe 3.0
        reader.readTrip(segmentId).distanceMeters shouldBe 0.0
		val stationaryDay = daily.getBetween(day, day).getOrNull()!!.single()
		stationaryDay.totalDistance.raw shouldBe 0f
		stationaryDay.hasMeasuredDistance shouldBe true
		stationaryDay.totalDuration.raw shouldBe 1_000L
        database.locationSampleDao().byIds(listOf(original.id)).single() shouldBe original
        database.locationSampleDao().deleteExactIds(listOf(original.id))
        reader.read().distanceMeters shouldBe null
        reader.read().pointCount shouldBe 1L
		daily.getBetween(day, day).getOrNull()!!.single().hasMeasuredDistance shouldBe false
    }

    @Test fun `a saved point without accuracy remains usable without inventing a distance`() = runTest {
        database.sourceEvidenceStateDao().ensure()
        database.locationSampleDao().insert(point(1_000L, 0, null))
        val value = reader.read()
        value.pointCount shouldBe 1L
        value.distanceMeters shouldBe null
        value.hasActivityDistances shouldBe false
    }

    @Test fun `one range pass keeps local DST days and a damaged day's gap separate`() = runTest {
        database.sourceEvidenceStateDao().ensure()
        val zone = java.time.ZoneId.of("Europe/Prague")
        fun time(value: String) = java.time.LocalDateTime.parse(value).atZone(zone).toInstant().toEpochMilli()
        val first = java.time.LocalDate.of(2026, 3, 28).toEpochDay()
        val dst = first + 1L
        val stationary = first + 2L
        val missing = first + 3L
        database.locationSampleDao().insert(listOf(
            point(time("2026-03-28T23:58:00"), 0, null),
            point(time("2026-03-28T23:58:30"), 10_000, null),
            point(time("2026-03-29T01:59:00"), 0, null),
            point(time("2026-03-29T03:00:00"), 10_000, null),
            point(time("2026-03-29T12:00:00"), 0, null).copy(latE7 = null, lonE7 = null),
            point(time("2026-03-30T08:00:00"), 0, null),
            point(time("2026-03-30T08:00:30"), 0, null),
        ))
        queries.clear()
        val days = reader.readDays(first, first + 184L, zone)
        assertTrue(requireNotNull(days[first]?.distanceMeters) > 1.0)
        days[first]?.partial shouldBe false
        assertTrue(requireNotNull(days[dst]?.distanceMeters) > 1.0)
        days[dst]?.pointCount shouldBe 2L
        days[dst]?.partial shouldBe true
        days[stationary]?.distanceMeters shouldBe 0.0
        days[stationary]?.partial shouldBe false
        days[missing] shouldBe null
        // Seven saved rows fit one bounded chronological page, regardless of 185 displayed days.
        queries.count { "FROM effective_location_sample AS location_sample LEFT JOIN location_accepted_receipt" in it } shouldBe 1
    }

    @Test fun `WAL snapshot allows provider writes`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (kind in listOf("summary", "metrics")) {
            // Robolectric includes the test name in its directory; keep native Windows paths short.
            val name = "wal-$kind.db"
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val armed = java.util.concurrent.atomic.AtomicBoolean(false)
            val actual = AppDatabase.fileBuilder(context, name)
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .setQueryCallback({ sql, _ ->
                    if ("FROM effective_location_sample AS location_sample LEFT JOIN location_accepted_receipt" in sql &&
                        armed.compareAndSet(true, false)) {
                        entered.countDown()
                        check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    }
                }, java.util.concurrent.Executor(Runnable::run)).build()
            try {
                actual.sourceEvidenceStateDao().ensure()
                actual.locationSampleDao().insert(point(1_000, 0, null))
                val summary = SavedLocationSummaryReader(actual, Dispatchers.IO)
                val metrics = SavedLocationMetricsReader(actual, TestDispatchersProvider(Dispatchers.IO))
                armed.set(true)
                val reading = async(Dispatchers.IO) {
                    if (kind == "summary") {
                        val saved = summary.readWindow(com.adsamcik.tracker.stats.api.repository.HistoryObservationWindow(0, 10_000))
                            as com.adsamcik.tracker.stats.api.repository.SavedLocationSummaryRead.Snapshot
                        saved.readSnapshot.sourceEvidenceRevision shouldBe 0L
                        saved.summary.pointCount
                    } else metrics.read().pointCount
                }
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                // This commit must finish while the earlier read is deliberately held open.
                withTimeout(5_000) { async(Dispatchers.IO) { actual.withTransaction {
                    actual.locationSampleDao().insert(point(2_000, 10_000, null))
                    actual.sourceEvidenceStateDao().incrementRevision(3_000)
                } }.await() }
                release.countDown()
                withTimeout(5_000) { reading.await() } shouldBe 1L
                actual.locationSampleDao().countAll() shouldBe 2L
                metrics.read().pointCount shouldBe 2L
            } finally {
                release.countDown()
                actual.close()
                context.deleteDatabase(name)
            }
        }
    }

    private fun point(time: Long, lon: Int, speed: Float?) = LocationSample(timeMs = time,
        elapsedRealtimeNanos = time * 1_000_000L, latE7 = 0, lonE7 = lon, altitudeM = null,
        rawGpsAltitudeM = null, hAccM = null, vAccM = null, speedMps = speed,
        speedAccuracyMps = null, provider = "legacy", quality = SampleQuality.LOW,
        motionState = MotionState.MOVING, policy = null, bucketId = null, createdAt = time)
}
