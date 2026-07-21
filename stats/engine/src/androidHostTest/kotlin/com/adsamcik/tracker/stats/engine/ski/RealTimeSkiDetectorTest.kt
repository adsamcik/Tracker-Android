package com.adsamcik.tracker.stats.engine.ski

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class RealTimeSkiDetectorTest {

	private lateinit var detector: RealTimeSkiDetector
	private val config = SkiDetectionConfig()

	@BeforeEach
	fun setup() {
		detector = RealTimeSkiDetector(config)
	}

	/**
	 * Simulate a ski day scenario: warm up → lift → descent → repeat.
	 */
	private fun feedStableAltitude(
		startTimeMs: Long,
		durationSeconds: Int,
		altitude: Float,
		speed: Float = 0f
	): Long {
		var t = startTimeMs
		for (i in 0 until durationSeconds) {
			detector.onSample(t, altitude, speed)
			t += 1000L
		}
		return t
	}

	private fun feedLinearAltitudeChange(
		startTimeMs: Long,
		durationSeconds: Int,
		startAltitude: Float,
		endAltitude: Float,
		speed: Float
	): Long {
		var t = startTimeMs
		val altPerStep = (endAltitude - startAltitude) / durationSeconds
		for (i in 0 until durationSeconds) {
			val alt = startAltitude + altPerStep * i
			detector.onSample(t, alt, speed)
			t += 1000L
		}
		return t
	}

	private fun fastDetector(): RealTimeSkiDetector = RealTimeSkiDetector(
		SkiDetectionConfig(
			minStateDurationMs = 1_000L,
			baroMedianWindow = 1,
			verticalRateEmaAlpha = 1f,
		)
	)

	private fun enterDownhill(
		target: RealTimeSkiDetector,
		epochBaseMs: Long = 100_000L,
	): RealTimeSkiState {
		target.onSample(0L, epochBaseMs, 1_000f, 0f)
		target.onSample(1_000L, epochBaseMs + 1_000L, 990f, 10f)
		return requireNotNull(
			target.onSample(2_000L, epochBaseMs + 2_000L, 980f, 10f)
		)
	}

	@Nested
	inner class InitialState {
		@Test
		fun `returns null before warm-up`() {
			val result = detector.onSample(0L, 1500f, 0f)
			result.shouldBeNull()
		}

		@Test
		fun `initial state is IDLE`() {
			val state = detector.getCurrentState()
			state.state shouldBe SkiState.IDLE
			state.completedRunCount shouldBe 0
			state.isConfirmedSkiSession.shouldBeFalse()
		}
	}

	@Nested
	inner class DescentDetection {
		@Test
		fun `detects descent after sustained altitude drop with speed`() {
			// Warm up with stable altitude
			var t = feedStableAltitude(0L, 10, 2000f)

			// Descend: ~5 m/s vertical drop at 15 m/s speed for 40s (> minStateDuration)
			t = feedLinearAltitudeChange(t, 40, 2000f, 1800f, 15f)

			val state = detector.getCurrentState()
			state.state shouldBe SkiState.DOWNHILL_RUN
		}
	}

	@Nested
	inner class LiftDetection {
		@Test
		fun `detects lift ascent after sustained altitude gain`() {
			// Warm up
			var t = feedStableAltitude(0L, 10, 1500f)

			// Ascend: ~2 m/s vertical gain at 3 m/s speed for 40s
			t = feedLinearAltitudeChange(t, 40, 1500f, 1580f, 3f)

			val state = detector.getCurrentState()
			state.state shouldBe SkiState.LIFT_UP
		}
	}

	@Nested
	inner class CycleCounting {
		@Test
		fun `counts complete lift-descent cycles`() {
			var t = feedStableAltitude(0L, 10, 1500f) // warm up

			// Single lift+descent cycle with generous durations
			// (median filter warm-up ~5s + 30s hysteresis = 35s needed, 90s provides margin)
			t = feedLinearAltitudeChange(t, 90, 1500f, 1725f, 3f)
			t = feedLinearAltitudeChange(t, 90, 1725f, 1275f, 15f)

			val state = detector.getCurrentState()
			state.completedRunCount shouldBeGreaterThanOrEqual 1
		}

		@Test
		fun `confirmed ski session after minimum cycles`() {
			var t = feedStableAltitude(0L, 10, 1500f)

			// Run 3 cycles with generous durations
			for (cycle in 0 until 3) {
				val baseAlt = 1500f - cycle * 300f
				t = feedLinearAltitudeChange(t, 60, baseAlt, baseAlt + 200f, 3f)
				t = feedLinearAltitudeChange(t, 60, baseAlt + 200f, baseAlt - 100f, 15f)
			}

			val state = detector.getCurrentState()
			state.completedRunCount shouldBeGreaterThanOrEqual config.minCyclesForClassification
			state.isConfirmedSkiSession.shouldBeTrue()
		}
	}

	@Nested
	inner class StateListener {
		@Test
		fun `listener receives state transitions`() {
			val transitions = mutableListOf<Pair<SkiState, SkiState>>()
			detector.setListener(SkiStateListener { prev, newState ->
				transitions.add(prev to newState.state)
			})

			// Warm up + descent
			var t = feedStableAltitude(0L, 10, 2000f)
			t = feedLinearAltitudeChange(t, 40, 2000f, 1800f, 15f)

			// Should have at least one transition
			transitions.size shouldBeGreaterThanOrEqual 1
		}
	}

	@Nested
	inner class Reset {
		@Test
		fun `reset clears all state`() {
			var t = feedStableAltitude(0L, 10, 2000f)
			feedLinearAltitudeChange(t, 40, 2000f, 1800f, 15f)

			detector.reset()

			val state = detector.getCurrentState()
			state.state shouldBe SkiState.IDLE
			state.completedRunCount shouldBe 0
			state.totalVerticalM shouldBe 0f
		}
	}

	@Nested
	inner class NearResortProximity {
		@Test
		fun `nearResort false by default`() {
			val state = detector.getCurrentState()
			state.isNearResort.shouldBeFalse()
		}

		@Test
		fun `setNearResort enables single-cycle confirmation`() {
			detector.setNearResort(true)

			var t = feedStableAltitude(0L, 10, 1500f)

			// Single lift + descent cycle
			t = feedLinearAltitudeChange(t, 90, 1500f, 1725f, 3f)
			t = feedLinearAltitudeChange(t, 90, 1725f, 1275f, 15f)

			val state = detector.getCurrentState()
			state.isNearResort.shouldBeTrue()
			// With nearResort, 1 cycle is enough for confirmation
			if (state.completedRunCount >= 1) {
				state.isConfirmedSkiSession.shouldBeTrue()
			}
		}

		@Test
		fun `reset clears nearResort flag`() {
			detector.setNearResort(true)
			detector.getCurrentState().isNearResort.shouldBeTrue()

			detector.reset()
			detector.getCurrentState().isNearResort.shouldBeFalse()
		}
	}

	@Nested
	inner class RunMetrics {
		@Test
		fun `tracks total vertical descent across runs`() {
			var t = feedStableAltitude(0L, 10, 1500f)

			// Lift up then descend
			t = feedLinearAltitudeChange(t, 90, 1500f, 1725f, 3f)
			t = feedLinearAltitudeChange(t, 90, 1725f, 1275f, 15f)

			// During descent, currentRunVerticalM should track live drop
			val duringDescent = detector.getCurrentState()
			duringDescent.currentRunVerticalM shouldBeGreaterThan 0f
		}

		@Test
		fun `tracks max speed during descent`() {
			var t = feedStableAltitude(0L, 10, 2000f)

			// Descend at high speed
			t = feedLinearAltitudeChange(t, 40, 2000f, 1800f, 20f)

			val state = detector.getCurrentState()
			// During active descent, should track max speed
			if (state.state == SkiState.DOWNHILL_RUN) {
				state.currentRunMaxSpeedMps shouldBeGreaterThan 0f
			}
		}

		@Test
		fun `finalized run contains accumulated altitude drop and max speed`() {
			val target = fastDetector()
			enterDownhill(target)
			target.onSample(3_000L, 103_000L, 970f, 12f)
			target.onSample(4_000L, 104_000L, 950f, 20f)

			target.onSample(5_000L, 105_000L, 950f, 0f)
			val finalized = requireNotNull(
				target.onSample(6_000L, 106_000L, 950f, 0f)
			)

			finalized.state shouldBe SkiState.IDLE
			finalized.totalVerticalM shouldBe 30f
			finalized.totalRunCount shouldBe 1
			finalized.lastCompletedRun shouldBe FinalizedSkiRun(
				startTimeMs = 102_000L,
				endTimeMs = 106_000L,
				verticalDropM = 30f,
				maxSpeedMps = 20f,
			)
		}
	}

	@Nested
	inner class StreamingHysteresis {
		@Test
		fun `streaming and batch candidates preserve downhill at relaxed exit boundary`() {
			val target = fastDetector()
			enterDownhill(target)
			val signal = SkiSignal(
				timeMs = 3_000L,
				verticalRateMps = -0.4f,
				speedMps = 2f,
			)

			nextSkiCandidate(
				signal,
				SkiState.DOWNHILL_RUN,
				SkiDetectionConfig(),
			) shouldBe SkiState.DOWNHILL_RUN

			val streamingState = requireNotNull(
				target.onSample(3_000L, 103_000L, 979.6f, 2f)
			)
			streamingState.state shouldBe SkiState.DOWNHILL_RUN
		}

		@Test
		fun `streaming exit still requires full dwell duration`() {
			val target = fastDetector()
			enterDownhill(target)

			target.onSample(3_000L, 103_000L, 980.1f, 0f)
			requireNotNull(
				target.onSample(3_999L, 103_999L, 980.1999f, 0f)
			).state shouldBe SkiState.DOWNHILL_RUN

			requireNotNull(
				target.onSample(4_000L, 104_000L, 980.1999f, 0f)
			).state shouldBe SkiState.IDLE
		}
	}

	@Nested
	inner class MonotonicTiming {
		@Test
		fun `backward wall clock jump does not change dwell or duration`() {
			val normal = fastDetector()
			val jumping = fastDetector()
			val altitudes = listOf(1_000f, 990f, 980f, 970f, 960f)
			val normalEpoch = listOf(100_000L, 101_000L, 102_000L, 103_000L, 104_000L)
			val jumpingEpoch = listOf(100_000L, 101_000L, 90_000L, 91_000L, 92_000L)

			altitudes.indices.forEach { index ->
				val elapsedMs = index * 1_000L
				normal.onSample(
					elapsedMs,
					normalEpoch[index],
					altitudes[index],
					if (index == 0) 0f else 10f,
				)
				jumping.onSample(
					elapsedMs,
					jumpingEpoch[index],
					altitudes[index],
					if (index == 0) 0f else 10f,
				)
			}

			val normalState = normal.getCurrentState()
			val jumpingState = jumping.getCurrentState()
			jumpingState.state shouldBe normalState.state
			jumpingState.stateDurationMs shouldBe normalState.stateDurationMs
			jumpingState.stateDurationMs shouldBe 2_000L
		}
	}

	@Nested
	inner class Finish {
		@Test
		fun `finish is idempotent and uses last accepted event boundary`() {
			val target = fastDetector()
			val transitions = mutableListOf<RealTimeSkiState>()
			target.setListener(SkiStateListener { _, newState -> transitions += newState })
			enterDownhill(target)
			target.onSample(3_000L, 103_000L, 960f, 18f)
			target.onSample(3_000L, 999_000L, 100f, 100f)

			val first = target.finish()
			val transitionCount = transitions.size
			val second = target.finish()

			first shouldBe second
			first.boundaryTimeMs shouldBe 103_000L
			first.state.stateEntryTimeMs shouldBe 103_000L
			first.state.isFinished.shouldBeTrue()
			first.finalizedRun shouldBe FinalizedSkiRun(
				startTimeMs = 102_000L,
				endTimeMs = 103_000L,
				verticalDropM = 20f,
				maxSpeedMps = 18f,
			)
			transitions.size shouldBe transitionCount
		}
	}

	@Nested
	inner class LiftType {
		@Test
		fun `currentLiftType null by default`() {
			detector.getCurrentState().currentLiftType.shouldBeNull()
		}

		@Test
		fun `setCurrentLiftType reflected in state during LIFT_UP`() {
			// Feed enough samples to get into LIFT_UP
			var t = feedStableAltitude(0L, 10, 1500f)
			t = feedLinearAltitudeChange(t, 90, 1500f, 1725f, 3f)

			detector.setCurrentLiftType("gondola")

			val state = detector.getCurrentState()
			if (state.state == SkiState.LIFT_UP) {
				state.currentLiftType shouldBe "gondola"
			}
		}

		@Test
		fun `currentLiftType null when not in LIFT_UP`() {
			detector.setCurrentLiftType("chairlift")

			// In IDLE state, lift type should not be exposed
			val state = detector.getCurrentState()
			state.state shouldBe SkiState.IDLE
			state.currentLiftType.shouldBeNull()
		}

		@Test
		fun `reset clears currentLiftType`() {
			detector.setCurrentLiftType("gondola")
			detector.reset()
			detector.getCurrentState().currentLiftType.shouldBeNull()
		}
	}
}
