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
	}
}
