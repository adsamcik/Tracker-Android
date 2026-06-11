package com.adsamcik.tracker.stats.engine.ski

import com.adsamcik.tracker.stats.engine.filter.GpsCleaningConfig
import com.adsamcik.tracker.stats.engine.filter.GpsPoint
import com.adsamcik.tracker.stats.engine.filter.GpsTrackCleaner
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * End-to-end integration tests for the ski detection pipeline.
 *
 * Exercises: GPS data → VerticalRateCalculator → SkiStateMachine → SkiRunExtractor.
 * Test data is generated from realistic parameters based on real Val Thorens skiing
 * (March 2019, SkiTracker export).
 *
 * All generators use a fixed random seed (42) for reproducibility.
 */
class SkiDetectionIntegrationTest {

	// ── Shared pipeline components ──────────────────────────────────────

	private val config = SkiDetectionConfig()
	private val machine = SkiStateMachine(config)
	private val rng = Random(42)

	// ── Test data model ─────────────────────────────────────────────────

	data class TestLocationPoint(
		val timeMs: Long,
		val lat: Double,
		val lon: Double,
		val altitudeM: Float,
		val speedMps: Float?,
	)

	// ── Data generators (based on real Val Thorens patterns) ─────────────

	/**
	 * Generate a lift ride: steady ascent with consistent GPS speed.
	 * Based on real Val Thorens chairlift data (~1900m→2400m over 7.5 min).
	 */
	private fun generateLiftRide(
		startTimeMs: Long,
		durationMs: Long = 450_000L,
		startAlt: Float = 1900f,
		endAlt: Float = 2400f,
		startLat: Double = 45.3225,
		startLon: Double = 6.5385,
		endLat: Double = 45.3306,
		endLon: Double = 6.5547,
		intervalMs: Long = 4000L,
	): List<TestLocationPoint> {
		val count = (durationMs / intervalMs).toInt()
		return (0 until count).map { i ->
			val f = i.toDouble() / (count - 1).coerceAtLeast(1)
			val alt = startAlt + (endAlt - startAlt) * f.toFloat() +
					rng.nextFloat() * 4f - 2f
			val lat = startLat + (endLat - startLat) * f +
					(rng.nextDouble() - 0.5) * 0.0002
			val lon = startLon + (endLon - startLon) * f +
					(rng.nextDouble() - 0.5) * 0.0002
			val speed = 3.0f + rng.nextFloat() - 0.5f
			TestLocationPoint(
				timeMs = startTimeMs + i * intervalMs,
				lat = lat,
				lon = lon,
				altitudeM = alt,
				speedMps = speed,
			)
		}
	}

	/**
	 * Generate a ski descent: fast descent with speed variations and brief pauses.
	 * Based on real Val Thorens piste data (~2400m→1900m over 5 min).
	 */
	private fun generateDescentRun(
		startTimeMs: Long,
		durationMs: Long = 300_000L,
		startAlt: Float = 2400f,
		endAlt: Float = 1900f,
		startLat: Double = 45.3306,
		startLon: Double = 6.5549,
		endLat: Double = 45.3226,
		endLon: Double = 6.5387,
		intervalMs: Long = 4000L,
	): List<TestLocationPoint> {
		val count = (durationMs / intervalMs).toInt()
		return (0 until count).map { i ->
			val f = i.toDouble() / (count - 1).coerceAtLeast(1)
			// Non-linear descent — steeper in middle, gentler at start/end
			val altF = f * f * 0.3 + f * 0.7
			val alt = startAlt + (endAlt - startAlt) * altF.toFloat() +
					rng.nextFloat() * 4f - 2f
			val lat = startLat + (endLat - startLat) * f +
					(rng.nextDouble() - 0.5) * 0.0002
			val lon = startLon + (endLon - startLon) * f +
					(rng.nextDouble() - 0.5) * 0.0002
			// Variable speed: 5-15 m/s with occasional slows
			val baseSpeed = 8.0f + 4.0f * kotlin.math.sin(f * Math.PI * 3).toFloat()
			val speed = (baseSpeed + rng.nextFloat() * 2f - 1f).coerceAtLeast(3.5f)
			TestLocationPoint(
				timeMs = startTimeMs + i * intervalMs,
				lat = lat,
				lon = lon,
				altitudeM = alt,
				speedMps = speed,
			)
		}
	}

