package com.adsamcik.tracker.statistics.presenter

import androidx.lifecycle.SavedStateHandle
import arrow.core.right
import com.adsamcik.tracker.feature.map.api.preview.RoutePoint
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.shared.model.AltitudeConversionStatus
import com.adsamcik.tracker.shared.model.AltitudeDatum
import com.adsamcik.tracker.shared.model.AltitudeSource
import com.adsamcik.tracker.shared.model.LocationSample
import com.adsamcik.tracker.shared.model.MotionState
import com.adsamcik.tracker.shared.model.SampleQuality
import com.adsamcik.tracker.shared.model.Trip
import com.adsamcik.tracker.statistics.export.GpxShareHelper
import com.adsamcik.tracker.stats.api.TransportMode
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryCause
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryEntry
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryProductState
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryQuery
import com.adsamcik.tracker.stats.api.repository.LocationSampleRepository
import com.adsamcik.tracker.stats.api.repository.HistoryAvailability
import com.adsamcik.tracker.stats.api.repository.HistoryCapture
import com.adsamcik.tracker.stats.api.repository.HistoryCaptureRevision
import com.adsamcik.tracker.stats.api.repository.HistoryEvidence
import com.adsamcik.tracker.stats.api.repository.HistoryProductState
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.LiveSessionHistorySnapshot
import com.adsamcik.tracker.stats.api.repository.PressureHistory
import com.adsamcik.tracker.stats.api.repository.PressureHistoryCause
import com.adsamcik.tracker.stats.api.repository.PressureHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.PressureSessionHistory
import com.adsamcik.tracker.stats.api.repository.PressureSessionHistoryQuery
import com.adsamcik.tracker.stats.api.repository.SessionHistory
import com.adsamcik.tracker.stats.api.repository.SessionHistoryQuery
import com.adsamcik.tracker.stats.api.repository.SkiRunSegmentRepository
import com.adsamcik.tracker.stats.api.repository.StepsHistory
import com.adsamcik.tracker.stats.api.repository.StepsHistoryCause
import com.adsamcik.tracker.stats.api.repository.StepsHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryRepository
import com.adsamcik.tracker.stats.api.repository.TripPresentationRepository
import com.adsamcik.tracker.stats.api.repository.TripRepository
import com.adsamcik.tracker.stats.api.repository.TripSummary
import com.adsamcik.tracker.stats.api.value.DistanceM
import com.adsamcik.tracker.stats.api.value.DurationMs
import com.adsamcik.tracker.stats.api.value.EpochMs
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import kotlin.math.abs

@OptIn(ExperimentalCoroutinesApi::class)
class TripDetailPresenterViewModelTest {

	private val testDispatcher = StandardTestDispatcher()
	private val dispatchers = TestDispatchersProvider(testDispatcher)
	private val tripRepository: TripRepository = mockk()
	private val trackingHistoryRepository: TrackingHistoryRepository = mockk()
	private val tripPresentationRepository: TripPresentationRepository = mockk()
	private val skiRunSegmentRepository: SkiRunSegmentRepository = mockk()
	private val locationSampleRepository: LocationSampleRepository = mockk()
	private val gpxShareHelper: GpxShareHelper = mockk(relaxed = true)
	private val buildInsightsFn: Method = Class.forName(
		"com.adsamcik.tracker.statistics.presenter.TripDetailPresenterViewModelKt",
	).getDeclaredMethod(
		"buildInsights",
		TripSummary::class.java,
		Trip::class.java,
		List::class.java,
	).also { it.isAccessible = true }

	@BeforeEach
	fun setUp() {
		Dispatchers.setMain(testDispatcher)
		every { trackingHistoryRepository.observeLiveSession(any()) } returns flowOf(completeHistory(TRIP_ID))
	}

	@AfterEach
	fun tearDown() {
		Dispatchers.resetMain()
	}

