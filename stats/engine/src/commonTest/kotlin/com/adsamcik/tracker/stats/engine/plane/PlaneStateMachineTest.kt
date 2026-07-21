package com.adsamcik.tracker.stats.engine.plane

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class PlaneStateMachineTest {

	private val machine = PlaneStateMachine()

	private fun generateSignals(
		durationMs: Long,
		intervalMs: Long = 1000L,
		startTimeMs: Long = 0L,
		verticalRate: Float,
		speed: Float = 0f,
		stepRate: Float = 0f,
		cruiseEvidence: CruiseEvidence = CruiseEvidence.UNKNOWN,
	): List<PlaneSignal> {
		val count = (durationMs / intervalMs).toInt()
		return (0 until count).map { i ->
			PlaneSignal(
				timeMs = startTimeMs + i * intervalMs,
				verticalRateMps = verticalRate,
				speedMps = speed,
				stepRatePerMin = stepRate,
				cruiseEvidence = cruiseEvidence,
			)
		}
	}

	@Nested
	inner class FullFlightScenario {

		@Test
		fun `full flight - produces expected segments and total airborne duration`() {
			var t = 0L
			val signals = mutableListOf<PlaneSignal>()

			// IDLE (parked, taxiing) 5 min
			signals += generateSignals(300_000L, startTimeMs = t, verticalRate = 0f)
			t += 300_000L

			// CLIMBING 8 min
			signals += generateSignals(480_000L, startTimeMs = t, verticalRate = 5f)
			t += 480_000L

			// CRUISING (flattened, cabin pressure plateau) 60 min
			signals += generateSignals(
				3_600_000L,
				startTimeMs = t,
				verticalRate = 0f,
				cruiseEvidence = CruiseEvidence.QUALIFIED_CLIMB,
			)
			t += 3_600_000L

			// DESCENDING 8 min
			signals += generateSignals(480_000L, startTimeMs = t, verticalRate = -5f)
			t += 480_000L

			// WALK in the terminal 2 min
			signals += generateSignals(120_000L, startTimeMs = t, verticalRate = 0f, stepRate = 100f)
			t += 120_000L

			val segments = machine.process(signals)

			segments.map { it.state } shouldBe listOf(
				PlaneState.IDLE,
				PlaneState.CLIMBING,
				PlaneState.CRUISING,
				PlaneState.DESCENDING,
				PlaneState.WALK,
			)
			machine.totalAirborneDurationMs(segments) shouldBe 480_000L + 3_600_000L + 480_000L
		}
	}

	@Nested
	inner class ClassifySignal {

		@Test
		fun `vertical rate above climb enter threshold classifies as CLIMBING`() {
			machine.classifySignal(PlaneSignal(0L, verticalRateMps = 5f)) shouldBe PlaneState.CLIMBING
		}

		@Test
		fun `vertical rate below descend enter threshold classifies as DESCENDING`() {
			machine.classifySignal(PlaneSignal(0L, verticalRateMps = -5f)) shouldBe PlaneState.DESCENDING
		}

		@Test
		fun `sustained high GPS speed alone classifies as CRUISING`() {
			machine.classifySignal(
				PlaneSignal(0L, verticalRateMps = 0f, speedMps = 60f),
			) shouldBe PlaneState.CRUISING
		}

		@Test
		fun `high step rate classifies as WALK even with a plausible climb rate`() {
			machine.classifySignal(
				PlaneSignal(0L, verticalRateMps = 5f, stepRatePerMin = 100f),
			) shouldBe PlaneState.WALK
		}

		@Test
		fun `flat vertical rate, low speed and no steps classifies as IDLE`() {
			machine.classifySignal(PlaneSignal(0L, verticalRateMps = 0f)) shouldBe PlaneState.IDLE
		}

		@Test
		fun `classifySignal never returns CRUISING from a flat isolated sample`() {
			// Without a preceding climb or corroborating GPS speed, a flat reading is
			// indistinguishable from "on the ground" - it must fall back to IDLE, never CRUISING.
			machine.classifySignal(PlaneSignal(0L, verticalRateMps = 0.1f)) shouldBe PlaneState.IDLE
		}
	}

	@Nested
	inner class Hysteresis {

		@Test
		fun `CLIMBING sticks through a brief dip above the exit threshold`() {
			val config = PlaneDetectionConfig(climbEnterVerticalRateMps = 3.0f, climbExitVerticalRateMps = 1.5f)
			val m = PlaneStateMachine(config)
			m.classifyWithHysteresis(
				PlaneSignal(0L, verticalRateMps = 2.0f),
				currentState = PlaneState.CLIMBING,
			) shouldBe PlaneState.CLIMBING
		}

		@Test
		fun `CLIMBING graduates to CRUISING once the climb rate flattens`() {
			machine.classifyWithHysteresis(
				PlaneSignal(0L, verticalRateMps = 0.5f),
				currentState = PlaneState.CLIMBING,
			) shouldBe PlaneState.CRUISING
		}

		@Test
		fun `CLIMBING transitions directly to DESCENDING on a sharp reversal`() {
			machine.classifyWithHysteresis(
				PlaneSignal(0L, verticalRateMps = -5f),
				currentState = PlaneState.CLIMBING,
			) shouldBe PlaneState.DESCENDING
		}

		@Test
		fun `CLIMBING transitions to WALK when step rate spikes`() {
			machine.classifyWithHysteresis(
				PlaneSignal(0L, verticalRateMps = 0.5f, stepRatePerMin = 100f),
				currentState = PlaneState.CLIMBING,
			) shouldBe PlaneState.WALK
		}

		@Test
		fun `qualified CRUISING stays CRUISING through a flat sample`() {
			machine.classifyWithHysteresis(
				PlaneSignal(
					timeMs = 0L,
					verticalRateMps = 0f,
					cruiseEvidence = CruiseEvidence.QUALIFIED_CLIMB,
				),
				currentState = PlaneState.CRUISING,
			) shouldBe PlaneState.CRUISING
		}

		@Test
		fun `speed-only CRUISING closes on sustained flat stationary signals`() {
			val signals = buildList {
				add(PlaneSignal(timeMs = 0L, verticalRateMps = 0f, speedMps = 60f))
				addAll(
					generateSignals(
						durationMs = 60_000L,
						startTimeMs = 1_000L,
						verticalRate = 0f,
					),
				)
			}

			machine.process(signals).last().state shouldBe PlaneState.IDLE
		}

		@Test
		fun `CRUISING exits to DESCENDING once vertical rate drops`() {
			machine.classifyWithHysteresis(
				PlaneSignal(0L, verticalRateMps = -5f),
				currentState = PlaneState.CRUISING,
			) shouldBe PlaneState.DESCENDING
		}

		@Test
		fun `CRUISING exits to WALK on a step rate spike`() {
			machine.classifyWithHysteresis(
				PlaneSignal(0L, verticalRateMps = 0f, stepRatePerMin = 100f),
				currentState = PlaneState.CRUISING,
			) shouldBe PlaneState.WALK
		}

		@Test
		fun `DESCENDING sticks through a brief dip above the exit threshold`() {
			val config = PlaneDetectionConfig(descendEnterVerticalRateMps = -3.0f, descendExitVerticalRateMps = -1.5f)
			val m = PlaneStateMachine(config)
			m.classifyWithHysteresis(
				PlaneSignal(0L, verticalRateMps = -2.0f),
				currentState = PlaneState.DESCENDING,
			) shouldBe PlaneState.DESCENDING
		}

		@Test
		fun `DESCENDING exits once vertical rate rises above the exit threshold`() {
			val config = PlaneDetectionConfig(descendEnterVerticalRateMps = -3.0f, descendExitVerticalRateMps = -1.5f)
			val m = PlaneStateMachine(config)
			m.classifyWithHysteresis(
				PlaneSignal(0L, verticalRateMps = -0.5f),
				currentState = PlaneState.DESCENDING,
			) shouldBe PlaneState.IDLE
		}

		@Test
		fun `WALK sticks while step rate remains high`() {
			machine.classifyWithHysteresis(
				PlaneSignal(0L, verticalRateMps = 0f, stepRatePerMin = 80f),
				currentState = PlaneState.WALK,
			) shouldBe PlaneState.WALK
		}

		@Test
		fun `WALK exits once step rate drops`() {
			machine.classifyWithHysteresis(
				PlaneSignal(0L, verticalRateMps = 0f, stepRatePerMin = 0f),
				currentState = PlaneState.WALK,
			) shouldBe PlaneState.IDLE
		}

		@Test
		fun `IDLE never sticks - always reclassified fresh`() {
			machine.classifyWithHysteresis(
				PlaneSignal(0L, verticalRateMps = 5f),
				currentState = PlaneState.IDLE,
			) shouldBe PlaneState.CLIMBING
		}
	}

	@Nested
	inner class MinDurationEnforcement {

		@Test
		fun `short reversal blip is absorbed into the surrounding climb segment`() {
			var t = 0L
			val signals = mutableListOf<PlaneSignal>()
			signals += generateSignals(300_000L, startTimeMs = t, verticalRate = 5f)
			t += 300_000L
			// A brief 5s "descending" blip (e.g. turbulence) shorter than minStateDurationMs.
			signals += generateSignals(5_000L, startTimeMs = t, verticalRate = -5f)
			t += 5_000L
			signals += generateSignals(300_000L, startTimeMs = t, verticalRate = 5f)

			val segments = machine.process(signals)

			segments shouldHaveSize 1
			segments.first().state shouldBe PlaneState.CLIMBING
		}
	}

	@Nested
	inner class EmptyInput {

		@Test
		fun `empty signal list produces no segments`() {
			machine.process(emptyList()) shouldHaveSize 0
		}
	}
}
