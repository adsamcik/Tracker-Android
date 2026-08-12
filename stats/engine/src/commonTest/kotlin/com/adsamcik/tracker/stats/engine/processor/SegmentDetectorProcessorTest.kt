package com.adsamcik.tracker.stats.engine.processor

import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.processor.ProcessorContext
import com.adsamcik.tracker.stats.api.signal.ActivitySignal
import com.adsamcik.tracker.stats.api.signal.LocationSignal
import com.adsamcik.tracker.stats.api.signal.StepSignal
import com.adsamcik.tracker.stats.api.signal.TrackingSignal
import com.adsamcik.tracker.stats.api.value.ActivityConfidence
import com.adsamcik.tracker.stats.api.value.CoordinateE7
import com.adsamcik.tracker.stats.api.value.DistanceM
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.api.value.LatE7
import com.adsamcik.tracker.stats.api.value.LonE7
import com.adsamcik.tracker.stats.api.value.SpeedMps
import com.adsamcik.tracker.stats.api.value.StepCount
import com.adsamcik.tracker.stats.engine.segment.SegmentDetectorConfig
import com.adsamcik.tracker.stats.engine.segment.SessionSegmentDetector
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class SegmentDetectorProcessorTest {

	private fun signalWithoutLocation(timestampMs: Long = 1000L) = TrackingSignal(
		timestampMs = EpochMs(timestampMs),
		activity = ActivitySignal(
			type = DetectedActivityType.WALKING,
			confidence = ActivityConfidence(80),
		),
	)

	private val baseLatE7 = 407_128_000
	private val baseLonE7 = -740_060_000
	private val detectorConfig = SegmentDetectorConfig(
		driftRadiusM = 1f,
		departureDisplacementM = 1f,
		departureConfirmationMs = 0L,
		departureMinSteps = 1,
		stillCyclesForStopPending = 1,
		walkStopTimeoutMs = 0L,
		driveStopTimeoutMs = 0L,
		transitStopTimeoutMs = 0L,
	)

	private fun locationSignal(
		timestampMs: Long,
		latitudeOffsetE7: Int,
		activity: DetectedActivityType,
		speedMps: Float,
		stepDelta: Int,
		distanceDeltaM: Float,
	) = TrackingSignal(
		timestampMs = EpochMs(timestampMs),
		location = LocationSignal(
			coordinate = CoordinateE7(
				lat = LatE7(baseLatE7 + latitudeOffsetE7),
				lon = LonE7(baseLonE7),
			),
			horizontalAccuracyM = 5f,
			speed = SpeedMps.coerced(speedMps),
			distanceDelta = DistanceM.coerced(distanceDeltaM),
		),
		activity = ActivitySignal(activity, ActivityConfidence(80)),
		steps = StepSignal(
			stepDelta = StepCount.coerced(stepDelta),
			totalStepsSinceBoot = stepDelta.toLong(),
		),
	)

	private fun feedTripStart(processor: SegmentDetectorProcessor, startTimestampMs: Long) {
		processor.onSignal(
			locationSignal(
				timestampMs = startTimestampMs,
				latitudeOffsetE7 = 0,
				activity = DetectedActivityType.STILL,
				speedMps = 0f,
				stepDelta = 0,
				distanceDeltaM = 0f,
			),
		)
		processor.onSignal(
			locationSignal(
				timestampMs = startTimestampMs + 1_000L,
				latitudeOffsetE7 = 3_000,
				activity = DetectedActivityType.WALKING,
				speedMps = 1.5f,
				stepDelta = 10,
				distanceDeltaM = 10f,
			),
		)
		processor.onSignal(
			locationSignal(
				timestampMs = startTimestampMs + 2_000L,
				latitudeOffsetE7 = 6_000,
				activity = DetectedActivityType.WALKING,
				speedMps = 1.5f,
				stepDelta = 10,
				distanceDeltaM = 10f,
			),
		)
	}

	@Test
	fun `signal without location produces no events`() = runTest {
		val processor = SegmentDetectorProcessor(SessionSegmentDetector())
		processor.onStart(ProcessorContext(startTimestamp = EpochMs(1000L)))

		processor.onSignal(signalWithoutLocation(2000L))

		val events = processor.onFlush()
		events.shouldBeEmpty()
	}

	@Test
	fun `flush returns pending events and clears buffer`() = runTest {
		val processor = SegmentDetectorProcessor(SessionSegmentDetector())
		processor.onStart(ProcessorContext(startTimestamp = EpochMs(1000L)))

		// No signals means no events
		val firstFlush = processor.onFlush()
		firstFlush.shouldBeEmpty()

		// Second flush also empty since buffer was cleared
		val secondFlush = processor.onFlush()
		secondFlush.shouldBeEmpty()
	}

	@Test
	fun `stop without an active trip returns no final events`() = runTest {
		val processor = SegmentDetectorProcessor(SessionSegmentDetector())
		processor.onStart(ProcessorContext(startTimestamp = EpochMs(1000L)))

		// Feed signals without location — no events generated
		processor.onSignal(signalWithoutLocation(2000L))

		val stopEvents = processor.onStop()
		stopEvents.shouldBeEmpty()

		// After stop, flush should also be empty (buffer cleared)
		val postStopFlush = processor.onFlush()
		postStopFlush.shouldBeEmpty()
	}

	@Test
	fun `stop force-ends an active trip for persistence`() = runTest {
		var completedTrips = 0
		val processor = SegmentDetectorProcessor(
			detector = SessionSegmentDetector(detectorConfig),
			onTripCompleted = { completedTrips++ },
		)
		processor.onStart(ProcessorContext(startTimestamp = EpochMs(1_000L)))
		feedTripStart(processor, startTimestampMs = 1_000L)
		processor.onFlush().single().shouldBeInstanceOf<DomainEvent.TripStarted>()

		processor.onSignal(
			locationSignal(
				timestampMs = 4_000L,
				latitudeOffsetE7 = 9_000,
				activity = DetectedActivityType.WALKING,
				speedMps = 1.5f,
				stepDelta = 5,
				distanceDeltaM = 5f,
			),
		)

		val completed = processor.onStop().single().shouldBeInstanceOf<DomainEvent.TripCompleted>()
		completed.timestampMs shouldBe EpochMs(4_000L)
		completed.duration.raw shouldBe 2_000L
		completedTrips shouldBe 1
		processor.onFlush().shouldBeEmpty()
	}

	@Test
	fun `stop finalizes only the open trip after an earlier trip completed`() = runTest {
		var completedTrips = 0
		val processor = SegmentDetectorProcessor(
			detector = SessionSegmentDetector(detectorConfig),
			onTripCompleted = { completedTrips++ },
		)
		processor.onStart(ProcessorContext(startTimestamp = EpochMs(1_000L)))

		feedTripStart(processor, startTimestampMs = 1_000L)
		processor.onFlush().single().shouldBeInstanceOf<DomainEvent.TripStarted>()
		processor.onSignal(
			locationSignal(4_000L, 6_000, DetectedActivityType.STILL, 0f, 0, 0f),
		)
		processor.onSignal(
			locationSignal(5_000L, 6_000, DetectedActivityType.STILL, 0f, 0, 0f),
		)
		processor.onFlush().single().shouldBeInstanceOf<DomainEvent.TripCompleted>()

		feedTripStart(processor, startTimestampMs = 6_000L)
		processor.onFlush().single().shouldBeInstanceOf<DomainEvent.TripStarted>()
		processor.onSignal(
			locationSignal(9_000L, 9_000, DetectedActivityType.WALKING, 1.5f, 5, 5f),
		)

		processor.onStop().single().shouldBeInstanceOf<DomainEvent.TripCompleted>()
		completedTrips shouldBe 2
		processor.onFlush().shouldBeEmpty()
	}
}
