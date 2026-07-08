package com.adsamcik.tracker.stats.engine.sailing

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SailingStateMachineTest {

	private val machine = SailingStateMachine()

	private fun generateSignals(
		durationMs: Long,
		intervalMs: Long = 1000L,
		startTimeMs: Long = 0L,
		speed: Float,
		stepRate: Float = 0f,
	): List<SailingSignal> {
		val count = (durationMs / intervalMs).toInt()
		return (0 until count).map { i ->
			SailingSignal(
				timeMs = startTimeMs + i * intervalMs,
				speedMps = speed,
				stepRatePerMin = stepRate,
			)
		}
	}

	@Nested
	inner class FullSailingDayScenario {

		@Test
		fun `full sailing day - produces expected segments and total sailing duration`() {
			var t = 0L
			val signals = mutableListOf<SailingSignal>()

			// IDLE (moored) 5 min
			signals += generateSignals(300_000L, startTimeMs = t, speed = 0f)
			t += 300_000L

			// SAILING 20 min at 5 m/s (~10 knots)
			signals += generateSignals(1_200_000L, startTimeMs = t, speed = 5f)
			t += 1_200_000L

			// IDLE (becalmed) 3 min
			signals += generateSignals(180_000L, startTimeMs = t, speed = 0f)
			t += 180_000L

			// WALK on the dock 2 min
			signals += generateSignals(120_000L, startTimeMs = t, speed = 1.2f, stepRate = 100f)
			t += 120_000L

			val segments = machine.process(signals)

			segments.map { it.state } shouldBe listOf(
				SailingState.IDLE,
				SailingState.SAILING,
				SailingState.IDLE,
				SailingState.WALK,
			)
			machine.totalSailingDurationMs(segments) shouldBe 1_200_000L
		}
	}

	@Nested
	inner class ClassifySignal {

		@Test
		fun `speed within sailing band and low step rate classifies as SAILING`() {
			machine.classifySignal(SailingSignal(0L, speedMps = 5f)) shouldBe SailingState.SAILING
		}

		@Test
		fun `speed below the sailing threshold classifies as IDLE`() {
			machine.classifySignal(SailingSignal(0L, speedMps = 0.2f)) shouldBe SailingState.IDLE
		}

		@Test
		fun `speed above the sailing ceiling classifies as IDLE (not sailing)`() {
			machine.classifySignal(SailingSignal(0L, speedMps = 20f)) shouldBe SailingState.IDLE
		}

		@Test
		fun `high step rate classifies as WALK regardless of speed`() {
			machine.classifySignal(SailingSignal(0L, speedMps = 5f, stepRatePerMin = 100f)) shouldBe SailingState.WALK
		}

		@Test
		fun `zero speed and zero steps classifies as IDLE`() {
			machine.classifySignal(SailingSignal(0L, speedMps = 0f)) shouldBe SailingState.IDLE
		}
	}

	@Nested
	inner class Hysteresis {

		@Test
		fun `SAILING sticks through a brief speed dip above the exit threshold`() {
			val config = SailingDetectionConfig(sailingEnterMinSpeedMps = 0.75f, sailingExitMinSpeedMps = 0.4f)
			val m = SailingStateMachine(config)
			// Below the ENTER threshold but above the EXIT threshold — should stay SAILING.
			m.classifyWithHysteresis(
				SailingSignal(0L, speedMps = 0.5f),
				currentState = SailingState.SAILING,
			) shouldBe SailingState.SAILING
		}

		@Test
		fun `SAILING exits to IDLE once speed drops below the exit threshold`() {
			val config = SailingDetectionConfig(sailingEnterMinSpeedMps = 0.75f, sailingExitMinSpeedMps = 0.4f)
			val m = SailingStateMachine(config)
			m.classifyWithHysteresis(
				SailingSignal(0L, speedMps = 0.1f),
				currentState = SailingState.SAILING,
			) shouldBe SailingState.IDLE
		}

		@Test
		fun `IDLE never sticks - always reclassified fresh`() {
			machine.classifyWithHysteresis(
				SailingSignal(0L, speedMps = 5f),
				currentState = SailingState.IDLE,
			) shouldBe SailingState.SAILING
		}
	}

	@Nested
	inner class MinDurationEnforcement {

		@Test
		fun `short blip is absorbed into the surrounding segment`() {
			var t = 0L
			val signals = mutableListOf<SailingSignal>()
			signals += generateSignals(300_000L, startTimeMs = t, speed = 5f)
			t += 300_000L
			// A brief 5s "idle" blip (e.g. a wave trough) shorter than minStateDurationMs.
			signals += generateSignals(5_000L, startTimeMs = t, speed = 0f)
			t += 5_000L
			signals += generateSignals(300_000L, startTimeMs = t, speed = 5f)

			val segments = machine.process(signals)

			segments shouldHaveSize 1
			segments.first().state shouldBe SailingState.SAILING
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
