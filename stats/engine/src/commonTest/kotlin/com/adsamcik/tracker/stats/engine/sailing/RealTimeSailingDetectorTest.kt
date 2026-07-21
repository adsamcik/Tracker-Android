package com.adsamcik.tracker.stats.engine.sailing

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class RealTimeSailingDetectorTest {

	private lateinit var detector: RealTimeSailingDetector
	private val config = SailingDetectionConfig()

	@BeforeEach
	fun setup() {
		detector = RealTimeSailingDetector(config)
	}

	private fun feedSpeed(
		startTimeMs: Long,
		durationSeconds: Int,
		speed: Float,
		distanceDeltaPerSampleM: Float = 0f,
		stepRate: Float = 0f,
		motionContextAvailable: Boolean = true,
	): Long {
		var t = startTimeMs
		for (i in 0 until durationSeconds) {
			detector.onSample(
				timeMs = t,
				speedMps = speed,
				distanceDeltaM = distanceDeltaPerSampleM,
				stepRatePerMin = stepRate,
				motionContextAvailable = motionContextAvailable,
			)
			t += 1000L
		}
		return t
	}

	@Nested
	inner class InitialState {
		@Test
		fun `initial state is IDLE`() {
			val state = detector.getCurrentState()
			state.state shouldBe SailingState.IDLE
			state.totalSailingDurationMs shouldBe 0L
			state.isConfirmedSailingSession.shouldBeFalse()
		}
	}

	@Nested
	inner class SailingDetection {
		@Test
		fun `detects sailing after sustained speed in the sailing band`() {
			feedSpeed(0L, 60, speed = 5f)
			detector.getCurrentState().state shouldBe SailingState.SAILING
		}

		@Test
		fun `does not confirm a session before the minimum cumulative duration`() {
			feedSpeed(0L, 60, speed = 5f)
			val state = detector.getCurrentState()
			state.state shouldBe SailingState.SAILING
			state.isConfirmedSailingSession.shouldBeFalse()
		}

		@Test
		fun `confirms the session once cumulative sailing time crosses the threshold`() {
			// minSailingDurationForConfirmationMs default is 600_000L (10 min), plus the
			// minStateDurationMs (45s) hysteresis delay before SAILING is even confirmed.
			feedSpeed(0L, 650, speed = 5f)
			detector.getCurrentState().isConfirmedSailingSession.shouldBeTrue()
		}

		@Test
		fun `stays IDLE when speed never enters the sailing band`() {
			feedSpeed(0L, 60, speed = 0.1f)
			detector.getCurrentState().state shouldBe SailingState.IDLE
		}

		@Test
		fun `stays IDLE when speed exceeds the sailing ceiling - eg a car, not a boat`() {
			feedSpeed(0L, 60, speed = 25f)
			detector.getCurrentState().state shouldBe SailingState.IDLE
		}

		@Test
		fun `speed-only overlap never confirms a sailing session without corroborating context`() {
			feedSpeed(0L, 650, speed = 5f, motionContextAvailable = false)

			val state = detector.getCurrentState()
			state.state shouldBe SailingState.IDLE
			state.detectionReason shouldBe SailingDetectionReason.BOAT_LIKE_MOTION
			state.isConfirmedSailingSession.shouldBeFalse()
		}
	}

	@Nested
	inner class WalkDetection {
		@Test
		fun `high step rate is classified as WALK even at plausible sailing speed`() {
			feedSpeed(0L, 60, speed = 1.5f, stepRate = 100f)
			detector.getCurrentState().state shouldBe SailingState.WALK
		}
	}

	@Nested
	inner class CumulativeTracking {
		@Test
		fun `accumulates sailing duration across separate segments`() {
			// Sail 5 min
			var t = feedSpeed(0L, 300, speed = 5f)
			// Moor (idle) for 2 min — long enough to confirm the transition
			t = feedSpeed(t, 120, speed = 0f)
			// Sail again for 5 min
			feedSpeed(t, 300, speed = 5f)

			val state = detector.getCurrentState()
			// Roughly 5 + 5 minutes of sailing accumulated across the two segments.
			state.totalSailingDurationMs shouldBeGreaterThan 500_000L
		}

		@Test
		fun `tracks max speed while sailing`() {
			feedSpeed(0L, 30, speed = 3f)
			feedSpeed(30_000L, 30, speed = 7f)
			feedSpeed(60_000L, 30, speed = 4f)

			detector.getCurrentState().maxSpeedMps shouldBe 7f
		}

		@Test
		fun `accumulates distance while sailing`() {
			// First 45s are the min-state-duration hysteresis delay before SAILING is confirmed;
			// the following 60 samples (i=45..104) accrue distance while confirmed SAILING.
			feedSpeed(0L, 105, speed = 5f, distanceDeltaPerSampleM = 5f)
			detector.getCurrentState().totalSailingDistanceM shouldBe 300f
		}
	}

	@Nested
	inner class ListenerNotification {
		@Test
		fun `listener is notified on state transitions`() {
			var transitions = 0
			detector.setListener { _, _ -> transitions++ }

			feedSpeed(0L, 60, speed = 5f)

			transitions shouldBeGreaterThan 0
		}

		@Nested
		inner class SampleGapsAndClock {
			@Test
			fun `large sample gap closes sailing and does not bridge duration or distance`() {
				val gapConfig = SailingDetectionConfig(
					minStateDurationMs = 0L,
					maxSampleGapMs = 30_000L,
					speedMedianWindow = 1,
				)
				detector = RealTimeSailingDetector(gapConfig)

				detector.onSample(1_000L, 5f, 10f, motionContextAvailable = true)
				detector.onSample(11_000L, 5f, 10f, motionContextAvailable = true)
				val stateAfterGap = detector.onSample(100_000L, 5f, 500f, motionContextAvailable = true)

				stateAfterGap.state shouldBe SailingState.IDLE
				stateAfterGap.detectionReason shouldBe SailingDetectionReason.UNKNOWN_SAMPLE_GAP
				stateAfterGap.totalSailingDurationMs shouldBe 10_000L
				stateAfterGap.totalSailingDistanceM shouldBe 20f

				detector.onSample(101_000L, 5f, 10f, motionContextAvailable = true)
					.totalSailingDurationMs shouldBe 10_000L
			}

			@Test
			fun `monotonic sample time keeps duration correct when wall clock moves backward`() {
				val monotonicConfig = SailingDetectionConfig(minStateDurationMs = 0L, speedMedianWindow = 1)
				detector = RealTimeSailingDetector(monotonicConfig)

				// The associated wall times could be 100_000 then 90_000; only monotonic times enter
				// the detector and therefore the ten-second sailing interval remains valid.
				detector.onSample(1_000L, 5f, motionContextAvailable = true)
				val state = detector.onSample(11_000L, 5f, motionContextAvailable = true)

				state.totalSailingDurationMs shouldBe 10_000L
			}
		}
	}

	@Nested
	inner class Reset {
		@Test
		fun `reset clears all accumulated state`() {
			feedSpeed(0L, 650, speed = 5f)
			detector.getCurrentState().isConfirmedSailingSession.shouldBeTrue()

			detector.reset()

			val state = detector.getCurrentState()
			state.state shouldBe SailingState.IDLE
			state.totalSailingDurationMs shouldBe 0L
			state.totalSailingDistanceM shouldBe 0f
			state.isConfirmedSailingSession.shouldBeFalse()
		}
	}
}