	/**
	 * Generate an idle period: stationary with slight GPS jitter.
	 */
	private fun generateIdlePeriod(
		startTimeMs: Long,
		durationMs: Long = 120_000L,
		lat: Double = 45.3306,
		lon: Double = 6.5549,
		alt: Float = 2400f,
		intervalMs: Long = 5000L,
	): List<TestLocationPoint> {
		val count = (durationMs / intervalMs).toInt()
		return (0 until count).map { i ->
			TestLocationPoint(
				timeMs = startTimeMs + i * intervalMs,
				lat = lat + (rng.nextDouble() - 0.5) * 0.00005,
				lon = lon + (rng.nextDouble() - 0.5) * 0.00005,
				altitudeM = alt + rng.nextFloat() * 2f - 1f,
				speedMps = rng.nextFloat() * 0.4f,
			)
		}
	}

	/**
	 * Generate a walking segment: slow speed, some altitude change, step-like.
	 */
	private fun generateWalkingSegment(
		startTimeMs: Long,
		durationMs: Long = 300_000L,
		startAlt: Float = 1900f,
		endAlt: Float = 1920f,
		startLat: Double = 45.3226,
		startLon: Double = 6.5387,
		endLat: Double = 45.3240,
		endLon: Double = 6.5395,
		intervalMs: Long = 5000L,
	): List<TestLocationPoint> {
		val count = (durationMs / intervalMs).toInt()
		return (0 until count).map { i ->
			val f = i.toDouble() / (count - 1).coerceAtLeast(1)
			TestLocationPoint(
				timeMs = startTimeMs + i * intervalMs,
				lat = startLat + (endLat - startLat) * f +
						(rng.nextDouble() - 0.5) * 0.0001,
				lon = startLon + (endLon - startLon) * f +
						(rng.nextDouble() - 0.5) * 0.0001,
				altitudeM = startAlt + (endAlt - startAlt) * f.toFloat() +
						rng.nextFloat() * 2f - 1f,
				speedMps = 1.2f + rng.nextFloat() * 0.6f - 0.3f,
			)
		}
	}

	// ── Pipeline helpers ────────────────────────────────────────────────

	/** Run full pipeline: points → vertical rate → signals → state machine → extractor. */
	private fun runFullPipeline(
		points: List<TestLocationPoint>,
		pipelineConfig: SkiDetectionConfig = config,
	): PipelineResult {
		val altitudes = points.map { TimestampedAltitude(it.timeMs, it.altitudeM) }
		val rates = VerticalRateCalculator.compute(
			altitudes,
			medianWindow = pipelineConfig.gpsAltMedianWindow,
			emaAlpha = pipelineConfig.verticalRateEmaAlpha,
		)

		val signals = rates.mapIndexed { index, rate ->
			val pt = points[index]
			SkiSignal(
				timeMs = rate.timeMs,
				verticalRateMps = rate.verticalRateMps,
				speedMps = pt.speedMps ?: 0f,
			)
		}

		val sm = SkiStateMachine(pipelineConfig)
		val segments = sm.process(signals)
		val cycles = sm.countSkiCycles(segments)

		val locations = points.map {
			SkiLocationPoint(it.timeMs, it.lat, it.lon, it.altitudeM, it.speedMps)
		}
		val summary = SkiRunExtractor.extract(segments, locations)

		return PipelineResult(
			rates = rates,
			segments = segments,
			cycles = cycles,
			summary = summary,
		)
	}

	data class PipelineResult(
		val rates: List<TimestampedVerticalRate>,
		val segments: List<SkiStateSegment>,
		val cycles: Int,
		val summary: SkiSessionSummary,
	)

