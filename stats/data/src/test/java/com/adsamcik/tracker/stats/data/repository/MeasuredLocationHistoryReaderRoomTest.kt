package com.adsamcik.tracker.stats.data.repository

import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LocationFileIdentityEntity
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import com.adsamcik.tracker.stats.api.repository.HistoryBrowseMode
import com.adsamcik.tracker.stats.api.repository.HistoryLocationCoverage
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
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/** Actual Room point keys and saved qualification; no Android provider is activated. NOT_RUN. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MeasuredLocationHistoryReaderRoomTest {
	private lateinit var database: AppDatabase
	private lateinit var reader: MeasuredLocationHistoryReader

	@Before fun setup() = runTest {
		database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext())
		database.sourceEvidenceStateDao().ensure()
		reader = MeasuredLocationHistoryReader(database, RetainedLocationContextReader(database))
	}
	@After fun close() = database.close()

	@Test fun `sessionless saved points page beyond the old whole-cohort cap with exact tie keys`() = runTest {
		val ids = database.withTransaction { (1..4_201).map { point(1_000L + it / 3, accuracy = null) } }
		val range = range(0L, 10_000L)
		val found = mutableListOf<Long>()
		var after: HistoryPosition? = null
		var pages = 0
		do {
			val candidates = database.withTransaction { reader.candidates(range, after) }
			(candidates.size <= 101) shouldBe true
			val selected = candidates.take(100)
			val read = database.withTransaction { reader.qualify(selected, range) }
			read.problems shouldBe emptySet()
			selected.forEach { candidate ->
				val row = requireNotNull(read.rows[measuredHistoryPosition(candidate)])
				row.name shouldBe null
				row.sections shouldBe emptyList()
				row.purpose shouldBe HistoryPurpose.UNKNOWN
				val measurement = row.measurement.shouldBeInstanceOf<HistoryMeasurement.Location>()
				measurement.coverage shouldBe HistoryLocationCoverage.LEGACY_UNVERIFIABLE
				measurement.point.accuracyMeters shouldBe null
				found += requireNotNull(measurement.point.sampleId)
			}
			after = selected.lastOrNull()?.let(::measuredHistoryPosition)
			pages++
			(pages <= 43) shouldBe true
		} while (candidates.size > 100)
		found.size shouldBe 4_201
		found.toSet() shouldBe ids.toSet()
		found.distinct().size shouldBe found.size
	}

	@Test fun `an overnight delivery still selects points by measured local day`() = runTest {
		val day = LocalDate.of(2026, 3, 29)
		val zone = ZoneId.of("Europe/Prague")
		val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
		val end = day.plusDays(1L).atStartOfDay(zone).toInstant().toEpochMilli()
		val kept = point(start, createdAt = end + 100_000L)
		point(start - 1L, createdAt = end + 100_000L)
		point(end, createdAt = end + 100_000L)
		val range = range(start, end).copy(viewZoneId = zone.id)
		val read = database.withTransaction { reader.qualify(reader.candidates(range, null), range) }
		read.rows.values.single().measurement.shouldBeInstanceOf<HistoryMeasurement.Location>().point.sampleId shouldBe kept
		read.rows.keys.single().startTimeMs shouldBe start
		end - start shouldBe 23L * 60L * 60L * 1_000L
	}

	@Test fun `origin and source subsets do not borrow coincident imported or disabled points`() = runTest {
		val native = point(1_000L)
		val imported = point(1_000L)
		database.locationFileIdentityDao().insertIfAbsent(LocationFileIdentityEntity("imported", imported, true, "unknown"))
		val legacy = range(0L, 2_000L).copy(origins = setOf(HistoryOrigin.LEGACY))
		reader.candidates(legacy, null).map { it.rowId } shouldBe listOf(native)
		reader.candidates(legacy.copy(sources = setOf(HistorySource.WIFI)), null) shouldBe emptyList()
		reader.candidates(legacy.copy(origins = setOf(HistoryOrigin.IMPORTED)), null).map { it.rowId } shouldBe listOf(imported)
	}

	@Test fun `a saved retention floor removes only older measured points`() = runTest {
		point(999L)
		val kept = point(1_000L)
		database.sourceEvidenceStateDao().updateLifecycle(0L, 1_000L, 2_000L)
		reader.candidates(range(0L, 3_000L), null).map { it.rowId } shouldBe listOf(kept)
	}

	@Test fun `qualification storage failure retains ordinary saved point with an untimed problem`() = runTest {
		val id = point(1_000L)
		val proof = mockk<RetainedLocationContextReader>()
		coEvery { proof.qualifySavedInTransaction(any()) } throws android.database.sqlite.SQLiteException("Read failure fixture")
		reader = MeasuredLocationHistoryReader(database, proof)
		val range = range(0L, 2_000L)
		val read = database.withTransaction { reader.qualify(reader.candidates(range, null), range) }
		read.rows.values.single().measurement.shouldBeInstanceOf<HistoryMeasurement.Location>().point.sampleId shouldBe id
		read.problems shouldBe setOf(HistoryProblem(HistorySource.LOCATION, HistoryProblemReason.STORAGE_UNAVAILABLE))
	}

	@Test fun `cancellation remains cancellation instead of a saved-source failure`() = runTest {
		point(1_000L)
		val cancelled = CancellationException("Cancelled qualifier fixture")
		val proof = mockk<RetainedLocationContextReader>()
		coEvery { proof.qualifySavedInTransaction(any()) } throws cancelled
		reader = MeasuredLocationHistoryReader(database, proof)
		val range = range(0L, 2_000L)
		var observed: CancellationException? = null
		try { database.withTransaction { reader.qualify(reader.candidates(range, null), range) } }
		catch (failure: CancellationException) { observed = failure }
		(observed === cancelled) shouldBe true
	}

	private fun range(from: Long, to: Long) = HistoryRange(from, to, "UTC",
		sources = setOf(HistorySource.LOCATION), purposes = setOf(HistoryPurpose.UNKNOWN),
		mode = HistoryBrowseMode.OBSERVATIONS)

	private suspend fun point(time: Long, accuracy: Float? = 10f, createdAt: Long = time): Long =
		database.locationSampleDao().insert(LocationSample(timeMs = time, elapsedRealtimeNanos = 0L,
			latE7 = 500_000_000, lonE7 = 140_000_000, altitudeM = null, rawGpsAltitudeM = null,
			hAccM = accuracy, vAccM = null, speedMps = null, speedAccuracyMps = null,
			provider = "legacy-fixture", quality = SampleQuality.HIGH, motionState = null,
			policy = null, bucketId = null, createdAt = createdAt))
}
