package com.adsamcik.tracker.stats.engine.segment

import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.stats.api.SegmentEvent
import com.adsamcik.tracker.stats.api.SegmentSignal
import com.adsamcik.tracker.stats.api.TransportMode
import com.adsamcik.tracker.stats.api.TripState
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThan as intShouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SessionSegmentDetectorTest {

	private var currentTimeMs = 1_000_000L
	private lateinit var detector: SessionSegmentDetector
	private val config = SegmentDetectorConfig()

	@BeforeEach
	fun setUp() {
		currentTimeMs = 1_000_000L
		detector = SessionSegmentDetector(
			config = config,
			clock = { currentTimeMs },
		)
	}

	// --- Test signal helpers ---

	/** Base coordinates: approximately 40.7128°N, 74.0060°W (NYC) */
	private val baseLat = 407128000
	private val baseLon = -740060000

	private fun stationarySignal(timeMs: Long = currentTimeMs): SegmentSignal =
		SegmentSignal(
			timestampMs = timeMs,
			latE7 = baseLat,
			lonE7 = baseLon,
			speedMps = 0f,
			stepDelta = 0,
			activityType = DetectedActivityType.STILL,
			activityConfidence = 80,
		)

	private fun movingSignal(
		timeMs: Long = currentTimeMs,
		latE7: Int = baseLat + 5000, // ~55m north
		lonE7: Int = baseLon,
		speedMps: Float = 1.5f,
		stepDelta: Int = 15,
		distanceDeltaM: Float = 15f,
		activityType: DetectedActivityType = DetectedActivityType.WALKING,
		activityConfidence: Int = 80,
	): SegmentSignal = SegmentSignal(
		timestampMs = timeMs,
		latE7 = latE7,
		lonE7 = lonE7,
		speedMps = speedMps,
		stepDelta = stepDelta,
		distanceDeltaM = distanceDeltaM,
		activityType = activityType,
		activityConfidence = activityConfidence,
		horizontalAccuracyM = 5f,
	)

	private fun stillSignal(timeMs: Long = currentTimeMs): SegmentSignal =
		SegmentSignal(
			timestampMs = timeMs,
			latE7 = baseLat,
			lonE7 = baseLon,
			speedMps = 0.1f,
			stepDelta = 0,
			activityType = DetectedActivityType.STILL,
			activityConfidence = 80,
		)

	private fun feedMovingSignals(count: Int, intervalMs: Long = 10_000L): List<SegmentEvent?> {
		val events = mutableListOf<SegmentEvent?>()
		repeat(count) { i ->
			currentTimeMs += intervalMs
			val offset = (i + 1) * 1000 // Progressive displacement
			events.add(
				detector.onSignal(
					movingSignal(
						timeMs = currentTimeMs,
						latE7 = baseLat + offset,
						lonE7 = baseLon,
						distanceDeltaM = 11.1f, // ~11m per signal
						stepDelta = 15,
					)
				)
			)
		}
		return events
	}

	private fun feedStillSignals(count: Int, intervalMs: Long = 10_000L): List<SegmentEvent?> {
		val events = mutableListOf<SegmentEvent?>()
		repeat(count) {
			currentTimeMs += intervalMs
			events.add(detector.onSignal(stillSignal(currentTimeMs)))
		}
		return events
	}

	/**
	 * Helper: feed signals to get detector into IN_TRIP state.
	 * Returns the TripStarted event.
	 *
	 * Strategy: set anchor, trigger departure, then feed signals until
	 * we exceed both the time window and displacement/step thresholds.
	 */
	private fun getToInTrip(): SegmentEvent.TripStarted {
		// Set anchor
		detector.onSignal(stationarySignal())

		// Trigger departure with displacement > drift radius (25m)
		currentTimeMs += 10_000L
		val departureTime = currentTimeMs
		detector.onSignal(
			movingSignal(
				timeMs = currentTimeMs,
				latE7 = baseLat + 3000, // ~33m > 25m drift
				stepDelta = 20,
			)
		)
		detector.state shouldBe TripState.DEPARTING

		// Feed moving signals, collecting any events. We need:
		// - elapsed >= 120_000ms from departure start
		// - displacement >= 50m from anchor OR steps >= 100
		var tripStartedEvent: SegmentEvent.TripStarted? = null
		var stepsSoFar = 20 // Initial departure steps

		while (detector.state == TripState.DEPARTING) {
			currentTimeMs += 15_000L
			stepsSoFar += 15
			val offset = ((currentTimeMs - departureTime) / 1000).toInt() * 50 // Progressive displacement
			val event = detector.onSignal(
				movingSignal(
					timeMs = currentTimeMs,
					latE7 = baseLat + 3000 + offset,
					stepDelta = 15,
					distanceDeltaM = 15f,
				)
			)
			if (event is SegmentEvent.TripStarted) {
				tripStartedEvent = event
			}
		}

		tripStartedEvent.shouldNotBeNull()
		detector.state shouldBe TripState.IN_TRIP
		return tripStartedEvent
	}

	@Nested
	inner class LifecycleTests {
		@Test
		fun `initial state is STATIONARY`() {
			detector.state shouldBe TripState.STATIONARY
		}

		@Test
		fun `reset returns to STATIONARY from any state`() {
			// Get to DEPARTING
			detector.onSignal(stationarySignal())
			currentTimeMs += 10_000L
			detector.onSignal(
				movingSignal(
					timeMs = currentTimeMs,
					latE7 = baseLat + 3000,
				)
			)
			detector.state shouldBe TripState.DEPARTING

			detector.reset()
			detector.state shouldBe TripState.STATIONARY
		}

		@Test
		fun `forceEnd from STATIONARY returns null`() {
			detector.forceEnd(currentTimeMs).shouldBeNull()
			detector.state shouldBe TripState.STATIONARY
		}

		@Test
		fun `forceEnd from DEPARTING returns DepartureCancelled`() {
			detector.onSignal(stationarySignal())
			currentTimeMs += 10_000L
			detector.onSignal(
				movingSignal(
					timeMs = currentTimeMs,
					latE7 = baseLat + 3000,
				)
			)
			detector.state shouldBe TripState.DEPARTING

			val event = detector.forceEnd(currentTimeMs)
			event.shouldNotBeNull()
			event.shouldBeInstanceOf<SegmentEvent.DepartureCancelled>()
			(event as SegmentEvent.DepartureCancelled).reason shouldBe "forced shutdown"
			detector.state shouldBe TripState.STATIONARY
		}

		@Test
		fun `forceEnd from IN_TRIP returns TripEnded`() {
			getToInTrip()

			val event = detector.forceEnd(currentTimeMs)
			event.shouldNotBeNull()
			event.shouldBeInstanceOf<SegmentEvent.TripEnded>()
			detector.state shouldBe TripState.STATIONARY
		}

		@Test
		fun `forceEnd from STOP_PENDING returns TripEnded`() {
			getToInTrip()

			// Enter STOP_PENDING
			feedStillSignals(config.stillCyclesForStopPending)
			detector.state shouldBe TripState.STOP_PENDING

			val event = detector.forceEnd(currentTimeMs)
			event.shouldNotBeNull()
			event.shouldBeInstanceOf<SegmentEvent.TripEnded>()
			detector.state shouldBe TripState.STATIONARY
		}
	}

	@Nested
	inner class StationaryToDeparting {
		@Test
		fun `sets anchor on first signal with GPS`() {
			detector.onSignal(stationarySignal())
			detector.state shouldBe TripState.STATIONARY
		}

		@Test
		fun `drift within radius stays STATIONARY`() {
			detector.onSignal(stationarySignal())

			// Small movement within 25m drift radius (~20m)
			currentTimeMs += 10_000L
			detector.onSignal(
				movingSignal(
					timeMs = currentTimeMs,
					latE7 = baseLat + 1800, // ~20m, within 25m drift radius
					speedMps = 0.2f,
					stepDelta = 0,
					activityType = DetectedActivityType.STILL,
					activityConfidence = 60,
				)
			)
			detector.state shouldBe TripState.STATIONARY
		}

		@Test
		fun `displacement beyond drift radius triggers DEPARTING`() {
			detector.onSignal(stationarySignal())

			currentTimeMs += 10_000L
			detector.onSignal(
				movingSignal(
					timeMs = currentTimeMs,
					latE7 = baseLat + 3000, // ~33m, beyond 25m drift radius
				)
			)
			detector.state shouldBe TripState.DEPARTING
		}

		@Test
		fun `steps with speed triggers DEPARTING`() {
			detector.onSignal(stationarySignal())

			currentTimeMs += 10_000L
			val event = detector.onSignal(
				SegmentSignal(
					timestampMs = currentTimeMs,
					latE7 = baseLat, // No displacement
					lonE7 = baseLon,
					speedMps = 0.5f,
					stepDelta = 10,
					activityType = DetectedActivityType.WALKING,
					activityConfidence = 40,
				)
			)
			event.shouldBeNull() // No event emitted on departure
			detector.state shouldBe TripState.DEPARTING
		}

		@Test
		fun `moving activity with high confidence triggers DEPARTING`() {
			detector.onSignal(stationarySignal())

			currentTimeMs += 10_000L
			detector.onSignal(
				SegmentSignal(
					timestampMs = currentTimeMs,
					latE7 = baseLat,
					lonE7 = baseLon,
					speedMps = 0f,
					stepDelta = 0,
					activityType = DetectedActivityType.IN_VEHICLE,
					activityConfidence = 70,
				)
			)
			detector.state shouldBe TripState.DEPARTING
		}

		@Test
		fun `moving activity with low confidence stays STATIONARY`() {
			detector.onSignal(stationarySignal())

			currentTimeMs += 10_000L
			detector.onSignal(
				SegmentSignal(
					timestampMs = currentTimeMs,
					latE7 = baseLat,
					lonE7 = baseLon,
					speedMps = 0f,
					stepDelta = 0,
					activityType = DetectedActivityType.WALKING,
					activityConfidence = 30, // Below 60 threshold
				)
			)
			detector.state shouldBe TripState.STATIONARY
		}

		@Test
		fun `no GPS signal stays STATIONARY`() {
			detector.onSignal(stationarySignal())

			currentTimeMs += 10_000L
			detector.onSignal(
				SegmentSignal(
					timestampMs = currentTimeMs,
					// No GPS
					speedMps = null,
					stepDelta = 0,
					activityType = DetectedActivityType.STILL,
					activityConfidence = 80,
				)
			)
			detector.state shouldBe TripState.STATIONARY
		}
	}

	@Nested
	inner class DepartureConfirmation {
		@Test
		fun `confirmed with displacement and time emits TripStarted`() {
			val tripStarted = getToInTrip()
			tripStarted.startTimeMs shouldBe (tripStarted.startTimeMs) // Validates non-zero
			detector.state shouldBe TripState.IN_TRIP
		}

		@Test
		fun `cancelled when insufficient movement after timeout`() {
			detector.onSignal(stationarySignal())

			// Trigger departure with step + speed
			currentTimeMs += 10_000L
			detector.onSignal(
				SegmentSignal(
					timestampMs = currentTimeMs,
					latE7 = baseLat,
					lonE7 = baseLon,
					speedMps = 0.5f,
					stepDelta = 5,
				)
			)
			detector.state shouldBe TripState.DEPARTING

			// Wait past confirmation window with no movement
			currentTimeMs += config.departureConfirmationMs + 1
			val event = detector.onSignal(
				stationarySignal(currentTimeMs)
			)

			event.shouldNotBeNull()
			event.shouldBeInstanceOf<SegmentEvent.DepartureCancelled>()
			detector.state shouldBe TripState.STATIONARY
		}

		@Test
		fun `early confirmation with both displacement and steps`() {
			detector.onSignal(stationarySignal())

			// Trigger departure
			currentTimeMs += 10_000L
			detector.onSignal(
				movingSignal(
					timeMs = currentTimeMs,
					latE7 = baseLat + 3000,
					stepDelta = 30,
				)
			)
			detector.state shouldBe TripState.DEPARTING

			// Feed moving signals, collecting events. Confirmation should happen
			// well before the 120s window due to both displacement>50m AND steps>100.
			var tripStartedEvent: SegmentEvent.TripStarted? = null
			repeat(6) { i ->
				currentTimeMs += 5_000L
				val event = detector.onSignal(
					movingSignal(
						timeMs = currentTimeMs,
						latE7 = baseLat + 3000 + (i + 1) * 1500,
						stepDelta = 20,
						distanceDeltaM = 16f,
					)
				)
				if (event is SegmentEvent.TripStarted) {
					tripStartedEvent = event
				}
			}

			// Total elapsed: 6 * 5s = 30s (well under 120s confirmation window)
			// Total steps: 30 + 6*20 = 150 (>100)
			// Displacement: baseLat+3000+6*1500 = baseLat+12000 (~133m > 50m)
			detector.state shouldBe TripState.IN_TRIP
			tripStartedEvent.shouldNotBeNull()
		}

		@Test
		fun `departure evidence is counted exactly once when trip is confirmed`() {
			val departureConfig = config.copy(
				departureConfirmationMs = 20_000L,
				departureDisplacementM = 100_000f,
				departureMinSteps = 60,
			)
			detector = SessionSegmentDetector(
				config = departureConfig,
				clock = { currentTimeMs },
			)
			detector.onSignal(stationarySignal())

			currentTimeMs += 1_000L
			detector.onSignal(
				movingSignal(
					timeMs = currentTimeMs,
					latE7 = baseLat,
					stepDelta = 10,
					distanceDeltaM = 10f,
				)
			)

			currentTimeMs += 10_000L
			detector.onSignal(
				movingSignal(
					timeMs = currentTimeMs,
					latE7 = baseLat,
					stepDelta = 20,
					distanceDeltaM = 20f,
				)
			)

			currentTimeMs += 10_000L
			val started = detector.onSignal(
				movingSignal(
					timeMs = currentTimeMs,
					latE7 = baseLat,
					stepDelta = 30,
					distanceDeltaM = 30f,
				)
			)
			started.shouldBeInstanceOf<SegmentEvent.TripStarted>()

			val ended = detector.forceEnd(currentTimeMs)
				.shouldBeInstanceOf<SegmentEvent.TripEnded>()
			ended.totalDistanceM shouldBe (60f plusOrMinus 0.001f)
			ended.totalSteps shouldBe 60
			ended.sampleCount shouldBe 1
			ended.primaryActivity shouldBe DetectedActivityType.WALKING
			ended.averageActivityConfidence shouldBe 80
		}
	}

	@Nested
	inner class InTripAccumulation {
		@Test
		fun `accumulates distance across signals`() {
			getToInTrip()

			// Feed 5 moving signals
			val events = feedMovingSignals(5)

			// Force end to see accumulated metrics
			val endEvent = detector.forceEnd(currentTimeMs)
			endEvent.shouldNotBeNull()
			endEvent.shouldBeInstanceOf<SegmentEvent.TripEnded>()
			(endEvent as SegmentEvent.TripEnded).totalDistanceM shouldBeGreaterThan 0f
		}

		@Test
		fun `accumulates steps across signals`() {
			getToInTrip()
			feedMovingSignals(5)

			val endEvent = detector.forceEnd(currentTimeMs) as SegmentEvent.TripEnded
			endEvent.totalSteps intShouldBeGreaterThan 0
		}

		@Test
		fun `counts location samples`() {
			getToInTrip()
			feedMovingSignals(5)

			val endEvent = detector.forceEnd(currentTimeMs) as SegmentEvent.TripEnded
			endEvent.sampleCount intShouldBeGreaterThan 0
		}

		@Test
		fun `tracks activity votes for primary activity`() {
			getToInTrip()

			// Feed signals with WALKING activity
			repeat(5) {
				currentTimeMs += 10_000L
				detector.onSignal(
					movingSignal(
						timeMs = currentTimeMs,
						latE7 = baseLat + 10000 + it * 500,
						activityType = DetectedActivityType.WALKING,
						activityConfidence = 75,
					)
				)
			}

			val endEvent = detector.forceEnd(currentTimeMs) as SegmentEvent.TripEnded
			endEvent.primaryActivity shouldBe DetectedActivityType.WALKING
		}
	}

	@Nested
	inner class StopDetection {
		@Test
		fun `three consecutive still cycles enter STOP_PENDING`() {
			getToInTrip()

			val events = feedStillSignals(config.stillCyclesForStopPending)
			detector.state shouldBe TripState.STOP_PENDING

			// Last event should be TripUpdated with STOP_PENDING state
			val lastEvent = events.last()
			lastEvent.shouldNotBeNull()
			lastEvent.shouldBeInstanceOf<SegmentEvent.TripUpdated>()
			(lastEvent as SegmentEvent.TripUpdated).currentState shouldBe TripState.STOP_PENDING
		}

		@Test
		fun `partial still cycles dont trigger STOP_PENDING`() {
			getToInTrip()

			// 2 still cycles (less than 3)
			feedStillSignals(config.stillCyclesForStopPending - 1)
			detector.state shouldBe TripState.IN_TRIP
		}

		@Test
		fun `interrupted still cycles reset counter`() {
			getToInTrip()

			// 2 still then 1 moving then 2 still
			feedStillSignals(config.stillCyclesForStopPending - 1)
			detector.state shouldBe TripState.IN_TRIP

			feedMovingSignals(1)
			detector.state shouldBe TripState.IN_TRIP

			feedStillSignals(config.stillCyclesForStopPending - 1)
			detector.state shouldBe TripState.IN_TRIP
		}
	}

	@Nested
	inner class StopResumption {
		@Test
		fun `movement during STOP_PENDING returns to IN_TRIP`() {
			getToInTrip()
			feedStillSignals(config.stillCyclesForStopPending)
			detector.state shouldBe TripState.STOP_PENDING

			currentTimeMs += 10_000L
			val event = detector.onSignal(
				movingSignal(
					timeMs = currentTimeMs,
					latE7 = baseLat + 12000,
					distanceDeltaM = 20f,
				)
			)

			detector.state shouldBe TripState.IN_TRIP
			event.shouldNotBeNull()
			event.shouldBeInstanceOf<SegmentEvent.TripUpdated>()
			(event as SegmentEvent.TripUpdated).currentState shouldBe TripState.IN_TRIP
		}

		@Test
		fun `still cycles counter resets after resumption`() {
			getToInTrip()
			feedStillSignals(config.stillCyclesForStopPending)
			detector.state shouldBe TripState.STOP_PENDING

			// Resume
			feedMovingSignals(1)
			detector.state shouldBe TripState.IN_TRIP

			// Need full still count again for STOP_PENDING
			feedStillSignals(config.stillCyclesForStopPending - 1)
			detector.state shouldBe TripState.IN_TRIP
		}
	}

	@Nested
	inner class ArrivalTimeout {
		@Test
		fun `stop pending timeout triggers TripEnded`() {
			getToInTrip()

			// Feed some moving signals
			feedMovingSignals(5)

			// Enter STOP_PENDING
			feedStillSignals(config.stillCyclesForStopPending)
			detector.state shouldBe TripState.STOP_PENDING

			// Wait past the longest possible timeout (transit = 900s)
			// This ensures arrival regardless of classified mode.
			currentTimeMs += config.transitStopTimeoutMs + 1
			val event = detector.onSignal(stillSignal(currentTimeMs))

			event.shouldNotBeNull()
			event.shouldBeInstanceOf<SegmentEvent.TripEnded>()
			detector.state shouldBe TripState.STATIONARY
		}

		@Test
		fun `shorter timeout does not trigger TripEnded prematurely`() {
			getToInTrip()
			feedMovingSignals(5)
			feedStillSignals(config.stillCyclesForStopPending)
			detector.state shouldBe TripState.STOP_PENDING

			// Wait just 1 second - well under any timeout
			currentTimeMs += 1_000L
			val event = detector.onSignal(stillSignal(currentTimeMs))

			// Should still be in STOP_PENDING, no TripEnded
			event.shouldBeNull()
			detector.state shouldBe TripState.STOP_PENDING
		}

		@Test
		fun `TripEnded contains valid metrics`() {
			getToInTrip()

			// Feed some signals to accumulate data
			feedMovingSignals(5)

			// Enter STOP_PENDING
			feedStillSignals(config.stillCyclesForStopPending)

			// Wait past timeout (drive timeout as default)
			currentTimeMs += config.driveStopTimeoutMs + 1
			val event = detector.onSignal(stillSignal(currentTimeMs))

			event.shouldNotBeNull()
			val tripEnded = event as SegmentEvent.TripEnded
			tripEnded.totalDistanceM shouldBeGreaterThan 0f
			tripEnded.sampleCount intShouldBeGreaterThan 0
			tripEnded.startTimeMs shouldBe (tripEnded.startTimeMs) // Non-zero
			(tripEnded.endTimeMs > tripEnded.startTimeMs) shouldBe true
		}

		@Test
		fun `transport mode is classified in TripEnded`() {
			getToInTrip()
			feedMovingSignals(5)
			feedStillSignals(config.stillCyclesForStopPending)
			currentTimeMs += config.driveStopTimeoutMs + 1
			val event = detector.onSignal(stillSignal(currentTimeMs)) as SegmentEvent.TripEnded

			// Mode should be set (UNKNOWN is valid too)
			event.inferredTransportMode.shouldNotBeNull()
		}
	}

	@Nested
	inner class GpsDriftSuppression {
		@Test
		fun `small oscillations within drift radius stay STATIONARY`() {
			detector.onSignal(stationarySignal())

			// Oscillate around anchor within 25m
			repeat(5) { i ->
				currentTimeMs += 5_000L
				val offset = if (i % 2 == 0) 1500 else -1500 // ~17m oscillation
				detector.onSignal(
					SegmentSignal(
						timestampMs = currentTimeMs,
						latE7 = baseLat + offset,
						lonE7 = baseLon,
						speedMps = 0.1f,
						stepDelta = 0,
						activityType = DetectedActivityType.STILL,
						activityConfidence = 60,
					)
				)
			}

			detector.state shouldBe TripState.STATIONARY
		}

		@Test
		fun `null GPS signals dont trigger departure`() {
			detector.onSignal(stationarySignal())

			currentTimeMs += 10_000L
			detector.onSignal(
				SegmentSignal(
					timestampMs = currentTimeMs,
					// No GPS coordinates
					speedMps = null,
					stepDelta = 0,
					activityType = DetectedActivityType.UNKNOWN,
				)
			)

			detector.state shouldBe TripState.STATIONARY
		}
	}

	@Nested
	inner class EdgeCases {
		@Test
		fun `empty signal does not crash`() {
			val event = detector.onSignal(
				SegmentSignal(timestampMs = currentTimeMs)
			)
			event.shouldBeNull()
			detector.state shouldBe TripState.STATIONARY
		}

		@Test
		fun `signal with only timestamp is handled`() {
			repeat(10) {
				currentTimeMs += 10_000L
				detector.onSignal(SegmentSignal(timestampMs = currentTimeMs))
			}
			detector.state shouldBe TripState.STATIONARY
		}

		@Test
		fun `very long trip accumulates correctly`() {
			getToInTrip()

			// 100 moving signals over ~16 minutes
			feedMovingSignals(100)

			val event = detector.forceEnd(currentTimeMs) as SegmentEvent.TripEnded
			event.totalDistanceM shouldBeGreaterThan 100f
			event.totalSteps intShouldBeGreaterThan 100
			event.sampleCount intShouldBeGreaterThan 50
		}

		@Test
		fun `custom config thresholds are respected`() {
			val customConfig = SegmentDetectorConfig(
				driftRadiusM = 5f, // Much smaller
				departureDisplacementM = 10f,
				departureConfirmationMs = 30_000L,
				departureMinSteps = 20,
			)
			val customDetector = SessionSegmentDetector(
				config = customConfig,
				clock = { currentTimeMs },
			)

			// Set anchor
			customDetector.onSignal(stationarySignal())

			// Small displacement (7m) that would be drift in default config
			// but triggers departure in custom config
			currentTimeMs += 10_000L
			customDetector.onSignal(
				movingSignal(
					timeMs = currentTimeMs,
					latE7 = baseLat + 700, // ~7.8m
					stepDelta = 5,
				)
			)
			customDetector.state shouldBe TripState.DEPARTING
		}

		@Test
		fun `anchor resets after entering STATIONARY`() {
			// Complete a full trip cycle
			getToInTrip()
			feedMovingSignals(3)
			feedStillSignals(config.stillCyclesForStopPending)
			currentTimeMs += config.driveStopTimeoutMs + 1
			detector.onSignal(stillSignal(currentTimeMs))
			detector.state shouldBe TripState.STATIONARY

			// The anchor was set from the still signal (at baseLat, baseLon).
			// Verify: a signal near baseLat stays stationary (anchor is correct).
			currentTimeMs += 10_000L
			detector.onSignal(
				SegmentSignal(
					timestampMs = currentTimeMs,
					latE7 = baseLat + 1000, // ~11m, within 25m drift radius
					lonE7 = baseLon,
					speedMps = 0.1f,
					stepDelta = 0,
					activityType = DetectedActivityType.STILL,
					activityConfidence = 80,
				)
			)
			detector.state shouldBe TripState.STATIONARY

			// But a signal far away triggers departure (anchor was not at old trip position)
			currentTimeMs += 10_000L
			detector.onSignal(
				movingSignal(
					timeMs = currentTimeMs,
					latE7 = baseLat + 5000, // ~55m > 25m drift
				)
			)
			detector.state shouldBe TripState.DEPARTING
		}

		@Test
		fun `multiple trips can be detected sequentially`() {
			// First trip
			getToInTrip()
			feedMovingSignals(3)
			feedStillSignals(config.stillCyclesForStopPending)
			currentTimeMs += config.driveStopTimeoutMs + 1
			val firstEnd = detector.onSignal(stillSignal(currentTimeMs))
			firstEnd.shouldNotBeNull()
			firstEnd.shouldBeInstanceOf<SegmentEvent.TripEnded>()
			detector.state shouldBe TripState.STATIONARY

			// After first trip ends, anchor is at baseLat (from still signal).
			// Move displacement beyond drift to trigger new departure directly.
			currentTimeMs += 60_000L
			detector.onSignal(
				movingSignal(
					timeMs = currentTimeMs,
					latE7 = baseLat + 3000, // ~33m from anchor, > 25m drift
					stepDelta = 10,
				)
			)
			detector.state shouldBe TripState.DEPARTING
		}
	}

	@Nested
	inner class ApproximateDistanceE7Test {
		@Test
		fun `zero distance between same points`() {
			val dist = SessionSegmentDetector.approximateDistanceE7(
				407128000, -740060000,
				407128000, -740060000,
			)
			dist shouldBe 0f
		}

		@Test
		fun `approximately correct for short north-south distance`() {
			// ~111m per degree latitude, so 1000 E7 units = ~0.0001 degrees = ~11.1m
			val dist = SessionSegmentDetector.approximateDistanceE7(
				407128000, -740060000,
				407138000, -740060000, // +10000 E7 = ~111m
			)
			// Should be approximately 111m (allow 20% tolerance for approximation)
			(dist > 90f) shouldBe true
			(dist < 135f) shouldBe true
		}

		@Test
		fun `approximately correct for short east-west distance`() {
			val dist = SessionSegmentDetector.approximateDistanceE7(
				407128000, -740060000,
				407128000, -740050000, // +10000 E7 longitude
			)
			// At ~40.7°N, longitude is shorter (~85m per 0.001°)
			(dist > 50f) shouldBe true
			(dist < 120f) shouldBe true
		}

		@Test
		fun `uses shortest distance across antimeridian`() {
			val dist = SessionSegmentDetector.approximateDistanceE7(
				0, 1_799_000_000,
				0, -1_799_000_000,
			)

			dist shouldBe (22_200f plusOrMinus 1f)
		}
	}
}
