package com.adsamcik.tracker.shared.base.database

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.data.LocationFileIdentityEntity
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocationFileIdentityRoomTest {
	private lateinit var database: AppDatabase

	@Before fun setUp() {
		database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext<Application>())
	}

	@After fun tearDown() = database.close()

	@Test fun `one saved point keeps one file ID across overlapping exports and import checks`() = runBlocking {
		val sampleId = database.locationSampleDao().insert(sample())
		val dao = database.locationFileIdentityDao()
		val assigned = dao.localIdentityFor(sampleId)
		assigned.imported shouldBe false
		dao.bySampleId(sampleId) shouldBe assigned
		dao.byPortableId(assigned.portableId) shouldBe assigned
		dao.localIdentityFor(sampleId) shouldBe assigned

		// A second export cannot silently assign another identity to the same saved point.
		dao.insertIfAbsent(assigned.copy(portableId = "point-b")) shouldBe -1L
		dao.bySampleId(sampleId) shouldBe assigned
		val otherSampleId = database.locationSampleDao().insert(sample().copy(timeMs = 2_000L))
		dao.insertIfAbsent(assigned.copy(sampleId = otherSampleId)) shouldBe -1L
		dao.bySampleId(otherSampleId) shouldBe null
		Unit
	}

	@Test fun `point deletion also removes its file identity`() = runBlocking {
		val saved = sample().copy(id = database.locationSampleDao().insert(sample()))
		val dao = database.locationFileIdentityDao()
		dao.insertIfAbsent(LocationFileIdentityEntity("point-a", saved.id, true, "session"))
		database.locationSampleDao().delete(saved)
		dao.byPortableId("point-a") shouldBe null
		Unit
	}

	private fun sample() = LocationSample(
		timeMs = 1_000L,
		elapsedRealtimeNanos = 0L,
		latE7 = 500_000_000,
		lonE7 = 140_000_000,
		altitudeM = null,
		rawGpsAltitudeM = null,
		hAccM = 25f,
		vAccM = null,
		speedMps = null,
		speedAccuracyMps = null,
		provider = "imported",
		quality = SampleQuality.MEDIUM,
		motionState = null,
		policy = null,
		bucketId = null,
		createdAt = 1_000L,
	)
}
