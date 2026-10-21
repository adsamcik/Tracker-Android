package com.adsamcik.tracker.stats.data.repository

import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ActivitySnapshot
import com.adsamcik.tracker.shared.base.database.data.AllDaySensorObservationIdentity
import com.adsamcik.tracker.shared.base.database.data.ObservationStampColumns
import com.adsamcik.tracker.shared.base.database.data.PressureSample
import com.adsamcik.tracker.stats.api.repository.HistoryBrowseMode
import com.adsamcik.tracker.stats.api.repository.HistoryMeasurement
import com.adsamcik.tracker.stats.api.repository.HistoryOrigin
import com.adsamcik.tracker.stats.api.repository.HistoryPosition
import com.adsamcik.tracker.stats.api.repository.HistoryProblem
import com.adsamcik.tracker.stats.api.repository.HistoryProblemReason
import com.adsamcik.tracker.stats.api.repository.HistoryPurpose
import com.adsamcik.tracker.stats.api.repository.HistoryRange
import com.adsamcik.tracker.stats.api.repository.HistorySource
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/** Real Room key discovery and exact saved row reads. Android/provider edges are absent. NOT_RUN. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MeasuredLegacySensorHistoryReaderRoomTest {
    private lateinit var database: AppDatabase
    private lateinit var reader: MeasuredLegacySensorHistoryReader
    @Before fun setup() = runTest {
        database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext())
        database.sourceEvidenceStateDao().ensure()
        reader = MeasuredLegacySensorHistoryReader(database)
    }
    @After fun close() = database.close()

    @Test fun `all day observations are local background data without sessions or current recording permission`() = runTest {
        val identity = AllDaySensorObservationIdentity.activity("event-one")
        database.activitySnapshotDao().insert(ActivitySnapshot(timeMs = 1_000, activityType = 7,
            confidence = 65, isTransition = true, createdAt = 2_000, sourceSignalId = identity))
        activity(1_001)
        val range = range(0, 3_000).copy(origins = setOf(HistoryOrigin.LOCAL),
            purposes = setOf(HistoryPurpose.BACKGROUND))
        val read = database.withTransaction {
            reader.qualify(HistorySource.ACTIVITY, reader.candidates(HistorySource.ACTIVITY, range, null), range)
        }
        read.problems shouldBe emptySet()
        val row = read.rows.values.single()
        row.position.stableId shouldBe identity
        row.position.startTimeMs shouldBe 1_000L
        row.origins shouldBe setOf(HistoryOrigin.LOCAL)
        row.purpose shouldBe HistoryPurpose.BACKGROUND
        row.toExclusiveMs shouldBe null
        database.sourcePolicyDao().authority() shouldBe null
        database.sourceSessionDao().activeSession() shouldBe null
    }

    @Test fun `imported all day observations keep their identity and do not acquire local capture labels`() = runTest {
        val identity = AllDaySensorObservationIdentity.pressure("event-two")
        database.pressureSampleDao().insert(PressureSample(timeMs = 1_000, elapsedRealtimeNanos = 20,
            pressureHpa = 1_010f, altitudeM = 0f, bucketId = null, createdAt = 2_000,
            sourceSignalId = identity, observationStamp = ObservationStampColumns(
                capabilityFlags = AllDaySensorObservationIdentity.IMPORTED_CAPABILITY)))
        val range = range(0, 3_000).copy(origins = setOf(HistoryOrigin.IMPORTED),
            purposes = setOf(HistoryPurpose.BACKGROUND))
        val keys = reader.candidates(HistorySource.PRESSURE, range, null)
        val row = reader.qualify(HistorySource.PRESSURE, keys, range).rows.values.single()
        row.position.stableId shouldBe identity
        row.origins shouldBe setOf(HistoryOrigin.IMPORTED)
        row.purpose shouldBe HistoryPurpose.BACKGROUND
        reader.candidates(HistorySource.PRESSURE, range.copy(origins = setOf(HistoryOrigin.LOCAL)), null) shouldBe emptyList()
        reader.candidates(HistorySource.PRESSURE, range.copy(purposes = setOf(HistoryPurpose.CAPTURE)), null) shouldBe emptyList()
    }

    @Test fun `mixed native and old observations with equal times page once in global order`() = runTest {
        activity(1_000)
        (1..102).forEach { index -> database.activitySnapshotDao().insert(ActivitySnapshot(
            timeMs = 1_000, activityType = 7, confidence = 50, isTransition = false, createdAt = 1_000,
            sourceSignalId = AllDaySensorObservationIdentity.activity("event-$index"))) }
        val range = range(0, 2_000)
        val first = reader.candidates(HistorySource.ACTIVITY, range, null)
        first.size shouldBe 101
        val second = reader.candidates(HistorySource.ACTIVITY, range, measuredHistoryPosition(first[99]))
        val rows = first.take(100) + second
        rows.size shouldBe 103
        rows.map { it.identity }.distinct().size shouldBe rows.size
        rows.map(::measuredHistoryPosition).sorted() shouldBe rows.map(::measuredHistoryPosition)
    }

    @Test fun `dense Activity data pages beyond 4096 with distinct equal timestamp keys`() = runTest {
        val ids = database.withTransaction { (1..4_201).map { activity(1_000L + it / 3) } }
        val range = range(0L, 10_000L)
        val found = mutableListOf<Long>()
        var after: HistoryPosition? = null
        var pages = 0
        do {
            val candidates = database.withTransaction { reader.candidates(HistorySource.ACTIVITY, range, after) }
            (candidates.size <= 101) shouldBe true
            val keys = candidates.take(100)
            val read = database.withTransaction { reader.qualify(HistorySource.ACTIVITY, keys, range) }
            read.problems shouldBe emptySet()
            keys.forEach { key ->
                val row = requireNotNull(read.rows[measuredHistoryPosition(key)])
                row.purpose shouldBe HistoryPurpose.UNKNOWN
                row.origins shouldBe setOf(HistoryOrigin.LEGACY)
                row.name shouldBe null
                row.toExclusiveMs shouldBe null
                row.measurement.shouldBeInstanceOf<HistoryMeasurement.LegacyActivity>().activityType shouldBe 7
                found += requireNotNull(key.rowId)
            }
            after = keys.lastOrNull()?.let(::measuredHistoryPosition)
            pages++
            (pages <= 43) shouldBe true
        } while (candidates.size > 100)
        found.size shouldBe 4_201
        found.toSet() shouldBe ids.toSet()
        found.distinct().size shouldBe found.size
    }

    @Test fun `Pressure keeps the saved aggregate without manufacturing a wall time window`() = runTest {
        val id = pressure(1_000L)
        val range = range(0L, 2_000L)
        val read = database.withTransaction {
            reader.qualify(HistorySource.PRESSURE, reader.candidates(HistorySource.PRESSURE, range, null), range)
        }
        read.problems shouldBe emptySet()
        val row = read.rows.values.single()
        row.position.startTimeMs shouldBe 1_000L
        row.position.stableId shouldBe "pressure_legacy:" + id.toString().padStart(20, '0')
        row.toExclusiveMs shouldBe null
        row.purpose shouldBe HistoryPurpose.UNKNOWN
        val value = row.measurement.shouldBeInstanceOf<HistoryMeasurement.LegacyPressure>()
        value.meanHectopascals shouldBe 1_010f
        value.minimumHectopascals shouldBe 1_000f
        value.maximumHectopascals shouldBe 1_020f
        value.sampleCount shouldBe 4
        value.windowStartElapsedRealtimeNanos shouldBe 10L
        value.windowEndElapsedRealtimeNanos shouldBe 20L
    }

    @Test fun `late deliveries select by original measured local day including its first millisecond`() = runTest {
        val zone = ZoneId.of("Europe/Prague")
        val day = LocalDate.of(2026, 3, 29)
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1L).atStartOfDay(zone).toInstant().toEpochMilli()
        val kept = activity(start, createdAt = end + 100L)
        activity(start - 1L, createdAt = end + 100L)
        activity(end, createdAt = end + 100L)
        val range = range(start, end).copy(viewZoneId = zone.id)
        reader.candidates(HistorySource.ACTIVITY, range, null).map { it.rowId } shouldBe listOf(kept)
        end - start shouldBe 23L * 60L * 60L * 1_000L
    }

    @Test fun `retention rejects only older leaves without turning the survivors into a recording`() = runTest {
        activity(999L)
        val kept = activity(1_000L)
        database.sourceEvidenceStateDao().updateLifecycle(0L, 1_000L, 2_000L)
        val range = range(0L, 3_000L)
        val read = database.withTransaction {
            reader.qualify(HistorySource.ACTIVITY, reader.candidates(HistorySource.ACTIVITY, range, null).take(100), range)
        }
        read.rows.values.single().position.stableId shouldBe "activity_legacy:" + kept.toString().padStart(20, '0')
        read.problems shouldBe emptySet()
    }

    @Test fun `malformed Pressure leaves do not suppress good sibling measurements`() = runTest {
        val kept = pressure(1_000L)
        pressure(1_001L, mean = -1f)
        val range = range(0L, 2_000L)
        val read = database.withTransaction {
            reader.qualify(HistorySource.PRESSURE, reader.candidates(HistorySource.PRESSURE, range, null), range)
        }
        read.rows.values.single().position.stableId shouldBe "pressure_legacy:" + kept.toString().padStart(20, '0')
        read.problems shouldBe setOf(HistoryProblem(HistorySource.PRESSURE, HistoryProblemReason.INTEGRITY_UNAVAILABLE))
    }

    @Test fun `browse subsets never relabel legacy rows as authorized capture or imported data`() = runTest {
        activity(1_000L)
        val range = range(0L, 2_000L)
        reader.candidates(HistorySource.ACTIVITY, range.copy(purposes = setOf(HistoryPurpose.CAPTURE)), null) shouldBe emptyList()
        reader.candidates(HistorySource.ACTIVITY, range.copy(origins = setOf(HistoryOrigin.IMPORTED)), null) shouldBe emptyList()
        reader.candidates(HistorySource.ACTIVITY, range.copy(sources = setOf(HistorySource.STEPS)), null) shouldBe emptyList()
    }

    private fun range(from: Long, to: Long) = HistoryRange(from, to, "UTC",
        sources = setOf(HistorySource.ACTIVITY, HistorySource.PRESSURE),
        mode = HistoryBrowseMode.OBSERVATIONS)
    private suspend fun activity(time: Long, createdAt: Long = time): Long = database.activitySnapshotDao().insert(
        ActivitySnapshot(timeMs = time, activityType = 7, confidence = 65, isTransition = true, createdAt = createdAt))
    private suspend fun pressure(time: Long, mean: Float = 1_010f): Long = database.pressureSampleDao().insert(
        PressureSample(timeMs = time, elapsedRealtimeNanos = 20L, pressureHpa = mean, altitudeM = 12f,
            bucketId = null, createdAt = time, sampleCount = 4, minPressureHpa = 1_000f, maxPressureHpa = 1_020f,
            standardDeviationHpa = 2f, windowStartElapsedRealtimeNanos = 10L, windowEndElapsedRealtimeNanos = 20L))
}