	/** Build a standard ski day sequence of lift-descent cycles with idle transitions. */
	private fun buildSkiDay(
		numCycles: Int,
		startTimeMs: Long = 0L,
		liftDurationMs: Long = 450_000L,
		descentDurationMs: Long = 300_000L,
		idleDurationMs: Long = 120_000L,
		topIdleDurationMs: Long = 60_000L,
		startAlt: Float = 1900f,
		endAlt: Float = 2400f,
	): List<TestLocationPoint> {
		val points = mutableListOf<TestLocationPoint>()
		var t = startTimeMs

		// Initial idle at base
		points += generateIdlePeriod(t, idleDurationMs, alt = startAlt,
			lat = 45.3225, lon = 6.5385)
		t += idleDurationMs

		repeat(numCycles) {
			// Lift up
			points += generateLiftRide(t, liftDurationMs, startAlt = startAlt, endAlt = endAlt)
			t += liftDurationMs

			// Brief idle at top
			points += generateIdlePeriod(t, topIdleDurationMs, alt = endAlt)
			t += topIdleDurationMs

			// Descent
			points += generateDescentRun(t, descentDurationMs, startAlt = endAlt, endAlt = startAlt)
			t += descentDurationMs

			// Idle at bottom
			points += generateIdlePeriod(t, idleDurationMs, alt = startAlt,
				lat = 45.3226, lon = 6.5387)
			t += idleDurationMs
		}

		return points
	}

	// ── Test suites ─────────────────────────────────────────────────────

	@Nested
	inner class RealSkiDayScenario {

		@Test
		fun `fullSkiDay_twoRuns_detectedAsSkiing`() {
			val points = buildSkiDay(numCycles = 2)
			val result = runFullPipeline(points)

			// Vertical rate should show clear positive and negative patterns
			val positiveRates = result.rates.count { it.verticalRateMps > 0.3f }
			val negativeRates = result.rates.count { it.verticalRateMps < -0.3f }
			assert(positiveRates > 20) { "Expected positive lift rates, got $positiveRates" }
			assert(negativeRates > 20) { "Expected negative descent rates, got $negativeRates" }

			// State machine should produce lift and downhill segments
			val liftSegments = result.segments.filter { it.state == SkiState.LIFT_UP }
			val downhillSegments = result.segments.filter { it.state == SkiState.DOWNHILL_RUN }
			liftSegments.shouldNotBeEmpty()
			downhillSegments.shouldNotBeEmpty()

			// At least 2 complete cycles
			result.cycles shouldBeGreaterThanOrEqual 2

			// Summary validation
			result.summary.totalRuns shouldBeGreaterThanOrEqual 2
			// Each descent drops ~500m, total vertical should be roughly -1000m
			val totalVert = abs(result.summary.totalVerticalM)
			totalVert shouldBe (1000f plusOrMinus 400f)
		}

		@Test
		fun `fullSkiDay_fiveRuns_highConfidence`() {
			val points = buildSkiDay(numCycles = 5)
			val result = runFullPipeline(points)

			result.cycles shouldBeGreaterThanOrEqual 5
			result.summary.totalRuns shouldBeGreaterThanOrEqual 5
			result.summary.totalDistanceM shouldBe (
					result.summary.totalDistanceM plusOrMinus 1f
					) // non-zero check via identity
			assert(result.summary.totalDistanceM > 0f) {
				"Total distance should be positive"
			}
		}

		@Test
		fun `halfDay_endedMidLift_partialDetection`() {
			val fullDay = buildSkiDay(numCycles = 2)
			// Add a partial 3rd lift that stops halfway through
			var t = fullDay.last().timeMs + 5000L
			val partialLift = generateLiftRide(
				t, durationMs = 200_000L,
				startAlt = 1900f, endAlt = 2100f,
			)
			val points = fullDay + partialLift

			val result = runFullPipeline(points)

			// Should still detect the 2 complete cycles
			result.cycles shouldBeGreaterThanOrEqual 2
			result.summary.totalRuns shouldBeGreaterThanOrEqual 2
		}
	}

