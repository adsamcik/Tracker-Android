package com.adsamcik.tracker.stats.engine.ski

import com.adsamcik.tracker.stats.api.ski.SkiLift
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SkiLiftProximityScorerTest {

	// ── Helpers ─────────────────────────────────────────────────────────

	private fun loc(
		timeMs: Long,
		lat: Double,
		lon: Double,
		alt: Float? = null,
		speed: Float? = null,
	) = SkiLocationPoint(timeMs, lat, lon, alt, speed)

	private fun lift(
		id: Long = 1L,
		startLat: Double,
		startLon: Double,
		endLat: Double,
		endLon: Double,
		liftType: String = "chairlift",
		name: String? = null,
	) = SkiLift(id, liftType, name, startLat, startLon, null, endLat, endLon, null)

	private fun segment(state: SkiState, startMs: Long, endMs: Long) =
		SkiStateSegment(state, startMs, endMs)

	/** Locations spanning a segment's time range, moving from (lat1,lon1) to (lat2,lon2). */
	private fun locationsForSegment(
		startMs: Long,
		endMs: Long,
		startLat: Double,
		startLon: Double,
		endLat: Double,
		endLon: Double,
		count: Int = 5,
	): List<SkiLocationPoint> {
		val interval = (endMs - startMs) / (count - 1).coerceAtLeast(1)
		return (0 until count).map { i ->
			val f = i.toDouble() / (count - 1).coerceAtLeast(1)
			loc(
				timeMs = startMs + i * interval,
				lat = startLat + (endLat - startLat) * f,
				lon = startLon + (endLon - startLon) * f,
			)
		}
	}

	private val emptyFinder: (Double, Double, Double) -> List<SkiLift> = { _, _, _ -> emptyList() }

	private data class SkiDayData(
		val signals: List<SkiSignal>,
		val locations: List<SkiLocationPoint>,
		val liftCoords: List<Pair<Pair<Double, Double>, Pair<Double, Double>>>,
	)

	// ── Bearing calculation ────────────────────────────────────────────

	@Nested
	inner class BearingCalculation {

		@Test
		fun `northBearing`() {
			val b = SkiLiftProximityScorer.bearing(0.0, 0.0, 1.0, 0.0)
			b shouldBe (0.0 plusOrMinus 1.0)
		}

		@Test
		fun `eastBearing`() {
			val b = SkiLiftProximityScorer.bearing(0.0, 0.0, 0.0, 1.0)
			b shouldBe (90.0 plusOrMinus 1.0)
		}

		@Test
		fun `southBearing`() {
			val b = SkiLiftProximityScorer.bearing(1.0, 0.0, 0.0, 0.0)
			b shouldBe (180.0 plusOrMinus 1.0)
		}

		@Test
		fun `bearingDifference_sameDirection`() {
			SkiLiftProximityScorer.bearingDifference(45.0, 45.0) shouldBe (0.0 plusOrMinus 0.01)
		}

		@Test
		fun `bearingDifference_opposite`() {
			SkiLiftProximityScorer.bearingDifference(0.0, 180.0) shouldBe (180.0 plusOrMinus 0.01)
		}

		@Test
		fun `bearingDifference_wrapsAround`() {
			SkiLiftProximityScorer.bearingDifference(350.0, 10.0) shouldBe (20.0 plusOrMinus 0.01)
		}
	}

	// ── Direction match ────────────────────────────────────────────────

	@Nested
	inner class DirectionMatch {

		@Test
		fun `sameDirection_matches`() {
			// Track going NE, lift going NE
			val start = loc(0, 45.0, 6.0)
			val end = loc(1, 45.01, 6.01)
			val lft = lift(startLat = 45.0, startLon = 6.0, endLat = 45.01, endLon = 6.01)
			SkiLiftProximityScorer.isDirectionMatch(start, end, lft).shouldBeTrue()
		}

		@Test
		fun `oppositeDirection_matches`() {
			// Track going NE, lift mapped in SW direction
			val start = loc(0, 45.0, 6.0)
			val end = loc(1, 45.01, 6.01)
			val lft = lift(startLat = 45.01, startLon = 6.01, endLat = 45.0, endLon = 6.0)
			SkiLiftProximityScorer.isDirectionMatch(start, end, lft).shouldBeTrue()
		}

		@Test
		fun `perpendicularDirection_noMatch`() {
			// Track going N (lat increases), lift going E (lon increases)
			val start = loc(0, 45.0, 6.0)
			val end = loc(1, 45.01, 6.0)
			val lft = lift(startLat = 45.0, startLon = 6.0, endLat = 45.0, endLon = 6.01)
			SkiLiftProximityScorer.isDirectionMatch(start, end, lft).shouldBeFalse()
		}

		@Test
		fun `withinThreshold_60degrees`() {
			// Track bearing ~0° (north), lift bearing ~55° (NE-ish)
			// 55° difference is within the 60° threshold
			val start = loc(0, 45.0, 6.0)
			val end = loc(1, 46.0, 6.0) // due north
			val lft = lift(startLat = 45.0, startLon = 6.0, endLat = 45.01, endLon = 6.020197)
			val liftBearing = SkiLiftProximityScorer.bearing(45.0, 6.0, 45.01, 6.020197)
			assert(liftBearing in 50.0..60.0) { "Lift bearing should be ~55°, got $liftBearing" }
			SkiLiftProximityScorer.isDirectionMatch(start, end, lft).shouldBeTrue()
		}

		@Test
		fun `justOutsideThreshold`() {
			// Track bearing ~0° (north), lift bearing ~65°
			// 65° difference exceeds the 60° threshold
			val start = loc(0, 45.0, 6.0)
			val end = loc(1, 46.0, 6.0) // due north
			val lft = lift(startLat = 45.0, startLon = 6.0, endLat = 45.01, endLon = 6.030328)
			val liftBearing = SkiLiftProximityScorer.bearing(45.0, 6.0, 45.01, 6.030328)
			assert(liftBearing in 62.0..70.0) { "Lift bearing should be ~65°, got $liftBearing" }
			SkiLiftProximityScorer.isDirectionMatch(start, end, lft).shouldBeFalse()
		}
	}

	// ── Scoring ────────────────────────────────────────────────────────

	@Nested
	inner class Scoring {

		@Test
		fun `noLiftSegments_zeroScore`() {
			val segments = listOf(
				segment(SkiState.DOWNHILL_RUN, 0, 60_000),
				segment(SkiState.IDLE, 60_000, 120_000),
				segment(SkiState.DOWNHILL_RUN, 120_000, 180_000),
			)
			val locations = locationsForSegment(0, 180_000, 45.0, 6.0, 45.01, 6.01)
			val result = SkiLiftProximityScorer.score(segments, locations, emptyFinder)

			result.matchedLiftSegments shouldBe 0
			result.totalLiftSegments shouldBe 0
			result.confidenceBoost shouldBe 0
			result.onKnownLift.shouldBeFalse()
		}

		@Test
		fun `liftSegmentsWithNoInfrastructure_zeroScore`() {
			val segments = listOf(
				segment(SkiState.LIFT_UP, 0, 60_000),
				segment(SkiState.DOWNHILL_RUN, 60_000, 120_000),
			)
			val locations = locationsForSegment(0, 120_000, 45.0, 6.0, 45.01, 6.01)
			val result = SkiLiftProximityScorer.score(segments, locations, emptyFinder)

			result.matchedLiftSegments shouldBe 0
			result.totalLiftSegments shouldBe 1
			result.confidenceBoost shouldBe 0
			result.onKnownLift.shouldBeFalse()
		}

		@Test
		fun `oneLiftMatch_boost10`() {
			val segments = listOf(
				segment(SkiState.LIFT_UP, 0, 60_000),
				segment(SkiState.DOWNHILL_RUN, 60_000, 120_000),
			)
			// Lift segment going north
			val locations = locationsForSegment(0, 120_000, 45.0, 6.0, 45.02, 6.0)
			val alignedLift = lift(startLat = 45.0, startLon = 6.0, endLat = 45.02, endLon = 6.0)
			val finder: (Double, Double, Double) -> List<SkiLift> = { _, _, _ -> listOf(alignedLift) }

			val result = SkiLiftProximityScorer.score(segments, locations, finder)

			result.matchedLiftSegments shouldBe 1
			result.confidenceBoost shouldBe 10
			result.onKnownLift.shouldBeTrue()
		}

		@Test
		fun `twoLiftMatches_boost15`() {
			val segments = listOf(
				segment(SkiState.LIFT_UP, 0, 60_000),
				segment(SkiState.DOWNHILL_RUN, 60_000, 120_000),
				segment(SkiState.LIFT_UP, 120_000, 180_000),
				segment(SkiState.DOWNHILL_RUN, 180_000, 240_000),
			)
			val locations =
				locationsForSegment(0, 60_000, 45.0, 6.0, 45.01, 6.0) +
						locationsForSegment(60_000, 120_000, 45.01, 6.0, 45.0, 6.0) +
						locationsForSegment(120_000, 180_000, 45.0, 6.0, 45.01, 6.0) +
						locationsForSegment(180_000, 240_000, 45.01, 6.0, 45.0, 6.0)
			val alignedLift = lift(startLat = 45.0, startLon = 6.0, endLat = 45.01, endLon = 6.0)
			val finder: (Double, Double, Double) -> List<SkiLift> = { _, _, _ -> listOf(alignedLift) }

			val result = SkiLiftProximityScorer.score(segments, locations, finder)

			result.matchedLiftSegments shouldBe 2
			result.confidenceBoost shouldBe 15
			result.onKnownLift.shouldBeTrue()
		}

		@Test
		fun `threeLiftMatches_boost20`() {
			val segments = listOf(
				segment(SkiState.LIFT_UP, 0, 60_000),
				segment(SkiState.DOWNHILL_RUN, 60_000, 120_000),
				segment(SkiState.LIFT_UP, 120_000, 180_000),
				segment(SkiState.DOWNHILL_RUN, 180_000, 240_000),
				segment(SkiState.LIFT_UP, 240_000, 300_000),
				segment(SkiState.DOWNHILL_RUN, 300_000, 360_000),
			)
			val locations =
				locationsForSegment(0, 60_000, 45.0, 6.0, 45.01, 6.0) +
						locationsForSegment(60_000, 120_000, 45.01, 6.0, 45.0, 6.0) +
						locationsForSegment(120_000, 180_000, 45.0, 6.0, 45.01, 6.0) +
						locationsForSegment(180_000, 240_000, 45.01, 6.0, 45.0, 6.0) +
						locationsForSegment(240_000, 300_000, 45.0, 6.0, 45.01, 6.0) +
						locationsForSegment(300_000, 360_000, 45.01, 6.0, 45.0, 6.0)
			val alignedLift = lift(startLat = 45.0, startLon = 6.0, endLat = 45.01, endLon = 6.0)
			val finder: (Double, Double, Double) -> List<SkiLift> = { _, _, _ -> listOf(alignedLift) }

			val result = SkiLiftProximityScorer.score(segments, locations, finder)

			result.matchedLiftSegments shouldBe 3
			result.confidenceBoost shouldBe 20
			result.onKnownLift.shouldBeTrue()
		}

		@Test
		fun `liftNearbyButWrongDirection_noMatch`() {
			val segments = listOf(
				segment(SkiState.LIFT_UP, 0, 60_000),
				segment(SkiState.DOWNHILL_RUN, 60_000, 120_000),
			)
			// Track going north
			val locations = locationsForSegment(0, 120_000, 45.0, 6.0, 45.02, 6.0)
			// Lift going east (perpendicular)
			val perpLift = lift(startLat = 45.01, startLon = 5.99, endLat = 45.01, endLon = 6.01)
			val finder: (Double, Double, Double) -> List<SkiLift> = { _, _, _ -> listOf(perpLift) }

			val result = SkiLiftProximityScorer.score(segments, locations, finder)

			result.matchedLiftSegments shouldBe 0
			result.confidenceBoost shouldBe 0
			result.onKnownLift.shouldBeFalse()
		}
	}

	// ── Integration with ski day ───────────────────────────────────────

	@Nested
	inner class IntegrationWithSkiDay {

		private val machine = SkiStateMachine()

		private fun generateSignals(
			durationMs: Long,
			intervalMs: Long = 1000L,
			startTimeMs: Long = 0L,
			verticalRate: Float,
			speed: Float,
		): List<SkiSignal> {
			val count = (durationMs / intervalMs).toInt()
			return (0 until count).map { i ->
				SkiSignal(
					timeMs = startTimeMs + i * intervalMs,
					verticalRateMps = verticalRate,
					speedMps = speed,
				)
			}
		}

		/**
		 * Build a 3-cycle ski day returning both signals (for state machine)
		 * and location points (for proximity scorer), along with lift
		 * coordinates that align with the lift segments.
		 */
		private fun buildThreeCycleSkiDay(): SkiDayData {
			val signals = mutableListOf<SkiSignal>()
			val locations = mutableListOf<SkiLocationPoint>()
			val liftCoords = mutableListOf<Pair<Pair<Double, Double>, Pair<Double, Double>>>()
			var t = 0L

			// Base coordinates for Val Thorens area
			val baseLat = 45.3225
			val baseLon = 6.5385
			val topLat = 45.3306
			val topLon = 6.5385

			repeat(3) { cycle ->
				val liftStart = t

				// LIFT 8 min
				signals += generateSignals(480_000L, startTimeMs = t, verticalRate = 1.5f, speed = 3f)
				val liftLocs = locationsForSegment(
					t, t + 480_000L,
					baseLat, baseLon + cycle * 0.001,
					topLat, topLon + cycle * 0.001,
					count = 480,
				)
				locations += liftLocs
				liftCoords += (baseLat to (baseLon + cycle * 0.001)) to (topLat to (topLon + cycle * 0.001))
				t += 480_000L

				// DOWNHILL 3 min
				signals += generateSignals(180_000L, startTimeMs = t, verticalRate = -2f, speed = 8f)
				locations += locationsForSegment(
					t, t + 180_000L,
					topLat, topLon + cycle * 0.001,
					baseLat, baseLon + cycle * 0.001,
					count = 180,
				)
				t += 180_000L

				// IDLE 2 min
				signals += generateSignals(120_000L, startTimeMs = t, verticalRate = 0f, speed = 0f)
				locations += locationsForSegment(
					t, t + 120_000L,
					baseLat, baseLon + cycle * 0.001,
					baseLat, baseLon + cycle * 0.001,
					count = 120,
				)
				t += 120_000L
			}

			return SkiDayData(signals, locations, liftCoords)
		}

		@Test
		fun `fullSkiDay_withMatchingLifts_boosted`() {
			val data = buildThreeCycleSkiDay()
			val segments = machine.process(data.signals)

			val lifts = data.liftCoords.mapIndexed { i, (start, end) ->
				lift(
					id = i.toLong(),
					startLat = start.first,
					startLon = start.second,
					endLat = end.first,
					endLon = end.second,
				)
			}

			val finder: (Double, Double, Double) -> List<SkiLift> = { _, _, _ -> lifts }
			val result = SkiLiftProximityScorer.score(segments, data.locations, finder)

			result.confidenceBoost shouldBeGreaterThanOrEqual 15
			result.onKnownLift.shouldBeTrue()
		}

		@Test
		fun `fullSkiDay_noInfrastructureData_noBoosting`() {
			val data = buildThreeCycleSkiDay()
			val segments = machine.process(data.signals)

			val result = SkiLiftProximityScorer.score(segments, data.locations, emptyFinder)

			result.confidenceBoost shouldBe 0
			result.onKnownLift.shouldBeFalse()
		}
	}
}
