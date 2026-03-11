package com.adsamcik.tracker.statistics.export

import com.adsamcik.tracker.impexp.exporter.ExportResult
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.dao.LocationSampleDao
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.MotionState
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Tests for [GpxShareHelper] DAO wiring and control flow.
 */
class GpxShareHelperTest {

	private val locationSampleDao: LocationSampleDao = mockk()
	private val testDispatcher = StandardTestDispatcher()
	private val dispatchersProvider = object : DispatchersProvider {
		override val io: CoroutineDispatcher get() = testDispatcher
		override val default: CoroutineDispatcher get() = testDispatcher
		override val main: CoroutineDispatcher get() = testDispatcher
		override val unconfined: CoroutineDispatcher get() = testDispatcher
	}
	private val helper = GpxShareHelper(locationSampleDao, dispatchersProvider)

	private fun buildSample(time: Long, lat: Double, lon: Double): LocationSample {
		return LocationSample(
			timeMs = time,
			elapsedRealtimeNanos = 0L,
			latE7 = (lat * 1e7).toInt(),
			lonE7 = (lon * 1e7).toInt(),
			altitudeM = 100f,
			rawGpsAltitudeM = 100f,
			hAccM = 5f,
			vAccM = 3f,
			speedMps = 1.5f,
			speedAccuracyMps = 0.5f,
			provider = "fused",
			quality = SampleQuality.HIGH,
			motionState = MotionState.MOVING,
			policy = null,
			bucketId = null,
			createdAt = System.currentTimeMillis(),
		)
	}

	@Test
	fun `returns Success when no locations found`() = runTest(testDispatcher) {
		coEvery { locationSampleDao.getAllBetween(1000L, 5000L) } returns emptyList()

		val result = helper.exportAndShare(
			context = mockk(),
			tripId = 1L,
			startTimeMs = 1000L,
			endTimeMs = 5000L,
		)

		result.shouldBeInstanceOf<ExportResult.Success>()
		coVerify(exactly = 1) { locationSampleDao.getAllBetween(1000L, 5000L) }
	}

	@Test
	fun `queries DAO with correct time range`() = runTest(testDispatcher) {
		coEvery { locationSampleDao.getAllBetween(2000L, 8000L) } returns emptyList()

		helper.exportAndShare(
			context = mockk(),
			tripId = 42L,
			startTimeMs = 2000L,
			endTimeMs = 8000L,
		)

		coVerify(exactly = 1) { locationSampleDao.getAllBetween(2000L, 8000L) }
	}

	@Test
	fun `fetches locations for given time range with multiple points`() = runTest(testDispatcher) {
		val samples = listOf(
			buildSample(time = 1000L, lat = 50.08, lon = 14.42),
			buildSample(time = 2000L, lat = 50.09, lon = 14.43),
			buildSample(time = 3000L, lat = 50.10, lon = 14.44),
		)
		coEvery { locationSampleDao.getAllBetween(1000L, 3000L) } returns samples

		// GpxExporter needs a real filesystem and context for file I/O.
		// We verify the DAO interaction happens before the export step fails.
		try {
			helper.exportAndShare(
				context = mockk(relaxed = true),
				tripId = 99L,
				startTimeMs = 1000L,
				endTimeMs = 3000L,
			)
		} catch (_: Exception) {
			// Expected: GpxExporter or FileProvider fails in unit test environment
		}

		coVerify(exactly = 1) { locationSampleDao.getAllBetween(1000L, 3000L) }
	}

	@Test
	fun `returns Success for idempotent empty-data calls`() = runTest(testDispatcher) {
		coEvery { locationSampleDao.getAllBetween(any(), any()) } returns emptyList()

		val result1 = helper.exportAndShare(mockk(), 1L, 0L, 100L)
		val result2 = helper.exportAndShare(mockk(), 1L, 0L, 100L)

		result1 shouldBe ExportResult.Success
		result2 shouldBe ExportResult.Success
	}
}
