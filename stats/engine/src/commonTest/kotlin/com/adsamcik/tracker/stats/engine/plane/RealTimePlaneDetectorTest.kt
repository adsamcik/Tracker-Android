package com.adsamcik.tracker.stats.engine.plane

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class RealTimePlaneDetectorTest {

	private lateinit var detector: RealTimePlaneDetector
	private val config = PlaneDetectionConfig()

	private var t = 0L
	private var altitudeM = 0f

	@BeforeEach
	fun setup() {
		detector = RealTimePlaneDetector(config)
		t = 0L
		altitudeM = 0f
	}

	/** Feeds [durationSeconds] samples at 1Hz, advancing altitude by [verticalRateMps] per second. */
	private fun feedAltitude(
		durationSeconds: Int,
		verticalRateMps: Float,
		speedMps: Float = 0f,
		stepRatePerMin: Float = 0f,
	) {
		repeat(durationSeconds) {
			t += 1000L
			altitudeM += verticalRateMps
			detector.onSample(t, altitudeM, speedMps, stepRatePerMin)
		}
	}

	@Nested
	inner class InitialState {
		@Test
		fun `initial state is IDLE`() {
			val state = detector.getCurrentState()
			state.state shouldBe PlaneState.IDLE
			state.totalAirborneDurationMs shouldBe 0L
			state.isConfirmedFlight.shouldBeFalse()
		}
	}

	@Nested
	inner class ClimbDetection {
		@Test
		fun `detects climbing after sustained vertical rate`() {
			feedAltitude(durationSeconds = 40, verticalRateMps = 10f)
			detector.getCurrentState().state shouldBe PlaneState.CLIMBING
		}

		@Test
		fun `does not confirm a flight before the minimum cumulative airborne duration`() {
			feedAltitude(durationSeconds = 40, verticalRateMps = 10f)
			val state = detector.getCurrentState()
			state.state shouldBe PlaneState.CLIMBING
			state.isConfirmedFlight.shouldBeFalse()
		}

		@Test
		fun `stays IDLE when altitude does not change`() {
			feedAltitude(durationSeconds = 40, verticalRateMps = 0f)
			detector.getCurrentState().state shouldBe PlaneState.IDLE
		}
	}

	@Nested
	inner class CruiseDetection {
		@Test
		fun `graduates from CLIMBING to CRUISING once the climb rate flattens`() {
			feedAltitude(durationSeconds = 40, verticalRateMps = 10f)
			detector.getCurrentState().state shouldBe PlaneState.CLIMBING

			feedAltitude(durationSeconds = 60, verticalRateMps = 0f)
			detector.getCurrentState().state shouldBe PlaneState.CRUISING
		}

		@Test
		fun `sustained high GPS speed alone is enough to classify CRUISING`() {
			feedAltitude(durationSeconds = 40, verticalRateMps = 0f, speedMps = 60f)
			detector.getCurrentState().state shouldBe PlaneState.CRUISING
		}
	}

	@Nested
	inner class DescendDetection {
		@Test
		fun `detects descending after sustained negative vertical rate following cruise`() {
			feedAltitude(durationSeconds = 40, verticalRateMps = 10f)
			feedAltitude(durationSeconds = 60, verticalRateMps = 0f)
			feedAltitude(durationSeconds = 40, verticalRateMps = -10f)

			detector.getCurrentState().state shouldBe PlaneState.DESCENDING
		}
	}

	@Nested
	inner class WalkDetection {
		@Test
		fun `high step rate is classified as WALK`() {
			feedAltitude(durationSeconds = 40, verticalRateMps = 0f, stepRatePerMin = 100f)
			detector.getCurrentState().state shouldBe PlaneState.WALK
		}

		@Test
		fun `high step rate overrides a plausible climb rate`() {
			feedAltitude(durationSeconds = 40, verticalRateMps = 10f, stepRatePerMin = 100f)
			detector.getCurrentState().state shouldBe PlaneState.WALK
		}
	}

	@Nested
	inner class CumulativeTracking {
		@Test
		fun `accumulates airborne duration across climb, cruise and descend segments`() {
			feedAltitude(durationSeconds = 40, verticalRateMps = 10f)
			feedAltitude(durationSeconds = 60, verticalRateMps = 0f)
			feedAltitude(durationSeconds = 40, verticalRateMps = -10f)

			detector.getCurrentState().totalAirborneDurationMs shouldBeGreaterThan 100_000L
		}

		@Test
		fun `confirms the flight once cumulative airborne time crosses the threshold`() {
			// minFlightDurationForConfirmationMs default is 900_000L (15 min), plus the
			// minStateDurationMs (20s) hysteresis delay before CLIMBING is even confirmed.
			feedAltitude(durationSeconds = 40, verticalRateMps = 10f)
			feedAltitude(durationSeconds = 900, verticalRateMps = 0f)

			detector.getCurrentState().isConfirmedFlight.shouldBeTrue()
		}

		@Test
		fun `tracks max speed while airborne`() {
			feedAltitude(durationSeconds = 40, verticalRateMps = 10f, speedMps = 100f)
			feedAltitude(durationSeconds = 30, verticalRateMps = 0f, speedMps = 200f)
			feedAltitude(durationSeconds = 30, verticalRateMps = 0f, speedMps = 150f)

			detector.getCurrentState().maxSpeedMps shouldBe 200f
		}
	}

	@Nested
	inner class ListenerNotification {
		@Test
		fun `listener is notified on state transitions`() {
			var transitions = 0
			detector.setListener { _, _ -> transitions++ }

			feedAltitude(durationSeconds = 40, verticalRateMps = 10f)

			transitions shouldBeGreaterThan 0
		}
	}

	@Nested
	inner class Reset {
		@Test
		fun `reset clears all accumulated state`() {
			feedAltitude(durationSeconds = 40, verticalRateMps = 10f)
			feedAltitude(durationSeconds = 900, verticalRateMps = 0f)
			detector.getCurrentState().isConfirmedFlight.shouldBeTrue()

			detector.reset()

			val state = detector.getCurrentState()
			state.state shouldBe PlaneState.IDLE
			state.totalAirborneDurationMs shouldBe 0L
			state.isConfirmedFlight.shouldBeFalse()
		}
	}
}
