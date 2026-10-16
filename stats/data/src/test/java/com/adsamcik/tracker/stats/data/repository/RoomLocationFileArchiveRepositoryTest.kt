package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.deleteAllCollectedDataForTesting
import com.adsamcik.tracker.shared.base.database.data.LocationFileArchiveEntity
import com.adsamcik.tracker.shared.base.database.data.LocationFileArchivePointEntity
import com.adsamcik.tracker.shared.base.database.data.LocationFileIdentityEntity
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import com.adsamcik.tracker.stats.api.repository.LocationFileArchiveRead
import com.adsamcik.tracker.stats.api.repository.LocationFileArchiveRequest
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomLocationFileArchiveRepositoryTest {
	private lateinit var database: AppDatabase
	private lateinit var reader: RoomLocationFileArchiveRepository
	private val archiveIdentity = "a".repeat(64)

	@Before fun setup() {
		database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext<Application>())
		reader = RoomLocationFileArchiveRepository(database, Dispatchers.Unconfined)
		runBlocking { database.sourceEvidenceStateDao().ensure() }
	}

	@After fun cleanup() = database.close()

	@Test fun `bounded pages read only exact members despite identical timestamps`() = runBlocking {
		val first = addPoint("first", 1_000L)
		val second = addPoint("second", 1_000L)
		addPoint("unrelated", 1_000L)
		recordArchive(listOf(first, second))
		val initial = reader.read(request()).shouldBeInstanceOf<LocationFileArchiveRead.Found>()
		initial.declaredPointCount shouldBe 2
		initial.availablePointCount shouldBe 2
		val dao = database.locationFileIdentityDao()
		val firstPage = dao.archivePointPage(archiveIdentity, -1, 1)
		firstPage.map { it.portableId } shouldBe listOf("first")
		val nextPage = dao.archivePointPage(archiveIdentity, firstPage.single().ordinal, 1)
		nextPage.map { it.portableId } shouldBe listOf("second")
		dao.archivePointPage(archiveIdentity, nextPage.single().ordinal, 1) shouldBe emptyList()
		Unit
	}

	@Test fun `deleted member is missing and replay cannot bind a replacement point`() = runBlocking {
		val original = addPoint("first", 1_000L)
		recordArchive(listOf(original))
		database.locationSampleDao().deleteExactIds(listOf(original.second)) shouldBe 1
		val replacement = addPoint("first", 1_000L)
		database.locationFileIdentityDao().recordArchivePoints(listOf(
			LocationFileArchivePointEntity(archiveIdentity, 0, replacement.first, replacement.second)))
		val result = reader.read(request()).shouldBeInstanceOf<LocationFileArchiveRead.Found>()
		result.availablePointCount shouldBe 0
		val member = database.locationFileIdentityDao().archivePointPage(archiveIdentity, -1, 1).single()
		member.portableId shouldBe "first"
		member.sampleId shouldBe null
		Unit
	}

	@Test fun `current retention excludes values without erasing declared membership`() = runBlocking {
		recordArchive(listOf(addPoint("old", 1_000L), addPoint("kept", 2_000L)))
		val epoch = requireNotNull(database.sourceEvidenceStateDao().get()).collectedDataEpoch
		database.sourceEvidenceStateDao().updateLifecycle(epoch, 1_500L, 3_000L)
		val result = reader.read(request()).shouldBeInstanceOf<LocationFileArchiveRead.Found>()
		result.declaredPointCount shouldBe 2
		result.availablePointCount shouldBe 1
		database.locationFileIdentityDao().archivePointPage(archiveIdentity, -1, 256)
			.map { it.portableId } shouldBe listOf("old", "kept")
		Unit
	}

	@Test fun `unfinished source membership cannot be treated as a complete imported entry`() = runBlocking {
		val dao = database.locationFileIdentityDao()
		dao.recordArchive(archive(pointCount = 2))
		val point = addPoint("prefix", 1_000L)
		dao.recordArchivePoints(listOf(LocationFileArchivePointEntity(archiveIdentity, 0, point.first, point.second)))
		reader.read(request()) shouldBe LocationFileArchiveRead.IncompleteMembership
		Unit
	}

	@Test fun `empty validated file is an explicit empty source entry`() = runBlocking {
		database.locationFileIdentityDao().recordArchive(archive(pointCount = 0))
		val result = reader.read(request()).shouldBeInstanceOf<LocationFileArchiveRead.Found>()
		result.declaredPointCount shouldBe 0
		result.availablePointCount shouldBe 0
		database.locationFileIdentityDao().archivePointPage(archiveIdentity, -1, 256) shouldBe emptyList()
		Unit
	}

	@Test fun `original epoch fences an archive after full clear`() = runBlocking {
		recordArchive(listOf(addPoint("first", 1_000L)))
		val original = request()
		AppDatabase.deleteAllCollectedDataForTesting(database, "location-archive-reader-clear",
			original.expectedCollectedDataEpoch + 1L, null, 3_000L)
		reader.read(original) shouldBe LocationFileArchiveRead.DataChanged
		reader.read(request()) shouldBe LocationFileArchiveRead.NotFound
		Unit
	}

	private suspend fun request() = LocationFileArchiveRequest(
		archiveIdentity, requireNotNull(database.sourceEvidenceStateDao().get()).collectedDataEpoch)

	private suspend fun archive(pointCount: Int) = LocationFileArchiveEntity(archiveIdentity,
		LocationFileArchiveEntity.FORMAT, LocationFileArchiveEntity.VERSION,
		requireNotNull(database.sourceEvidenceStateDao().get()).collectedDataEpoch, pointCount)

	private suspend fun recordArchive(points: List<Pair<String, Long>>) {
		val dao = database.locationFileIdentityDao()
		dao.recordArchive(archive(points.size))
		dao.recordArchivePoints(points.mapIndexed { ordinal, point ->
			LocationFileArchivePointEntity(archiveIdentity, ordinal, point.first, point.second)
		})
	}

	private suspend fun addPoint(portableId: String, time: Long): Pair<String, Long> {
		val sampleId = database.locationSampleDao().insert(LocationSample(timeMs = time,
			elapsedRealtimeNanos = 0L, latE7 = 500_000_000, lonE7 = 140_000_000,
			altitudeM = null, rawGpsAltitudeM = null, hAccM = 10f, vAccM = null,
			speedMps = null, speedAccuracyMps = null, provider = "import",
			quality = SampleQuality.HIGH, motionState = null, policy = null, bucketId = null,
			createdAt = 1_000L))
		database.locationFileIdentityDao().insertIfAbsent(LocationFileIdentityEntity(
			portableId, sampleId, true, "unknown"))
		return portableId to sampleId
	}
}
