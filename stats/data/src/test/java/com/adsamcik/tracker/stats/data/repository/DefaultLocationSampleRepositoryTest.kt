package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.dao.LocationSampleDao
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class DefaultLocationSampleRepositoryTest {

	private val locationSampleDao: LocationSampleDao = mockk()
	private val repository = DefaultLocationSampleRepository(locationSampleDao)

	@Test
	fun `getSamplesBetween loads samples through ordered chunks`() = runTest {
		val samples = listOf(sample(id = 1L, timeMs = 1_000L), sample(id = 2L, timeMs = 1_500L))
		coEvery {
			locationSampleDao.getChunkBetweenOrdered(1_000L, 2_000L, null, null, any())
		} returns samples

		repository.getSamplesBetween(1_000L, 2_000L) shouldBe samples

		coVerify(exactly = 1) {
			locationSampleDao.getChunkBetweenOrdered(1_000L, 2_000L, null, null, any())
		}
	}

	@Test
	fun `getOrderedChunkBetween preserves stable cursor arguments`() = runTest {
		val samples = listOf(sample(id = 2L, timeMs = 2_000L))
		coEvery {
			locationSampleDao.getChunkBetweenOrdered(1_000L, 3_000L, 1_500L, 1L, 500)
		} returns samples

		repository.getOrderedChunkBetween(1_000L, 3_000L, 1_500L, 1L, 500) shouldBe samples

		coVerify(exactly = 1) {
			locationSampleDao.getChunkBetweenOrdered(1_000L, 3_000L, 1_500L, 1L, 500)
		}
	}

	private fun sample(id: Long = 1L, timeMs: Long): LocationSample = LocationSample(
		id = id,
		timeMs = timeMs,
		elapsedRealtimeNanos = 0L,
		latE7 = 1,
		lonE7 = 1,
		altitudeM = 10f,
		rawGpsAltitudeM = 10f,
		hAccM = 1f,
		vAccM = 1f,
		speedMps = 1f,
		speedAccuracyMps = 1f,
		provider = "gps",
		quality = com.adsamcik.tracker.shared.base.database.data.SampleQuality.HIGH,
		motionState = com.adsamcik.tracker.shared.base.database.data.MotionState.MOVING,
		policy = null,
		bucketId = null,
		createdAt = 0L,
	)
}