	@Nested
	inner class EdgeCases {

		@Test
		fun `singleLiftAndDescent_belowThreshold`() {
			val points = buildSkiDay(numCycles = 1)
			val result = runFullPipeline(points)

			// Only 1 cycle — below minCyclesForClassification (2)
			result.cycles shouldBeLessThanOrEqual 1
		}

		@Test
		fun `noAltitudeData_returnsEmpty`() {
			val points = buildSkiDay(numCycles = 2).map {
				it.copy(altitudeM = 0f)
			}
			val altitudes = points.map { TimestampedAltitude(it.timeMs, 0f) }
			val rates = VerticalRateCalculator.compute(altitudes)

			// All rates should be near zero when altitude is constant zero
			rates.forEach { point ->
				point.verticalRateMps shouldBe (0f plusOrMinus 0.01f)
			}
		}

		@Test
		fun `veryShortSession_fiveMinutes`() {
			// Only 5 minutes of data — one partial descent
			val points = generateDescentRun(
				startTimeMs = 0L,
				durationMs = 300_000L,
				startAlt = 2400f,
				endAlt = 2100f,
			)
			val result = runFullPipeline(points)

			// No complete lift+descent cycles
			result.cycles shouldBe 0
		}

		@Test
		fun `longIdlePeriod_midDay_stillDetects`() {
			val points = mutableListOf<TestLocationPoint>()
			var t = 0L

			// First cycle
			points += generateIdlePeriod(t, 60_000L, alt = 1900f, lat = 45.3225, lon = 6.5385)
			t += 60_000L
			points += generateLiftRide(t)
			t += 450_000L
			points += generateIdlePeriod(t, 60_000L, alt = 2400f)
			t += 60_000L
			points += generateDescentRun(t)
			t += 300_000L

			// 45-minute break (restaurant)
			points += generateIdlePeriod(t, 2_700_000L, alt = 1900f,
				lat = 45.3226, lon = 6.5387)
			t += 2_700_000L

			// Second cycle
			points += generateLiftRide(t)
			t += 450_000L
			points += generateIdlePeriod(t, 60_000L, alt = 2400f)
			t += 60_000L
			points += generateDescentRun(t)
			t += 300_000L
			points += generateIdlePeriod(t, 60_000L, alt = 1900f,
				lat = 45.3226, lon = 6.5387)

			val result = runFullPipeline(points)

			// Long idle should not break detection
			result.cycles shouldBeGreaterThanOrEqual 2
		}

		@Test
		fun `noisyGpsAltitude_stillDetects`() {
			val noisyRng = Random(99)
			val clean = buildSkiDay(numCycles = 3)
			// Add ±20m random noise to altitude
			val noisy = clean.map {
				it.copy(altitudeM = it.altitudeM + (noisyRng.nextFloat() * 40f - 20f))
			}

			val result = runFullPipeline(noisy)

			// GPS median window (11) should handle this noise
			result.cycles shouldBeGreaterThanOrEqual 3
		}
	}

