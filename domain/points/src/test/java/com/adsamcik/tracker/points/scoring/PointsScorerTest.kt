package com.adsamcik.tracker.points.scoring

import com.adsamcik.tracker.points.data.PointsScoringPolicy
import com.adsamcik.tracker.points.scoring.PointsScorer.ScoringLocation
import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.model.Location
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class PointsScorerTest {

	private val defaultPolicy = PointsScoringPolicy()
	private val scorer = PointsScorer(defaultPolicy)

	// ── Helpers ──────────────────────────────────────────────────────

	private fun dbLoc(
		time: Long,
		lat: Double,
		lon: Double,
		alt: Double,
	): ScoringLocation = ScoringLocation(
		location = Location(
			time = time,
			latitude = lat,
			longitude = lon,
			altitude = alt,
			horizontalAccuracy = null,
			verticalAccuracy = null,
			speed = null,
			speedAccuracy = null,
		),
		activity = ActivityInfo(ON_FOOT, CONFIDENCE),
	)

	// ── Policy defaults ─────────────────────────────────────────────

	@Nested
	inner class PolicyDefaults {
		@Test
		fun `default policy values match original constants`() {
			val p = PointsScoringPolicy()
			p.pointsPerMeterMps shouldBeExactly 0.01
			p.slopeMultiplier shouldBeExactly 12.0
			p.halfSlope shouldBeExactly kotlin.math.PI / 4
			p.altitudeThreshold shouldBeExactly 10.0
			p.fallbackPointsPerMeter shouldBeExactly 0.005
			p.fallbackPointsPerMinute shouldBeExactly 0.5
		}
	}

	// ── Fallback scoring ────────────────────────────────────────────

	@Nested
	inner class FallbackPoints {
		@Test
		fun `returns distance points when distance dominates`() {
			// 30_000 m × 0.005 = 150
			val result = scorer.calculateFallbackPoints(
				distanceMeters = 30_000.0,
				durationMinutes = 0.0,
			)
			result shouldBeExactly 150.0
		}

		@Test
		fun `returns duration points when duration dominates`() {
			// 120 min × 0.5 = 60
			val result = scorer.calculateFallbackPoints(
				distanceMeters = 0.0,
				durationMinutes = 120.0,
			)
			result shouldBeExactly 60.0
		}

		@Test
		fun `picks the maximum of distance and duration`() {
			val result = scorer.calculateFallbackPoints(
				distanceMeters = 5_000.0, // 25
				durationMinutes = 60.0,   // 30  ← winner
			)
			result shouldBeExactly 30.0
		}

		@Test
		fun `negative inputs are clamped to zero`() {
			val result = scorer.calculateFallbackPoints(
				distanceMeters = -500.0,
				durationMinutes = -10.0,
			)
			result shouldBeExactly 0.0
		}

		@Test
		fun `custom non-step policy is respected`() {
			val custom = PointsScoringPolicy(fallbackPointsPerMeter = 1.0)
			val customScorer = PointsScorer(custom)
			val result = customScorer.calculateFallbackPoints(
				distanceMeters = 5.0,
				durationMinutes = 0.0,
			)
			result shouldBeExactly 5.0
		}
	}

	// ── Slope points ────────────────────────────────────────────────

	@Nested
	inner class SlopePoints {
		@Test
		fun `two-point uphill walk yields positive points`() {
			val locations = listOf(
				dbLoc(1_000_000L, 50.0, 14.0, 200.0),
				dbLoc(2_000_000L, 50.001, 14.001, 220.0),
			)
			val points = scorer.calculateSlopePoints(locations)
			points shouldBeGreaterThan 0.0
		}

		@Test
		fun `flat walk yields points without slope bonus`() {
			val locations = listOf(
				dbLoc(1_000_000L, 50.0, 14.0, 200.0),
				dbLoc(2_000_000L, 50.001, 14.001, 200.0),
			)
			val flatPoints = scorer.calculateSlopePoints(locations)

			val uphillLocations = listOf(
				dbLoc(1_000_000L, 50.0, 14.0, 200.0),
				dbLoc(2_000_000L, 50.001, 14.001, 220.0),
			)
			val uphillPoints = scorer.calculateSlopePoints(uphillLocations)

			// Uphill should earn strictly more than flat
			uphillPoints shouldBeGreaterThan flatPoints
		}

		@Test
		fun `zero-time or zero-distance segments contribute zero points`() {
			val locations = listOf(
				dbLoc(1_000_000L, 50.0, 14.0, 200.0),
				dbLoc(1_000_000L, 50.0, 14.0, 220.0),
			)
			val points = scorer.calculateSlopePoints(locations)
			points shouldBeLessThanOrEqual 0.0
		}
	}

	// ── Speed cap ──────────────────────────────────────────────────

	@Nested
	inner class SpeedCap {

		/**
		 * Builds a two-point segment with a controlled speed by placing
		 * the second point [speedMps] × 1000 meters east and 1 second later,
		 * on flat terrain so only the speed multiplier matters.
		 */
		private fun flatSegmentLocations(speedMps: Double): List<ScoringLocation> {
			// distance ≈ speedMps * timeDelta; timeDelta = 1000s gives
			// nice round numbers and keeps lat/lon close enough for the
			// Haversine approximation.
			val timeDeltaMs = 1_000_000L          // 1 000 s
			val distanceMeters = speedMps * (timeDeltaMs / 1000.0)
			// ~1 degree latitude ≈ 111 320 m
			val latOffset = distanceMeters / 111_320.0
			return listOf(
				dbLoc(0L, 50.0, 14.0, 200.0),
				dbLoc(timeDeltaMs, 50.0 + latOffset, 14.0, 200.0),
			)
		}

		@Test
		fun `normal walking speed is not capped`() {
			val walkSpeed = 5.0  // m/s
			val locations = flatSegmentLocations(walkSpeed)
			val points = scorer.calculateSlopePoints(locations)
			points shouldBeGreaterThan 0.0

			// Manually verify the speed wasn't capped by comparing to
			// a cap-boundary run — walking should produce fewer points.
			val capLocations = flatSegmentLocations(PointsScorer.MAX_SCORING_SPEED_MPS)
			val capPoints = scorer.calculateSlopePoints(capLocations)
			points shouldBeLessThanOrEqual capPoints
		}

		@Test
		fun `impossible speed is capped to MAX_SCORING_SPEED_MPS`() {
			val impossibleSpeed = 500.0  // m/s — teleportation artifact
			val cappedSpeed = PointsScorer.MAX_SCORING_SPEED_MPS

			val impossibleLocations = flatSegmentLocations(impossibleSpeed)
			val cappedLocations = flatSegmentLocations(cappedSpeed)

			val impossiblePoints = scorer.calculateSlopePoints(impossibleLocations)
			val cappedPoints = scorer.calculateSlopePoints(cappedLocations)

			// Points from impossible speed should NOT scale with 500 m/s;
			// they should be comparable to the cap (distance differs, but
			// the speed multiplier must be identical).
			impossiblePoints shouldBeGreaterThan 0.0

			// The speed multiplier is capped, so points per meter of distance
			// should be equal. Extract the effective multiplier:
			// points = distance * ppmMps * speed * 1.0  (flat, no slope bonus)
			val segments500 = scorer.calculateSlope(impossibleLocations)
			val segments50 = scorer.calculateSlope(cappedLocations)
			val dist500 = segments500.sumOf { it.distance }
			val dist50 = segments50.sumOf { it.distance }

			val pointsPerMeter500 = impossiblePoints / dist500
			val pointsPerMeter50 = cappedPoints / dist50
			// Both should use 50 m/s as effective speed → same points/m
			// (small tolerance for Haversine approximation at different scales)
			pointsPerMeter500 shouldBe (pointsPerMeter50 plusOrMinus 1e-3)
		}

		@Test
		fun `speed exactly at cap is not reduced`() {
			val atCapSpeed = PointsScorer.MAX_SCORING_SPEED_MPS
			val slightlyBelow = atCapSpeed - 0.01

			val atCapPoints = scorer.calculateSlopePoints(flatSegmentLocations(atCapSpeed))
			val belowCapPoints = scorer.calculateSlopePoints(flatSegmentLocations(slightlyBelow))

			// At-cap should produce slightly more points than slightly below
			atCapPoints shouldBeGreaterThan belowCapPoints
		}
	}

	companion object {
		private const val ON_FOOT = 2
		private const val CONFIDENCE = 100
	}
}