	@Test
	fun `imported Steps never reads overlapping local supplements or exports GPX`() = runTest {
		val trip = sessionTrip().copy(source = com.adsamcik.tracker.shared.model.SegmentSource.PORTABLE_STEPS_IMPORT)
		coEvery { tripRepository.getTripDetail(TRIP_ID) } returns trip.right()
		val viewModel = createViewModel()
		val collector = backgroundScope.launch { viewModel.state.collect() }
		advanceUntilIdle()

		viewModel.loadSupplementalData()
		viewModel.exportTripGpx(mockk())
		advanceUntilIdle()

		viewModel.insights.value shouldBe TripDetailInsights()
		viewModel.skiSegments.value shouldBe emptyList()
		coVerify(exactly = 0) { tripPresentationRepository.getTripProjection(any()) }
		coVerify(exactly = 0) {
			locationSampleRepository.getOrderedChunkBetween(any(), any(), any(), any(), any())
		}
		coVerify(exactly = 0) { skiRunSegmentRepository.getSegmentsByTimeRange(any(), any()) }
		coVerify(exactly = 0) { gpxShareHelper.exportAndShare(any(), any(), any(), any()) }
		collector.cancel()
	}

	@Test
	fun `emits persisted route points for loaded trip`() = runTest {
		val trip = TripSummary(
			id = TRIP_ID,
			startTimeMs = EpochMs(TRIP_START_MS),
			endTimeMs = EpochMs(TRIP_END_MS),
			distance = DistanceM(5_100f),
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
		viewModel.loadSupplementalData()
		advanceUntilIdle()

		val expectedRoutePoints = listOf(
			RoutePoint(50.0, 14.0),
			RoutePoint(50.01, 14.01),
			RoutePoint(50.02, 14.02),
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

	@Test
	fun `repository-backed sample insights match direct insights across chunk boundary`() = runTest {
		val samples = (0 until SAMPLE_CHUNK_SIZE + 1).map { index ->
			val altitude = when (index) {
				SAMPLE_CHUNK_SIZE - 1 -> 125f
				SAMPLE_CHUNK_SIZE -> 117f
				else -> 100f
			}
			sample(
				id = index + 1L,
				timeMs = TRIP_START_MS + index,
				latE7 = null,
				lonE7 = null,
				altitudeM = altitude,
				speedMps = if (index == SAMPLE_CHUNK_SIZE) 6.5f else 1.5f,
				provider = if (index == SAMPLE_CHUNK_SIZE) "network" else "gps",
			)
		}
		val tripEndMs = TRIP_START_MS + samples.lastIndex
		val trip = TripSummary(
			id = TRIP_ID,
			startTimeMs = EpochMs(TRIP_START_MS),
			endTimeMs = EpochMs(tripEndMs),
			distance = DistanceM(5_100f),
			duration = DurationMs(tripEndMs - TRIP_START_MS),
			primaryMode = TransportMode.WALK,
			sampleCount = samples.size,
		)
		val chunkedRepository = ChunkedLocationSampleRepository(samples)
		coEvery { tripRepository.getTripDetail(TRIP_ID) } returns trip.right()
		coEvery { tripPresentationRepository.getTripProjection(TRIP_ID) } returns null
		coEvery {
			skiRunSegmentRepository.getSegmentsByTimeRange(TRIP_START_MS, tripEndMs)
		} returns emptyList()

		val viewModel = createViewModel(locationSampleRepository = chunkedRepository)
		val stateCollector = backgroundScope.launch { viewModel.state.collect() }

		advanceUntilIdle()
		viewModel.loadSupplementalData()
		advanceUntilIdle()

		val expected = buildInsights(trip, null, samples)
		viewModel.insights.value shouldBe expected
		viewModel.insights.value.elevationGainM shouldBe 25.0
		viewModel.insights.value.elevationLossM shouldBe 8.0
		chunkedRepository.requests shouldBe listOf(
			ChunkRequest(TRIP_START_MS, tripEndMs, null, null, SAMPLE_CHUNK_SIZE),
			ChunkRequest(
				TRIP_START_MS,
				tripEndMs,
				afterTimeMs = samples[SAMPLE_CHUNK_SIZE - 1].timeMs,
				afterId = samples[SAMPLE_CHUNK_SIZE - 1].id,
				limit = SAMPLE_CHUNK_SIZE,
			),
		)
		stateCollector.cancel()
	}

	@Test
	fun `history observer stops after the selected detail has no subscribers`() = runTest {
		val trip = sessionTrip()
		var historyCancelled = false
		coEvery { tripRepository.getTripDetail(TRIP_ID) } returns trip.right()
		every { trackingHistoryRepository.observeLiveSession(TRIP_ID) } returns flow {
			emit(completeHistory(TRIP_ID))
			try {
				awaitCancellation()
			} finally {
				historyCancelled = true
			}
		}

		val viewModel = createViewModel()
		val stateCollector = backgroundScope.launch { viewModel.state.collect() }
		advanceUntilIdle()

		stateCollector.cancel()
		runCurrent()
		advanceTimeBy(5_001L)
		runCurrent()

		historyCancelled shouldBe true
		viewModel.state.value shouldBe TripDetailState.Loading
	}

	@Test
	fun `exact Pressure-only detail never reads or exports Location presentation`() = runTest {
		val trip = sessionTrip()
		coEvery { tripRepository.getTripDetail(TRIP_ID) } returns trip.right()
		every { trackingHistoryRepository.observeLiveSession(TRIP_ID) } returns flowOf(
			pressureOnlyHistory(TRIP_ID),
		)

		val viewModel = createViewModel()
		val stateCollector = backgroundScope.launch { viewModel.state.collect() }
		advanceUntilIdle()

		val loaded = viewModel.state.value as TripDetailState.Loaded
		loaded.sourcePresentation shouldBe TripDetailSourcePresentation.PressureOnly(
			unavailablePressure(),
		)
		viewModel.loadSupplementalData()
		viewModel.exportTripGpx(mockk(relaxed = true))
		advanceUntilIdle()

		coVerify(exactly = 0) { tripPresentationRepository.getTripProjection(any()) }
		coVerify(exactly = 0) {
			locationSampleRepository.getOrderedChunkBetween(any(), any(), any(), any(), any())
		}
		coVerify(exactly = 0) { skiRunSegmentRepository.getSegmentsByTimeRange(any(), any()) }
		coVerify(exactly = 0) { gpxShareHelper.exportAndShare(any(), any(), any(), any()) }
		stateCollector.cancel()
	}

	@Test
	fun `exact Activity-only detail never reads or exports Location presentation`() = runTest {
		val trip = sessionTrip()
		coEvery { tripRepository.getTripDetail(TRIP_ID) } returns trip.right()
		every { trackingHistoryRepository.observeLiveSession(TRIP_ID) } returns flowOf(
			activityOnlyHistory(TRIP_ID),
		)

		val viewModel = createViewModel()
		val stateCollector = backgroundScope.launch { viewModel.state.collect() }
		advanceUntilIdle()

		viewModel.loadSupplementalData()
		viewModel.exportTripGpx(mockk(relaxed = true))
		advanceUntilIdle()

		coVerify(exactly = 0) { tripPresentationRepository.getTripProjection(any()) }
		coVerify(exactly = 0) {
			locationSampleRepository.getOrderedChunkBetween(any(), any(), any(), any(), any())
		}
		coVerify(exactly = 0) { skiRunSegmentRepository.getSegmentsByTimeRange(any(), any()) }
		coVerify(exactly = 0) { gpxShareHelper.exportAndShare(any(), any(), any(), any()) }
		stateCollector.cancel()
	}

	@Test
	fun `exact Steps-only detail never reads or exports Location presentation`() = runTest {
		val trip = sessionTrip()
		coEvery { tripRepository.getTripDetail(TRIP_ID) } returns trip.right()
		every { trackingHistoryRepository.observeLiveSession(TRIP_ID) } returns flowOf(
			stepsOnlyHistory(TRIP_ID),
		)

		val viewModel = createViewModel()
		val stateCollector = backgroundScope.launch { viewModel.state.collect() }
		advanceUntilIdle()

		viewModel.loadSupplementalData()
		viewModel.exportTripGpx(mockk(relaxed = true))
		advanceUntilIdle()

		coVerify(exactly = 0) { tripPresentationRepository.getTripProjection(any()) }
		coVerify(exactly = 0) {
			locationSampleRepository.getOrderedChunkBetween(any(), any(), any(), any(), any())
		}
		coVerify(exactly = 0) { skiRunSegmentRepository.getSegmentsByTimeRange(any(), any()) }
		coVerify(exactly = 0) { gpxShareHelper.exportAndShare(any(), any(), any(), any()) }
		stateCollector.cancel()
	}

	@Test
	fun `history observer failure clears stale Location supplements and keeps retry state`() = runTest {
		val trip = sessionTrip().copy(distance = DistanceM(1_000f))
		val failHistory = CompletableDeferred<Unit>()
		coEvery { tripRepository.getTripDetail(TRIP_ID) } returns trip.right()
		every { trackingHistoryRepository.observeLiveSession(TRIP_ID) } returns flow {
			emit(
				completeHistory(
					segmentId = TRIP_ID,
					capture = exactCapture(setOf(HistorySource.LOCATION)),
				),
			)
			failHistory.await()
			throw IllegalStateException("history failed")
		}
		coEvery { tripPresentationRepository.getTripProjection(TRIP_ID) } returns null
		coEvery {
			locationSampleRepository.getOrderedChunkBetween(
				fromMs = TRIP_START_MS,
				toMs = TRIP_END_MS,
				afterTimeMs = null,
				afterId = null,
				limit = any(),
			)
		} returns listOf(
			sample(
				id = 1L,
				timeMs = TRIP_START_MS,
				latE7 = 500_000_000,
				lonE7 = 140_000_000,
			),
		)
		coEvery {
			skiRunSegmentRepository.getSegmentsByTimeRange(TRIP_START_MS, TRIP_END_MS)
		} returns emptyList()

		val viewModel = createViewModel()
		val stateCollector = backgroundScope.launch { viewModel.state.collect() }
		advanceUntilIdle()
		viewModel.loadSupplementalData()
		advanceUntilIdle()
		viewModel.insights.value.routePoints.isNotEmpty() shouldBe true

		failHistory.complete(Unit)
		advanceUntilIdle()
		(viewModel.state.value as TripDetailState.Loaded).sourcePresentation shouldBe
			TripDetailSourcePresentation.Failed
		viewModel.loadSupplementalData()
		advanceUntilIdle()

		viewModel.insights.value shouldBe TripDetailInsights()
		viewModel.skiSegments.value shouldBe emptyList()
		stateCollector.cancel()
	}

	@Test
	fun `supplemental reads require a visible-screen request and keep one job`() = runTest {
		val trip = sessionTrip()
		coEvery { tripRepository.getTripDetail(TRIP_ID) } returns trip.right()
		coEvery { tripPresentationRepository.getTripProjection(TRIP_ID) } returns null
		stubEmptySupplementalData()

		val viewModel = createViewModel()
		val stateCollector = backgroundScope.launch { viewModel.state.collect() }
		advanceUntilIdle()

		coVerify(exactly = 0) { tripPresentationRepository.getTripProjection(TRIP_ID) }

		viewModel.loadSupplementalData()
		viewModel.loadSupplementalData()
		advanceUntilIdle()

		coVerify(exactly = 1) { tripPresentationRepository.getTripProjection(TRIP_ID) }
		coVerify(exactly = 1) {
			locationSampleRepository.getOrderedChunkBetween(any(), any(), any(), any(), any())
		}
		coVerify(exactly = 1) { skiRunSegmentRepository.getSegmentsByTimeRange(any(), any()) }
		stateCollector.cancel()
	}

	@Test
	fun `sub meter altitude increments accumulate into elevation gain`() = runTest {
		val samples = (0..10).map { index ->
			sample(
				id = index + 1L,
				timeMs = TRIP_START_MS + index * 1_000L,
				latE7 = null,
				lonE7 = null,
				altitudeM = 100f + index * 0.3f,
			)
		}

		val gain = loadInsights(samples).elevationGainM

		gain.shouldNotBeNull()
		abs(gain - 3.0) shouldBeLessThan 1.0
	}

	@Test
	fun `elevation gain is consistent across fine and coarse sampling cadences`() = runTest {
		val fineSamples = (0..10).map { index ->
			sample(
				id = index + 1L,
				timeMs = TRIP_START_MS + index * 1_000L,
				latE7 = null,
				lonE7 = null,
				altitudeM = 100f + index * 0.3f,
			)
		}
		val coarseAltitudes = listOf(100f, 101.2f, 102.4f, 103f)
		val coarseSamples = coarseAltitudes.mapIndexed { index, altitude ->
			sample(
				id = index + 1L,
				timeMs = TRIP_START_MS + index * 3_000L,
				latE7 = null,
				lonE7 = null,
				altitudeM = altitude,
			)
		}

		val fineGain = loadInsights(fineSamples).elevationGainM
		val coarseGain = loadInsights(coarseSamples).elevationGainM

		fineGain.shouldNotBeNull()
		coarseGain.shouldNotBeNull()
		abs(fineGain - coarseGain) shouldBeLessThan 0.01
	}

	private fun buildInsights(
		trip: TripSummary,
		projection: Trip?,
		samples: List<LocationSample>,
	): TripDetailInsights = buildInsightsFn.invoke(null, trip, projection, samples) as TripDetailInsights

	private fun createViewModel(
		locationSampleRepository: LocationSampleRepository = this.locationSampleRepository,
	): TripDetailPresenterViewModel =
		TripDetailPresenterViewModel(
			presenter = TripDetailPresenter(tripRepository, trackingHistoryRepository),
			tripPresentationRepository = tripPresentationRepository,
			skiRunSegmentRepository = skiRunSegmentRepository,
			locationSampleRepository = locationSampleRepository,
			gpxShareHelper = gpxShareHelper,
			dispatchers = dispatchers,
			savedStateHandle = SavedStateHandle(mapOf("tripId" to TRIP_ID)),
		)

	private suspend fun TestScope.loadInsights(samples: List<LocationSample>): TripDetailInsights {
		val tripEndMs = samples.last().timeMs
		val trip = TripSummary(
			id = TRIP_ID,
			startTimeMs = EpochMs(TRIP_START_MS),
			endTimeMs = EpochMs(tripEndMs),
			distance = DistanceM(1_000f),
			duration = DurationMs(tripEndMs - TRIP_START_MS),
			primaryMode = TransportMode.WALK,
			sampleCount = samples.size,
		)
		coEvery { tripRepository.getTripDetail(TRIP_ID) } returns trip.right()
		coEvery { tripPresentationRepository.getTripProjection(TRIP_ID) } returns null
		coEvery {
			skiRunSegmentRepository.getSegmentsByTimeRange(TRIP_START_MS, tripEndMs)
		} returns emptyList()

		val viewModel = createViewModel(ChunkedLocationSampleRepository(samples))
		val stateCollector = backgroundScope.launch { viewModel.state.collect() }
		advanceUntilIdle()
		viewModel.loadSupplementalData()
		advanceUntilIdle()
		stateCollector.cancel()
		return viewModel.insights.value
	}

	private fun sample(
		id: Long,
		timeMs: Long,
		latE7: Int?,
		lonE7: Int?,
		altitudeM: Float? = null,
		speedMps: Float? = null,
		provider: String = "gps",
	): LocationSample = LocationSample(
		id = id,
		timeMs = timeMs,
		elapsedRealtimeNanos = timeMs * 1_000_000L,
		latE7 = latE7,
		lonE7 = lonE7,
		altitudeM = altitudeM,
		rawGpsAltitudeM = altitudeM,
		hAccM = 5f,
		vAccM = null,
		speedMps = speedMps,
		speedAccuracyMps = null,
		provider = provider,
		quality = SampleQuality.HIGH,
		motionState = MotionState.MOVING,
		policy = null,
		bucketId = null,
		createdAt = timeMs,
		altitudeDatum = if (altitudeM != null) {
			AltitudeDatum.ANDROID_MODEL_MSL
		} else {
			AltitudeDatum.UNKNOWN_LEGACY
		},
		altitudeSource = if (altitudeM != null) {
			AltitudeSource.GPS_CONVERSION
		} else {
			AltitudeSource.UNKNOWN_LEGACY
		},
		altitudeConversionStatus = if (altitudeM != null) {
			AltitudeConversionStatus.SUCCESS
		} else {
			AltitudeConversionStatus.UNKNOWN_LEGACY
		},
		rawGpsAltitudeDatum = if (altitudeM != null) {
			AltitudeDatum.WGS84_ELLIPSOID
		} else {
			AltitudeDatum.UNKNOWN_LEGACY
		},
		altitudeModelVersion = if (altitudeM != null) 1 else 0,
		clockDomainId = "test-clock",
	)

	private fun completeHistory(
		segmentId: Long,
		capture: HistoryCapture = HistoryCapture.Unverifiable,
		pressure: PressureHistory = unavailablePressure(),
		steps: StepsHistory = completeSteps(),
		activity: ActivityHistoryEntry = unavailableActivity(
			capturesOnlyActivity = capture.capturesOnlyActivity(),
		),
	): LiveSessionHistorySnapshot = LiveSessionHistorySnapshot(
		segmentId = segmentId,
		session = SessionHistoryQuery.Found(
			SessionHistory(
				segmentId = segmentId,
				capture = capture,
				qualifiedSources = emptySet(),
				steps = steps,
			),
		),
		activity = ActivityHistoryQuery.Found(activity),
		pressure = PressureSessionHistoryQuery.Found(
			PressureSessionHistory(
				segmentId = segmentId,
				capture = capture,
				qualifiedSources = if (pressure.hasRetainedObservation) {
					setOf(HistorySource.PRESSURE)
				} else {
					emptySet()
				},
				pressure = pressure,
			),
		),
	)

	private fun pressureOnlyHistory(segmentId: Long): LiveSessionHistorySnapshot = completeHistory(
		segmentId = segmentId,
		capture = HistoryCapture.Exact(
			listOf(
				HistoryCaptureRevision(
					revision = 1L,
					effectiveAt = EpochMs(TRIP_START_MS),
					capturedSources = setOf(HistorySource.PRESSURE),
					controlSources = emptySet(),
				),
			),
		),
		pressure = unavailablePressure(),
		steps = notCapturedSteps(),
	)

	private fun activityOnlyHistory(segmentId: Long): LiveSessionHistorySnapshot {
		val capture = exactCapture(setOf(HistorySource.ACTIVITY))
		return completeHistory(
			segmentId = segmentId,
			capture = capture,
			steps = notCapturedSteps(),
			activity = unavailableActivity(capturesOnlyActivity = true),
		)
	}

	private fun stepsOnlyHistory(segmentId: Long): LiveSessionHistorySnapshot = completeHistory(
		segmentId = segmentId,
		capture = exactCapture(
			sources = setOf(HistorySource.STEPS),
			controlSources = setOf(HistorySource.ACTIVITY),
		),
		steps = completeSteps(),
	)

	private fun exactCapture(
		sources: Set<HistorySource>,
		controlSources: Set<HistorySource> = emptySet(),
	) = HistoryCapture.Exact(
		listOf(
			HistoryCaptureRevision(
				revision = 1L,
				effectiveAt = EpochMs(TRIP_START_MS),
				capturedSources = sources,
				controlSources = controlSources,
			),
		),
	)

	private fun HistoryCapture.capturesOnlyActivity(): Boolean =
		(this as? HistoryCapture.Exact)?.revisions?.all { revision ->
			revision.capturedSources == setOf(HistorySource.ACTIVITY)
		} == true

	private fun unavailableActivity(capturesOnlyActivity: Boolean) = ActivityHistoryEntry(
		key = ActivityHistoryEntryKey("activity"),
		startTime = EpochMs(TRIP_START_MS),
		endTime = EpochMs(TRIP_END_MS),
		storedZoneIds = setOf("UTC"),
		state = ActivityHistoryProductState.UNAVAILABLE,
		coverage = ActivityHistoryCoverage.NONE,
		activeTime = null,
		fragments = emptyList(),
		causes = setOf(ActivityHistoryCause.NO_QUALIFIED_FACTS),
		capturesOnlyActivity = capturesOnlyActivity,
	)

	private fun unavailablePressure() = PressureHistory(
		availability = HistoryAvailability.UNAVAILABLE,
		evidence = HistoryEvidence.NONE,
		productState = HistoryProductState.DEGRADED,
		coverage = PressureHistoryCoverage.UNKNOWN,
		windows = emptyList(),
		causes = setOf(PressureHistoryCause.PROVIDER_UNAVAILABLE),
	)

	private fun completeSteps() = StepsHistory(
		count = 0L,
		availability = HistoryAvailability.AVAILABLE,
		evidence = HistoryEvidence.ACTIVE,
		productState = HistoryProductState.READY,
		coverage = StepsHistoryCoverage.COMPLETE,
	)

	private fun notCapturedSteps() = StepsHistory(
		count = null,
		availability = HistoryAvailability.UNAVAILABLE,
		evidence = HistoryEvidence.NONE,
		productState = HistoryProductState.PARTIAL,
		coverage = StepsHistoryCoverage.NONE,
		causes = setOf(StepsHistoryCause.SOURCE_NOT_CAPTURED),
	)

	private fun sessionTrip(): TripSummary = TripSummary(
		id = TRIP_ID,
		startTimeMs = EpochMs(TRIP_START_MS),
		endTimeMs = EpochMs(TRIP_END_MS),
		distance = DistanceM(0f),
		duration = DurationMs(TRIP_END_MS - TRIP_START_MS),
		primaryMode = TransportMode.UNKNOWN,
		sampleCount = 1,
	)

	private fun stubEmptySupplementalData() {
		coEvery {
			locationSampleRepository.getOrderedChunkBetween(
				fromMs = TRIP_START_MS,
				toMs = TRIP_END_MS,
				afterTimeMs = null,
				afterId = null,
				limit = any(),
			)
		} returns emptyList()
		coEvery {
			skiRunSegmentRepository.getSegmentsByTimeRange(TRIP_START_MS, TRIP_END_MS)
		} returns emptyList()
	}

	private data class ChunkRequest(
		val fromMs: Long,
		val toMs: Long,
		val afterTimeMs: Long?,
		val afterId: Long?,
		val limit: Int,
	)

	private class ChunkedLocationSampleRepository(samples: List<LocationSample>) : LocationSampleRepository {
		private val orderedSamples = samples.sortedWith(compareBy(LocationSample::timeMs, LocationSample::id))
		val requests = mutableListOf<ChunkRequest>()

		override suspend fun getSamplesBetween(fromMs: Long, toMs: Long): List<LocationSample> =
			orderedSamples.filter { it.timeMs in fromMs..toMs }

		override suspend fun getOrderedChunkBetween(
			fromMs: Long,
			toMs: Long,
			afterTimeMs: Long?,
			afterId: Long?,
			limit: Int,
		): List<LocationSample> {
			requests += ChunkRequest(fromMs, toMs, afterTimeMs, afterId, limit)
			return orderedSamples
				.asSequence()
				.filter { it.timeMs in fromMs..toMs }
				.filter { sample ->
					afterTimeMs == null ||
						sample.timeMs > afterTimeMs ||
						(sample.timeMs == afterTimeMs && (afterId == null || sample.id > afterId))
				}
				.take(limit)
				.toList()
		}
	}

	private companion object {
		const val TRIP_ID = 42L
		const val TRIP_START_MS = 1_000L
		const val TRIP_END_MS = 5_000L
		const val SAMPLE_CHUNK_SIZE = 2_000
	}
}
