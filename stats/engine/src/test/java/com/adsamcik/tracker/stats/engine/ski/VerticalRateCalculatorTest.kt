package com.adsamcik.tracker.stats.engine.ski

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.math.abs

class VerticalRateCalculatorTest {

	private fun altitudes(
		count: Int,
		intervalMs: Long = 1000L,
		startTimeMs: Long = 0L,
		altitude: (Int) -> Float,
	): List<TimestampedAltitude> = (0 until count).map { i ->
		TimestampedAltitude(
			timeMs = startTimeMs + i * intervalMs,
			altitudeM = altitude(i),
		)
	}

	@Nested
	inner class BasicComputation {

		@Test
		fun `constantClimb - linearly increasing altitude produces positive rates`() {
			// 0m → 100m over 100 seconds → ~1 m/s
			val input = altitudes(101, intervalMs = 1000L) { i -> i.toFloat() }
			val result = VerticalRateCalculator.compute(input)

			result shouldHaveSize 101
			result[0].verticalRateMps shouldBe 0f
			// Trim head (EMA warmup) and tail (median filter edge effect) to check steady state
			val steady = result.subList(20, result.size - 5)
			steady.forEach { point ->
				point.verticalRateMps shouldBe (1.0f plusOrMinus 0.05f)
			}
		}

		@Test
		fun `constantDescent - linearly decreasing altitude produces negative rates`() {
			// 100m → 0m over 100 seconds → ~-1 m/s
			val input = altitudes(101, intervalMs = 1000L) { i -> 100f - i.toFloat() }
			val result = VerticalRateCalculator.compute(input)

			result shouldHaveSize 101
			// Trim head and tail for steady-state check
			val steady = result.subList(20, result.size - 5)
			steady.forEach { point ->
				point.verticalRateMps shouldBe (-1.0f plusOrMinus 0.05f)
			}
		}

		@Test
		fun `flatTerrain - constant altitude produces near-zero rates`() {
			val input = altitudes(50, intervalMs = 1000L) { 1500f }
			val result = VerticalRateCalculator.compute(input)

			result shouldHaveSize 50
			result.forEach { point ->
				point.verticalRateMps shouldBe (0f plusOrMinus 0.01f)
			}
		}

		@Test
		fun `emptyInput - returns empty list`() {
			val result = VerticalRateCalculator.compute(emptyList())
			result.shouldBeEmpty()
		}

		@Test
		fun `singlePoint - returns single point with rate zero`() {
			val input = listOf(TimestampedAltitude(timeMs = 0L, altitudeM = 500f))
			val result = VerticalRateCalculator.compute(input)

			result shouldHaveSize 1
			result[0].verticalRateMps shouldBe 0f
			result[0].timeMs shouldBe 0L
		}
	}

	@Nested
	inner class Smoothing {

		@Test
		fun `medianFilterRemovesSpikeNoise - outlier in flat terrain is filtered out`() {
			// Flat at 100m with a single 200m spike at index 25
			val input = altitudes(50, intervalMs = 1000L) { i ->
				if (i == 25) 200f else 100f
			}
			val result = VerticalRateCalculator.compute(input)

			// After smoothing, all rates should stay near zero despite the spike
			result.forEach { point ->
				point.verticalRateMps shouldBe (0f plusOrMinus 0.5f)
			}
		}

		@Test
		fun `gpsAltitudeNoise - noisy climb still produces roughly correct rate`() {
			// Steady 0.5 m/s climb with ±15m GPS noise
			val rng = java.util.Random(42)
			val input = altitudes(200, intervalMs = 1000L) { i ->
				i * 0.5f + (rng.nextGaussian() * 15.0).toFloat()
			}
			val result = VerticalRateCalculator.compute(
				input,
				medianWindow = 11,
				emaAlpha = 0.2f,
			)

			// Last third of results should converge near 0.5 m/s
			val tail = result.drop(result.size * 2 / 3)
			val avgRate = tail.map { it.verticalRateMps }.average().toFloat()
			avgRate shouldBe (0.5f plusOrMinus 0.5f)
		}

		@Test
		fun `emaSmoothing - reduces rapid oscillations`() {
			// Alternating +5/-5 m/s raw rates
			val rawRates = (0 until 20).map { i ->
				TimestampedVerticalRate(
					timeMs = i * 1000L,
					verticalRateMps = if (i % 2 == 0) 5f else -5f,
				)
			}
			val smoothed = VerticalRateCalculator.emaSmooth(rawRates, alpha = 0.3f)

			// Smoothed values should have smaller amplitude than raw ±5
			smoothed.drop(5).forEach { point ->
				abs(point.verticalRateMps) shouldBe (0f plusOrMinus 4.0f)
			}
			// The average absolute value should be well below the raw 5
			val avgAbs = smoothed.drop(5).map { abs(it.verticalRateMps) }.average().toFloat()
			assert(avgAbs < 4.0f) { "EMA should dampen oscillations, got avgAbs=$avgAbs" }
		}
	}

	@Nested
	inner class EdgeCases {

		@Test
		fun `duplicateTimestamps - same timestamp produces rate zero not NaN`() {
			val input = listOf(
				TimestampedAltitude(timeMs = 1000L, altitudeM = 100f),
				TimestampedAltitude(timeMs = 1000L, altitudeM = 200f),
			)
			val result = VerticalRateCalculator.compute(input)

			result shouldHaveSize 2
			result.forEach { point ->
				assert(!point.verticalRateMps.isNaN()) { "Rate should not be NaN" }
				assert(!point.verticalRateMps.isInfinite()) { "Rate should not be Infinite" }
			}
			result[1].verticalRateMps shouldBe 0f
		}

		@Test
		fun `largeTimeGap - two points 10 minutes apart computes average rate over gap`() {
			val input = listOf(
				TimestampedAltitude(timeMs = 0L, altitudeM = 1000f),
				TimestampedAltitude(timeMs = 600_000L, altitudeM = 1300f),
			)
			val result = VerticalRateCalculator.compute(input)

			result shouldHaveSize 2
			// Raw rate = 300m/600s = 0.5 m/s, but EMA (alpha=0.3) on only 2 points
			// gives 0.3 * 0.5 + 0.7 * 0 = 0.15
			result[1].verticalRateMps shouldBe (0.15f plusOrMinus 0.01f)
		}
	}
}
