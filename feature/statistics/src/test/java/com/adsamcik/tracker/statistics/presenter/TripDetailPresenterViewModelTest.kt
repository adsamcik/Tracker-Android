package com.adsamcik.tracker.statistics.presenter

import androidx.lifecycle.SavedStateHandle
import arrow.core.right
import com.adsamcik.tracker.map.presentation.udf.LatLngModel
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.shared.model.LocationSample
import com.adsamcik.tracker.shared.model.MotionState
import com.adsamcik.tracker.shared.model.SampleQuality
import com.adsamcik.tracker.statistics.export.GpxShareHelper
import com.adsamcik.tracker.stats.api.TransportMode
import com.adsamcik.tracker.stats.api.repository.LocationSampleRepository
import com.adsamcik.tracker.stats.api.repository.SkiRunSegmentRepository
import com.adsamcik.tracker.stats.api.repository.TripPresentationRepository
import com.adsamcik.tracker.stats.api.repository.TripRepository
import com.adsamcik.tracker.stats.api.repository.TripSummary
import com.adsamcik.tracker.stats.api.value.DistanceM
import com.adsamcik.tracker.stats.api.value.DurationMs
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.api.value.StepCount
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TripDetailPresenterViewModelTest {

	private val testDispatcher = StandardTestDispatcher()
	private val dispatchers = TestDispatchersProvider(testDispatcher)
	private val tripRepository: TripRepository = mockk()
	private val tripPresentationRepository: TripPresentationRepository = mockk()
	private val skiRunSegmentRepository: SkiRunSegmentRepository = mockk()
	private val locationSampleRepository: LocationSampleRepository = mockk()
	private val gpxShareHelper: GpxShareHelper = mockk(relaxed = true)

	@BeforeEach
	fun setUp() {
		Dispatchers.setMain(testDispatcher)
	}

	@AfterEach
	fun tearDown() {
		Dispatchers.resetMain()
	}

	@Test
	fun `emits persisted route points for loaded trip`() = runTest {
		val trip = TripSummary(
			id = TRIP_ID,
			startTimeMs = EpochMs(TRIP_START_MS),
			endTimeMs = EpochMs(TRIP_END_MS),
			distance = DistanceM(5_100f),
			steps = StepCount(0),
			duration = DurationMs(TRIP_END_MS - TRIP_START_MS),
			primaryMode = TransportMode.WALK,
			sampleCount = 3,
		)
		val persistedSamples = listOf(
			sample(id = 1L, timeMs = TRIP_START_MS, latE7 = 500_000_000, lonE7 = 140_000_000),
			sample(id = 2L, timeMs = TRIP_START_MS + 1_000L, latE7 = 500_100_000, lonE7 = 140_100_000),
			sample(id = 3L, timeMs = TRIP_END_MS, latE7 = 500_200_000, lonE7 = 140_200_000),
		)
		coEvery { tripRepository.getTripDetail(TRIP_ID) } returns trip.right()
		coEvery { tripPresentationRepository.getTripProjection(TRIP_ID) } returns null
		coEvery {
			locationSampleRepository.getOrderedChunkBetween(
				fromMs = TRIP_START_MS,
				toMs = TRIP_END_MS,
				afterTimeMs = null,
				afterId = null,
				limit = any(),
			)
		} returns persistedSamples
		coEvery { skiRunSegmentRepository.getSegmentsByTimeRange(TRIP_START_MS, TRIP_END_MS) } returns emptyList()

		val viewModel = createViewModel()
		val stateCollector = backgroundScope.launch { viewModel.state.collect() }

		advanceUntilIdle()

		val expectedRoutePoints = listOf(
			LatLngModel(50.0, 14.0),
			LatLngModel(50.01, 14.01),
			LatLngModel(50.02, 14.02),
		)
		viewModel.insights.value.routePoints shouldBe expectedRoutePoints
		coVerify(exactly = 1) {
			locationSampleRepository.getOrderedChunkBetween(
				fromMs = TRIP_START_MS,
				toMs = TRIP_END_MS,
				afterTimeMs = null,
				afterId = null,
				limit = any(),
			)
		}
		stateCollector.cancel()
	}

	private fun createViewModel(): TripDetailPresenterViewModel =
		TripDetailPresenterViewModel(
			presenter = TripDetailPresenter(tripRepository),
			tripPresentationRepository = tripPresentationRepository,
			skiRunSegmentRepository = skiRunSegmentRepository,
			locationSampleRepository = locationSampleRepository,
			gpxShareHelper = gpxShareHelper,
			dispatchers = dispatchers,
			savedStateHandle = SavedStateHandle(mapOf("tripId" to TRIP_ID)),
		)

	private fun sample(
		id: Long,
		timeMs: Long,
		latE7: Int,
		lonE7: Int,
	): LocationSample = LocationSample(
		id = id,
		timeMs = timeMs,
		elapsedRealtimeNanos = timeMs * 1_000_000L,
		latE7 = latE7,
		lonE7 = lonE7,
		altitudeM = null,
		rawGpsAltitudeM = null,
		hAccM = 5f,
		vAccM = null,
		speedMps = null,
		speedAccuracyMps = null,
		provider = "gps",
		quality = SampleQuality.HIGH,
		motionState = MotionState.MOVING,
		policy = null,
		bucketId = null,
		createdAt = timeMs,
	)

	private companion object {
		const val TRIP_ID = 42L
		const val TRIP_START_MS = 1_000L
		const val TRIP_END_MS = 5_000L
	}
}
