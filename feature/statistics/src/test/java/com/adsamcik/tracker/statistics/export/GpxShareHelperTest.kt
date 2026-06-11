package com.adsamcik.tracker.statistics.export

import android.content.Context
import android.content.pm.ApplicationInfo
import com.adsamcik.tracker.impexp.exporter.ExportResult
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.model.LocationSample
import com.adsamcik.tracker.shared.model.MotionState
import com.adsamcik.tracker.shared.model.SampleQuality
import com.adsamcik.tracker.stats.api.repository.LocationSampleRepository
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Tests for [GpxShareHelper] DAO wiring and control flow.
 */
class GpxShareHelperTest {

	private val locationSampleRepository: LocationSampleRepository = mockk()
	private val testDispatcher = StandardTestDispatcher()
	private val dispatchersProvider = object : DispatchersProvider {
		override val io: CoroutineDispatcher get() = testDispatcher
		override val default: CoroutineDispatcher get() = testDispatcher
		override val main: CoroutineDispatcher get() = testDispatcher
		override val unconfined: CoroutineDispatcher get() = testDispatcher
	}
	private val helper = GpxShareHelper(locationSampleRepository, dispatchersProvider)

	private fun mockExportContext(): Context {
		val appInfo = ApplicationInfo().apply {
			labelRes = 0
			nonLocalizedLabel = "TrackerApp"
		}
		return mockk(relaxed = true) {
			every { filesDir } returns kotlin.io.path.createTempDirectory().toFile()
			every { packageName } returns "com.adsamcik.tracker.test"
			every { applicationInfo } returns appInfo
			every { getString(any(), any(), any()) } returns "GPX export"
		}
	}

	private fun buildSample(time: Long, lat: Double?, lon: Double?): LocationSample {
		return LocationSample(
			timeMs = time,
			elapsedRealtimeNanos = 0L,
			latE7 = lat?.let { (it * 1e7).toInt() },
			lonE7 = lon?.let { (it * 1e7).toInt() },
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
		coEvery {
			locationSampleRepository.getOrderedChunkBetween(1000L, 5000L, null, null, 500)
		} returns emptyList()

		val result = helper.exportAndShare(
			context = mockExportContext(),
			tripId = 1L,
			startTimeMs = 1000L,
			endTimeMs = 5000L,
		)

		result.shouldBeInstanceOf<ExportResult.Success>()
		coVerify(exactly = 1) {
			locationSampleRepository.getOrderedChunkBetween(1000L, 5000L, null, null, 500)
		}
	}

	@Test
	fun `queries DAO with correct time range`() = runTest(testDispatcher) {
		coEvery {
			locationSampleRepository.getOrderedChunkBetween(2000L, 8000L, null, null, 500)
		} returns emptyList()

		helper.exportAndShare(
			context = mockExportContext(),
			tripId = 42L,
			startTimeMs = 2000L,
			endTimeMs = 8000L,
		)

		coVerify(exactly = 1) {
			locationSampleRepository.getOrderedChunkBetween(2000L, 8000L, null, null, 500)
		}
	}

	@Test
	fun `fetches locations across multiple chunks`() = runTest(testDispatcher) {
		val firstChunk = listOf(
			buildSample(time = 1000L, lat = null, lon = null),
			buildSample(time = 2000L, lat = null, lon = null),
		)
		val secondChunk = listOf(
			buildSample(time = 3000L, lat = null, lon = null),
		)
		coEvery {
			locationSampleRepository.getOrderedChunkBetween(1000L, 3000L, null, null, 500)
		} returns firstChunk
		coEvery {
			locationSampleRepository.getOrderedChunkBetween(1000L, 3000L, 2000L, 0L, 500)
		} returns secondChunk
		coEvery {
			locationSampleRepository.getOrderedChunkBetween(1000L, 3000L, 3000L, 0L, 500)
		} returns emptyList()

		val result = helper.exportAndShare(
			context = mockExportContext(),
			tripId = 99L,
			startTimeMs = 1000L,
			endTimeMs = 3000L,
		)

		result shouldBe ExportResult.Success
		coVerify(exactly = 1) {
			locationSampleRepository.getOrderedChunkBetween(1000L, 3000L, null, null, 500)
		}
		coVerify(exactly = 1) {
			locationSampleRepository.getOrderedChunkBetween(1000L, 3000L, 2000L, 0L, 500)
		}
		coVerify(exactly = 1) {
			locationSampleRepository.getOrderedChunkBetween(1000L, 3000L, 3000L, 0L, 500)
		}
	}

	@Test
	fun `returns Success for idempotent empty-data calls`() = runTest(testDispatcher) {
		coEvery {
			locationSampleRepository.getOrderedChunkBetween(any(), any(), any(), any(), any())
		} returns emptyList()

		val result1 = helper.exportAndShare(mockExportContext(), 1L, 0L, 100L)
		val result2 = helper.exportAndShare(mockExportContext(), 1L, 0L, 100L)

		result1 shouldBe ExportResult.Success
		result2 shouldBe ExportResult.Success
	}
}