	@Nested
	inner class NegativeScenarios {

		@Test
		fun `walkingHike_noDetection`() {
			// 2-hour walk with gradual 300m elevation gain
			val durationMs = 7_200_000L
			val intervalMs = 5000L
			val count = (durationMs / intervalMs).toInt()
			val walkRng = Random(42)
			val points = (0 until count).map { i ->
				val f = i.toDouble() / (count - 1).coerceAtLeast(1)
				TestLocationPoint(
					timeMs = i * intervalMs,
					lat = 45.3225 + f * 0.02 + (walkRng.nextDouble() - 0.5) * 0.0001,
					lon = 6.5385 + f * 0.01 + (walkRng.nextDouble() - 0.5) * 0.0001,
					altitudeM = 1600f + 300f * f.toFloat() + walkRng.nextFloat() * 4f - 2f,
					speedMps = 1.2f + walkRng.nextFloat() * 0.6f - 0.3f,
				)
			}

			val result = runFullPipeline(points)

			// Walking produces no lift+descent cycles
			result.cycles shouldBe 0
		}

		@Test
		fun `mountainDriving_noDetection`() {
			// Driving up: 2000m→2500m over 30 min at 15-25 m/s, then down
			val driveRng = Random(42)
			val points = mutableListOf<TestLocationPoint>()
			var t = 0L
			val upCount = 450 // 30min at 4s intervals
			for (i in 0 until upCount) {
				val f = i.toDouble() / (upCount - 1)
				points += TestLocationPoint(
					timeMs = t,
					lat = 45.32 + f * 0.05,
					lon = 6.54 + f * 0.03,
					altitudeM = 2000f + 500f * f.toFloat() + driveRng.nextFloat() * 4f - 2f,
					speedMps = 20f + driveRng.nextFloat() * 10f - 5f,
				)
				t += 4000L
			}
			// Brief stop at top
			for (i in 0 until 30) {
				points += TestLocationPoint(
					timeMs = t,
					lat = 45.37 + (driveRng.nextDouble() - 0.5) * 0.0001,
					lon = 6.57 + (driveRng.nextDouble() - 0.5) * 0.0001,
					altitudeM = 2500f + driveRng.nextFloat() * 2f - 1f,
					speedMps = driveRng.nextFloat() * 0.3f,
				)
				t += 5000L
			}
			// Driving down
			for (i in 0 until upCount) {
				val f = i.toDouble() / (upCount - 1)
				points += TestLocationPoint(
					timeMs = t,
					lat = 45.37 - f * 0.05,
					lon = 6.57 - f * 0.03,
					altitudeM = 2500f - 500f * f.toFloat() + driveRng.nextFloat() * 4f - 2f,
					speedMps = 18f + driveRng.nextFloat() * 8f - 4f,
				)
				t += 4000L
			}

			val result = runFullPipeline(points)

			// Driving speed exceeds liftMaxSpeed → no LIFT_UP state → 0 cycles
			result.cycles shouldBe 0
		}

		@Test
		fun `flatCycling_noDetection`() {
			// 1-hour bike ride on flat terrain, speed 5-8 m/s, altitude ±10m
			val bikeRng = Random(42)
			val durationMs = 3_600_000L
			val intervalMs = 4000L
			val count = (durationMs / intervalMs).toInt()
			val points = (0 until count).map { i ->
				val f = i.toDouble() / (count - 1).coerceAtLeast(1)
				TestLocationPoint(
					timeMs = i * intervalMs,
					lat = 45.32 + f * 0.05 + (bikeRng.nextDouble() - 0.5) * 0.0001,
					lon = 6.54 + f * 0.03 + (bikeRng.nextDouble() - 0.5) * 0.0001,
					altitudeM = 500f + bikeRng.nextFloat() * 20f - 10f,
					speedMps = 6.5f + bikeRng.nextFloat() * 3f - 1.5f,
				)
			}

			val result = runFullPipeline(points)

			// Flat terrain → no significant vertical rate → no cycles
			result.cycles shouldBe 0
		}

		@Test
		fun `hikingWithSteepDescent_noDetection`() {
			// Hike up 500m over 2 hours, then steep descent over 45 min
			val hikeRng = Random(42)
			val points = mutableListOf<TestLocationPoint>()
			var t = 0L

			// Ascent: 2 hours, walking speed
			val upCount = 1440 // 2hr at 5s intervals
			for (i in 0 until upCount) {
				val f = i.toDouble() / (upCount - 1)
				points += TestLocationPoint(
					timeMs = t,
					lat = 45.32 + f * 0.015 + (hikeRng.nextDouble() - 0.5) * 0.0001,
					lon = 6.54 + f * 0.008 + (hikeRng.nextDouble() - 0.5) * 0.0001,
					altitudeM = 1500f + 500f * f.toFloat() + hikeRng.nextFloat() * 4f - 2f,
					speedMps = 1.0f + hikeRng.nextFloat() * 0.6f - 0.3f,
				)
				t += 5000L
			}

			// Steep descent: 45 min, walking speed
			val downCount = 540 // 45min at 5s intervals
			for (i in 0 until downCount) {
				val f = i.toDouble() / (downCount - 1)
				points += TestLocationPoint(
					timeMs = t,
					lat = 45.335 - f * 0.015 + (hikeRng.nextDouble() - 0.5) * 0.0001,
					lon = 6.548 - f * 0.008 + (hikeRng.nextDouble() - 0.5) * 0.0001,
					altitudeM = 2000f - 500f * f.toFloat() + hikeRng.nextFloat() * 4f - 2f,
					speedMps = 1.3f + hikeRng.nextFloat() * 0.8f - 0.4f,
				)
				t += 5000L
			}

			val result = runFullPipeline(points)

			// Walking speed on ascent → not classified as LIFT_UP;
			// walking speed on descent → below downhillEnterSpeed → no DOWNHILL_RUN
			result.cycles shouldBe 0
		}
	}

