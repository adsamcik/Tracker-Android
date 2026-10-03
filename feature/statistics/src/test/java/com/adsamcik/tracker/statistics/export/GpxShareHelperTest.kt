package com.adsamcik.tracker.statistics.export

import android.content.Context
import android.content.pm.ApplicationInfo
import com.adsamcik.tracker.feature.statistics.api.export.TripGpxExportResult
import com.adsamcik.tracker.feature.statistics.api.export.TripGpxExporter
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.OutputStream

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
	private val tripGpxExporter = object : TripGpxExporter {
		override suspend fun export(
			outputStream: OutputStream,
			dateRange: LongRange,
			streamLocations: suspend ((LocationSample) -> Unit) -> Unit,
		): TripGpxExportResult {
			streamLocations { }
			return TripGpxExportResult.Success
		}
	}
	private val helper = GpxShareHelper(
		locationSampleRepository,
		tripGpxExporter,
		dispatchersProvider,
		object : TrackingStartupGate {
			override val isReady = true
			override val permitsNewCollection = false
			override suspend fun reconcile(retryFailedStorage: Boolean) = TrackingStartupResult.Ready(false, 0L)
		},
	)

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
	fun `cancellation closes caller-owned GPX stream and deletes its unfinished file`() = runTest(testDispatcher) {
		val started = CompletableDeferred<Unit>()
		var ownedOutput: OutputStream? = null
		val blockingExporter = object : TripGpxExporter {
			override suspend fun export(outputStream: OutputStream, dateRange: LongRange,
				streamLocations: suspend ((LocationSample) -> Unit) -> Unit): TripGpxExportResult {
				ownedOutput = outputStream
				outputStream.write(7)
				started.complete(Unit)
				awaitCancellation()
			}
		}
		val context = mockExportContext()
		val helper = GpxShareHelper(locationSampleRepository, blockingExporter, dispatchersProvider, readyHistoryGate())
		val export = async { helper.exportAndShare(context, 55L, 1_000L, 2_000L) }
		started.await()
		export.cancelAndJoin()
		java.io.File(context.filesDir, "sharable").listFiles()?.size shouldBe 0
		runCatching { ownedOutput!!.write(1) }.isFailure shouldBe true
	}

	@Test
	fun `format failure remains failure and removes private file even without emitted points`() = runTest(testDispatcher) {
		val failing = object : TripGpxExporter {
			override suspend fun export(outputStream: OutputStream, dateRange: LongRange,
				streamLocations: suspend ((LocationSample) -> Unit) -> Unit): TripGpxExportResult {
				outputStream.write(7)
				return TripGpxExportResult.Failure
			}
		}
		val context = mockExportContext()
		GpxShareHelper(locationSampleRepository, failing, dispatchersProvider, readyHistoryGate())
			.exportAndShare(context, 56L, 1_000L, 2_000L) shouldBe TripGpxExportResult.Failure
		java.io.File(context.filesDir, "sharable").listFiles()?.size shouldBe 0
	}

	@Test
	fun `cancelling one concurrent request for the same trip preserves the other physical file`() = runTest(testDispatcher) {
		val entered = listOf(CompletableDeferred<Unit>(), CompletableDeferred<Unit>())
		val releaseSecond = CompletableDeferred<Unit>()
		var nextRequest = 0
		val concurrentExporter = object : TripGpxExporter {
			override suspend fun export(outputStream: OutputStream, dateRange: LongRange,
				streamLocations: suspend ((LocationSample) -> Unit) -> Unit): TripGpxExportResult {
				val request = nextRequest++
				outputStream.write(request + 7)
				entered[request].complete(Unit)
				if (request == 0) awaitCancellation()
				releaseSecond.await()
				outputStream.write(9)
				return TripGpxExportResult.Failure
			}
		}
		val context = mockExportContext()
		val helper = GpxShareHelper(locationSampleRepository, concurrentExporter, dispatchersProvider, readyHistoryGate())
		val first = async { helper.exportAndShare(context, 58L, 1_000L, 2_000L) }
		entered[0].await()
		val second = async { helper.exportAndShare(context, 58L, 1_000L, 2_000L) }
		entered[1].await()
		val folder = java.io.File(context.filesDir, "sharable")
		folder.listFiles()?.size shouldBe 2
		first.cancelAndJoin()
		val surviving = folder.listFiles()!!.single()
		surviving.readBytes().toList() shouldBe listOf(8.toByte())
		releaseSecond.complete(Unit)
		second.await() shouldBe TripGpxExportResult.Failure
		folder.listFiles()?.size shouldBe 0
	}

	@Test
	fun `old request is rejected before GPX folder or Location read after deletion`() = runTest(testDispatcher) {
		var generation = 0L
		val gate = object : TrackingStartupGate {
			override val isReady = true
			override val currentGeneration get() = generation
			override suspend fun reconcile(retryFailedStorage: Boolean): TrackingStartupResult {
				generation++
				return TrackingStartupResult.Ready(false, 0L)
			}
		}
		val context = mockExportContext()
		GpxShareHelper(locationSampleRepository, tripGpxExporter, dispatchersProvider, gate)
			.exportAndShare(context, 57L, 1_000L, 2_000L) shouldBe TripGpxExportResult.Failure
		java.io.File(context.filesDir, "sharable").exists() shouldBe false
		coVerify(exactly = 0) { locationSampleRepository.getOrderedChunkBetween(any(), any(), any(), any(), any()) }
	}

	private fun readyHistoryGate() = object : TrackingStartupGate {
		override val isReady = true
		override val permitsNewCollection = false
		override suspend fun reconcile(retryFailedStorage: Boolean) = TrackingStartupResult.Ready(false, 0L)
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

		result.shouldBeInstanceOf<TripGpxExportResult.Success>()
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

		result shouldBe TripGpxExportResult.Success
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

		result1 shouldBe TripGpxExportResult.Success
		result2 shouldBe TripGpxExportResult.Success
	}
}
