package com.adsamcik.tracker.stats.engine.ski

import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class StreamingVerticalRateCalculatorTest {

	private lateinit var calculator: StreamingVerticalRateCalculator

	@BeforeEach
	fun setup() {
		calculator = StreamingVerticalRateCalculator(medianWindowSize = 5, emaAlpha = 0.3f)
	}

	@Nested
	inner class WarmUp {
		@Test
		fun `returns null before window is filled`() {
			for (i in 0 until 4) {
				val result = calculator.onNewSample(
					TimestampedAltitude(i * 1000L, 1000f)
				)
				result.shouldBeNull()
			}
			calculator.isWarmedUp shouldBe false
		}

		@Test
		fun `returns value after window is filled`() {
			for (i in 0 until 5) {
				calculator.onNewSample(TimestampedAltitude(i * 1000L, 1000f))
			}
			calculator.isWarmedUp shouldBe true
			calculator.processedSamples shouldBe 5
		}
	}

	@Nested
	inner class VerticalRate {
		@Test
		fun `detects descent from decreasing altitude`() {
			// Fill window with stable altitude, then start descending
			val startAlt = 2000f
			val dropPerSecond = 5f // 5 m/s descent

			// Warm-up phase (stable)
			for (i in 0 until 5) {
				calculator.onNewSample(TimestampedAltitude(i * 1000L, startAlt))
			}

			// Descent phase
			var lastRate: Float? = null
			for (i in 5 until 15) {
				val alt = startAlt - (i - 4) * dropPerSecond
				lastRate = calculator.onNewSample(TimestampedAltitude(i * 1000L, alt))
			}

			lastRate.shouldNotBeNull()
			lastRate shouldBeLessThan -1f // Should detect descent
		}

		@Test
		fun `detects ascent from increasing altitude`() {
			val startAlt = 1500f
			val risePerSecond = 2f // 2 m/s ascent (chairlift)

			// Warm-up (stable)
			for (i in 0 until 5) {
				calculator.onNewSample(TimestampedAltitude(i * 1000L, startAlt))
			}

			// Ascent phase
			var lastRate: Float? = null
			for (i in 5 until 15) {
				val alt = startAlt + (i - 4) * risePerSecond
				lastRate = calculator.onNewSample(TimestampedAltitude(i * 1000L, alt))
			}

			lastRate.shouldNotBeNull()
			lastRate shouldBeGreaterThan 0.5f // Should detect ascent
		}

		@Test
		fun `stable altitude produces near-zero rate`() {
			for (i in 0 until 20) {
				calculator.onNewSample(TimestampedAltitude(i * 1000L, 1000f))
			}

			calculator.currentVerticalRate shouldBe 0f
		}
	}

	@Nested
	inner class Reset {
		@Test
		fun `reset clears all state`() {
			for (i in 0 until 10) {
				calculator.onNewSample(TimestampedAltitude(i * 1000L, 1000f + i * 5f))
			}
			calculator.isWarmedUp shouldBe true

			calculator.reset()

			calculator.isWarmedUp shouldBe false
			calculator.processedSamples shouldBe 0
			calculator.currentVerticalRate shouldBe 0f
		}
	}
}