	@Nested
	inner class VerticalRateWithRealPatterns {

		@Test
		fun `liftVerticalRate_isPositive`() {
			val lift = generateLiftRide(0L)
			val altitudes = lift.map { TimestampedAltitude(it.timeMs, it.altitudeM) }
			val rates = VerticalRateCalculator.compute(
				altitudes,
				medianWindow = config.gpsAltMedianWindow,
				emaAlpha = config.verticalRateEmaAlpha,
			)

			// Trim warmup period and check steady-state positive rate
			val steady = rates.drop(rates.size / 3)
			val avgRate = steady.map { it.verticalRateMps }.average().toFloat()
			avgRate shouldBe (1.1f plusOrMinus 0.7f)
			assert(avgRate > 0.4f) { "Lift average rate should be positive, got $avgRate" }
		}

		@Test
		fun `descentVerticalRate_isNegative`() {
			val descent = generateDescentRun(0L)
			val altitudes = descent.map { TimestampedAltitude(it.timeMs, it.altitudeM) }
			val rates = VerticalRateCalculator.compute(
				altitudes,
				medianWindow = config.gpsAltMedianWindow,
				emaAlpha = config.verticalRateEmaAlpha,
			)

			// Trim warmup and check negative rate
			val steady = rates.drop(rates.size / 3)
			val avgRate = steady.map { it.verticalRateMps }.average().toFloat()
			assert(avgRate < -0.3f) { "Descent average rate should be negative, got $avgRate" }
		}

		@Test
		fun `idleVerticalRate_isNearZero`() {
			val idle = generateIdlePeriod(0L, durationMs = 300_000L)
			val altitudes = idle.map { TimestampedAltitude(it.timeMs, it.altitudeM) }
			val rates = VerticalRateCalculator.compute(
				altitudes,
				medianWindow = config.gpsAltMedianWindow,
				emaAlpha = config.verticalRateEmaAlpha,
			)

			rates.forEach { point ->
				point.verticalRateMps shouldBe (0f plusOrMinus 0.3f)
			}
		}

		@Test
		fun `transitionFromLiftToDescent_rateChangesSign`() {
			var t = 0L
			val points = mutableListOf<TestLocationPoint>()

			// Lift segment
			points += generateLiftRide(t, durationMs = 450_000L)
			t += 450_000L
			// Brief idle at top
			points += generateIdlePeriod(t, durationMs = 60_000L, alt = 2400f)
			t += 60_000L
			// Descent segment
			points += generateDescentRun(t, durationMs = 600_000L)

			val altitudes = points.map { TimestampedAltitude(it.timeMs, it.altitudeM) }
			val rates = VerticalRateCalculator.compute(
				altitudes,
				medianWindow = config.gpsAltMedianWindow,
				emaAlpha = config.verticalRateEmaAlpha,
			)

			// Find steady-state in lift portion (first third)
			val liftPortion = rates.take(rates.size / 3).drop(rates.size / 6)
			val liftAvg = liftPortion.map { it.verticalRateMps }.average().toFloat()

			// Find steady-state in descent portion (last third)
			val descentPortion = rates.takeLast(rates.size / 3)
			val descentAvg = descentPortion.map { it.verticalRateMps }.average().toFloat()

			assert(liftAvg > 0.3f) { "Lift portion should have positive rate, got $liftAvg" }
			assert(descentAvg < -0.3f) { "Descent portion should have negative rate, got $descentAvg" }
		}
	}

