package com.adsamcik.tracker.stats.engine.ski

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class SkiStateMachineTest {

	private val machine = SkiStateMachine()

	private fun generateSignals(
		durationMs: Long,
		intervalMs: Long = 1000L,
		startTimeMs: Long = 0L,
		verticalRate: Float,
		speed: Float,
		stepRate: Float = 0f,
	): List<SkiSignal> {
		val count = (durationMs / intervalMs).toInt()
		return (0 until count).map { i ->
			SkiSignal(
				timeMs = startTimeMs + i * intervalMs,
				verticalRateMps = verticalRate,
				speedMps = speed,
				stepRatePerMin = stepRate,
			)
		}
	}

	@Nested
	inner class FullSkiDayScenario {

		@Test
		fun `fullSkiDay - produces expected segments and cycle count`() {
			var t = 0L
			val signals = mutableListOf<SkiSignal>()

			// IDLE 5 min
			signals += generateSignals(300_000L, startTimeMs = t, verticalRate = 0f, speed = 0f)
			t += 300_000L

			// LIFT 8 min (ascending at 1.5 m/s, speed 3 m/s)
			signals += generateSignals(480_000L, startTimeMs = t, verticalRate = 1.5f, speed = 3f)
			t += 480_000L

			// DOWNHILL 3 min (descending at -2 m/s, speed 8 m/s)
			signals += generateSignals(180_000L, startTimeMs = t, verticalRate = -2f, speed = 8f)
			t += 180_000L

			// IDLE 2 min
			signals += generateSignals(120_000L, startTimeMs = t, verticalRate = 0f, speed = 0f)
			t += 120_000L

			// LIFT 8 min
			signals += generateSignals(480_000L, startTimeMs = t, verticalRate = 1.5f, speed = 3f)
			t += 480_000L

			// DOWNHILL 3 min
			signals += generateSignals(180_000L, startTimeMs = t, verticalRate = -2f, speed = 8f)
			t += 180_000L

			// IDLE 5 min
			signals += generateSignals(300_000L, startTimeMs = t, verticalRate = 0f, speed = 0f)

			val segments = machine.process(signals)

			// Expect: IDLE, LIFT_UP, DOWNHILL_RUN, IDLE, LIFT_UP, DOWNHILL_RUN, IDLE
			segments shouldHaveSize 7
			segments[0].state shouldBe SkiState.IDLE
			segments[1].state shouldBe SkiState.LIFT_UP
			segments[2].state shouldBe SkiState.DOWNHILL_RUN
			segments[3].state shouldBe SkiState.IDLE
			segments[4].state shouldBe SkiState.LIFT_UP
			segments[5].state shouldBe SkiState.DOWNHILL_RUN
			segments[6].state shouldBe SkiState.IDLE

			machine.countSkiCycles(segments) shouldBe 2
		}
	}

	@Nested
	inner class InsufficientData {

		@Test
		fun `singleRunOnly - one lift and one descent yields 1 cycle`() {
			var t = 0L
			val signals = mutableListOf<SkiSignal>()

			signals += generateSignals(480_000L, startTimeMs = t, verticalRate = 1.5f, speed = 3f)
			t += 480_000L
			signals += generateSignals(180_000L, startTimeMs = t, verticalRate = -2f, speed = 8f)

			val segments = machine.process(signals)
			machine.countSkiCycles(segments) shouldBe 1
		}

		@Test
		fun `walkingOnly - all walking signals produce WALK segments`() {
			val signals = generateSignals(
				durationMs = 600_000L,
				verticalRate = 0f,
				speed = 1.5f,
				stepRate = 80f,
			)
			val segments = machine.process(signals)

			segments.forEach { segment ->
				segment.state shouldBe SkiState.WALK
			}
		}

		@Test
		fun `emptySignals - returns empty result`() {
			val segments = machine.process(emptyList())
			segments.shouldBeEmpty()
		}
	}

	@Nested
	inner class StateClassification {

		@Test
		fun `downhillClassification - descending fast classifies as DOWNHILL_RUN`() {
			val signal = SkiSignal(
				timeMs = 0L,
				verticalRateMps = -2f,
				speedMps = 10f,
			)
			machine.classifySignal(signal) shouldBe SkiState.DOWNHILL_RUN
		}

		@Nested
		inner class HysteresisBoundaries {
			@ParameterizedTest
			@MethodSource("com.adsamcik.tracker.stats.engine.ski.SkiStateMachineTest#hysteresisCases")
			fun `shared candidate transition honors every exit boundary`(
				confirmedState: SkiState,
				signal: SkiSignal,
				expected: SkiState,
			) {
				nextSkiCandidate(signal, confirmedState, SkiDetectionConfig()) shouldBe expected
				machine.classifyWithHysteresis(signal, confirmedState) shouldBe expected
			}
		}

		@Test
		fun `liftClassification - ascending slowly classifies as LIFT_UP`() {
			val signal = SkiSignal(
				timeMs = 0L,
				verticalRateMps = 1f,
				speedMps = 3f,
			)
			machine.classifySignal(signal) shouldBe SkiState.LIFT_UP
		}

		@Test
		fun `idleClassification - stationary classifies as IDLE`() {
			val signal = SkiSignal(
				timeMs = 0L,
				verticalRateMps = 0f,
				speedMps = 0f,
			)
			machine.classifySignal(signal) shouldBe SkiState.IDLE
		}

		@Test
		fun `walkClassification - stepping with low vertical rate classifies as WALK`() {
			val signal = SkiSignal(
				timeMs = 0L,
				verticalRateMps = 0f,
				speedMps = 1.5f,
				stepRatePerMin = 80f,
			)
			machine.classifySignal(signal) shouldBe SkiState.WALK
		}
	}

	@Nested
	inner class DurationEnforcement {

		@Test
		fun `shortSegmentsAbsorbed - 10s idle between downhills is absorbed and merged`() {
			var t = 0L
			val signals = mutableListOf<SkiSignal>()

			// DOWNHILL 2 min
			signals += generateSignals(120_000L, startTimeMs = t, verticalRate = -2f, speed = 8f)
			t += 120_000L

			// Brief IDLE 10s (below 30s minStateDuration)
			signals += generateSignals(10_000L, startTimeMs = t, verticalRate = 0f, speed = 0f)
			t += 10_000L

			// DOWNHILL 2 min
			signals += generateSignals(120_000L, startTimeMs = t, verticalRate = -2f, speed = 8f)

			val segments = machine.process(signals)

			// The 10s idle is absorbed, then same-state merge combines the result
			segments shouldHaveSize 1
			segments[0].state shouldBe SkiState.DOWNHILL_RUN
		}

		@Test
		fun `longSegmentsPreserved - 60s idle between downhills is kept`() {
			var t = 0L
			val signals = mutableListOf<SkiSignal>()

			// DOWNHILL 2 min
			signals += generateSignals(120_000L, startTimeMs = t, verticalRate = -2f, speed = 8f)
			t += 120_000L

			// Long IDLE 60s (above 30s minStateDuration)
			signals += generateSignals(60_000L, startTimeMs = t, verticalRate = 0f, speed = 0f)
			t += 60_000L

			// DOWNHILL 2 min
			signals += generateSignals(120_000L, startTimeMs = t, verticalRate = -2f, speed = 8f)

			val segments = machine.process(signals)

			segments shouldHaveSize 3
			segments[0].state shouldBe SkiState.DOWNHILL_RUN
			segments[1].state shouldBe SkiState.IDLE
			segments[2].state shouldBe SkiState.DOWNHILL_RUN
		}
	}

	@Nested
	inner class CycleCounting {

		@Test
		fun `multipleCycles - 5 lift and descent pairs yields 5 cycles`() {
			var t = 0L
			val signals = mutableListOf<SkiSignal>()

			repeat(5) {
				signals += generateSignals(480_000L, startTimeMs = t, verticalRate = 1.5f, speed = 3f)
				t += 480_000L
				signals += generateSignals(180_000L, startTimeMs = t, verticalRate = -2f, speed = 8f)
				t += 180_000L
			}

			val segments = machine.process(signals)
			machine.countSkiCycles(segments) shouldBe 5
		}

		@Test
		fun `liftWithoutDescent - multiple lifts with no descents yields 0 cycles`() {
			var t = 0L
			val signals = mutableListOf<SkiSignal>()

			repeat(3) {
				signals += generateSignals(480_000L, startTimeMs = t, verticalRate = 1.5f, speed = 3f)
				t += 480_000L
				signals += generateSignals(120_000L, startTimeMs = t, verticalRate = 0f, speed = 0f)
				t += 120_000L
			}

			val segments = machine.process(signals)
			machine.countSkiCycles(segments) shouldBe 0
		}

		@Test
		fun `descentWithoutLift - multiple descents with no lifts yields 0 cycles`() {
			var t = 0L
			val signals = mutableListOf<SkiSignal>()

			repeat(3) {
				signals += generateSignals(180_000L, startTimeMs = t, verticalRate = -2f, speed = 8f)
				t += 180_000L
				signals += generateSignals(120_000L, startTimeMs = t, verticalRate = 0f, speed = 0f)
				t += 120_000L
			}

			val segments = machine.process(signals)
			machine.countSkiCycles(segments) shouldBe 0
		}
	}

	companion object {
		@JvmStatic
		fun hysteresisCases(): List<Arguments> = listOf(
				Arguments.of(
					SkiState.DOWNHILL_RUN,
					SkiSignal(0L, verticalRateMps = -0.5f, speedMps = 0f),
					SkiState.DOWNHILL_RUN,
				),
				Arguments.of(
					SkiState.DOWNHILL_RUN,
					SkiSignal(0L, verticalRateMps = 0f, speedMps = 1.5f),
					SkiState.DOWNHILL_RUN,
				),
				Arguments.of(
					SkiState.DOWNHILL_RUN,
					SkiSignal(0L, verticalRateMps = 0.01f, speedMps = 1.49f),
					SkiState.IDLE,
				),
				Arguments.of(
					SkiState.LIFT_UP,
					SkiSignal(0L, verticalRateMps = 0.3f, speedMps = 8f),
					SkiState.LIFT_UP,
				),
				Arguments.of(
					SkiState.LIFT_UP,
					SkiSignal(0L, verticalRateMps = 0.29f, speedMps = 8f),
					SkiState.IDLE,
				),
				Arguments.of(
					SkiState.LIFT_UP,
					SkiSignal(0L, verticalRateMps = 0.3f, speedMps = 8.01f),
					SkiState.IDLE,
				),
				Arguments.of(
					SkiState.WALK,
					SkiSignal(0L, verticalRateMps = 0f, speedMps = 1f, stepRatePerMin = 60f),
					SkiState.WALK,
				),
				Arguments.of(
					SkiState.WALK,
					SkiSignal(0L, verticalRateMps = 0f, speedMps = 0f, stepRatePerMin = 59.99f),
					SkiState.IDLE,
				),
				Arguments.of(
					SkiState.IDLE,
					SkiSignal(0L, verticalRateMps = -1f, speedMps = 3f),
					SkiState.DOWNHILL_RUN,
				),
				Arguments.of(
					SkiState.IDLE,
					SkiSignal(0L, verticalRateMps = 0.5f, speedMps = 8f),
					SkiState.LIFT_UP,
				),
		)
	}
}