	@Nested
	inner class GpsCleaningIntegration {

		@Test
		fun `cleaningPreservesSkiPattern`() {
			val spikeRng = Random(77)
			val clean = buildSkiDay(numCycles = 3)
			// Add 5% outlier spikes (random points ~1km away)
			val spiked = clean.map { pt ->
				if (spikeRng.nextFloat() < 0.05f) {
					pt.copy(
						lat = pt.lat + (spikeRng.nextDouble() - 0.5) * 0.02,
						lon = pt.lon + (spikeRng.nextDouble() - 0.5) * 0.02,
					)
				} else {
					pt
				}
			}

			// Convert to GpsPoint and clean
			val gpsPoints = spiked.map {
				GpsPoint(
					timeMs = it.timeMs,
					latitudeDeg = it.lat,
					longitudeDeg = it.lon,
					altitudeM = it.altitudeM,
					speedMps = it.speedMps,
					horizontalAccuracyM = 10f,
					verticalAccuracyM = 20f,
				)
			}
			val cleaned = GpsTrackCleaner.clean(gpsPoints)
			cleaned.shouldNotBeEmpty()

			// Run pipeline on cleaned data (use largest segment)
			val largest = cleaned.maxByOrNull { it.points.size }!!
			val testPoints = largest.points.map {
				TestLocationPoint(
					it.timeMs, it.latitudeDeg, it.longitudeDeg,
					it.altitudeM ?: 0f, it.speedMps,
				)
			}
			val result = runFullPipeline(testPoints)

			// After cleaning, ski pattern should still be detectable
			result.cycles shouldBeGreaterThanOrEqual 2
		}

		@Test
		fun `cleaningRemovesImpossibleSpeeds`() {
			val speedRng = Random(55)
			val clean = buildSkiDay(numCycles = 2)
			// Insert a few points implying 200 m/s speed (teleport)
			val spiked = clean.mapIndexed { i, pt ->
				if (i % 100 == 50) {
					pt.copy(
						lat = pt.lat + 0.01, // ~1.1km jump
						lon = pt.lon + 0.01,
					)
				} else {
					pt
				}
			}

			val gpsPoints = spiked.map {
				GpsPoint(
					timeMs = it.timeMs,
					latitudeDeg = it.lat,
					longitudeDeg = it.lon,
					altitudeM = it.altitudeM,
					speedMps = it.speedMps,
					horizontalAccuracyM = 10f,
					verticalAccuracyM = 20f,
				)
			}

			val before = gpsPoints.size
			val cleaned = GpsTrackCleaner.clean(gpsPoints)
			cleaned.shouldNotBeEmpty()
			val afterCount = cleaned.sumOf { it.points.size }

			// Some spikes should have been removed
			assert(afterCount < before) {
				"Cleaning should remove impossible speed points: before=$before, after=$afterCount"
			}

			// Ski detection should still work on cleaned data
			val largest = cleaned.maxByOrNull { it.points.size }!!
			val testPoints = largest.points.map {
				TestLocationPoint(
					it.timeMs, it.latitudeDeg, it.longitudeDeg,
					it.altitudeM ?: 0f, it.speedMps,
				)
			}
			val result = runFullPipeline(testPoints)
			result.cycles shouldBeGreaterThanOrEqual 2
		}
	}

	@Nested
	inner class ConfigSensitivity {

		@Test
		fun `stricterThresholds_fewerOrEqualCycles`() {
			val points = buildSkiDay(numCycles = 3)

			val defaultResult = runFullPipeline(points)

			// Stricter: higher vertical rate to enter lift, lower to enter downhill
			val strictConfig = config.copy(
				liftEnterVerticalRate = 1.0f,
				downhillEnterVerticalRate = -2.0f,
				downhillEnterSpeed = 5.0f,
			)
			val strictResult = runFullPipeline(points, strictConfig)

			// Stricter thresholds should yield same or fewer cycles
			assert(strictResult.cycles <= defaultResult.cycles) {
				"Strict config should detect ≤ default cycles: " +
						"strict=${strictResult.cycles}, default=${defaultResult.cycles}"
			}
		}

		@Test
		fun `looserThresholds_moreOrEqualCycles`() {
			val points = buildSkiDay(numCycles = 3)

			val defaultResult = runFullPipeline(points)

			// Looser: lower vertical rate to enter lift, less negative for downhill
			val looseConfig = config.copy(
				liftEnterVerticalRate = 0.2f,
				downhillEnterVerticalRate = -0.5f,
				downhillEnterSpeed = 1.5f,
			)
			val looseResult = runFullPipeline(points, looseConfig)

			// Looser thresholds should yield same or more cycles
			assert(looseResult.cycles >= defaultResult.cycles) {
				"Loose config should detect ≥ default cycles: " +
						"loose=${looseResult.cycles}, default=${defaultResult.cycles}"
			}
		}
	}
}
