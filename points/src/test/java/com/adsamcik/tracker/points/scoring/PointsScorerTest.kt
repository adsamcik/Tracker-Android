package com.adsamcik.tracker.points.scoring

import com.adsamcik.tracker.points.data.PointsScoringPolicy
import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.shared.base.database.data.DatabaseLocation
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
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
	): DatabaseLocation = DatabaseLocation(
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
		activityInfo = ActivityInfo(ON_FOOT, CONFIDENCE),
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
			p.fallbackPointsPerStep shouldBeExactly 0.01
			p.fallbackPointsPerMeter shouldBeExactly 0.005
			p.fallbackPointsPerMinute shouldBeExactly 0.5
		}
	}

	// ── Fallback scoring ────────────────────────────────────────────

	@Nested
	inner class FallbackPoints {
		@Test
		fun `returns step points when steps dominate`() {
			// 10_000 steps × 0.01 = 100
			val result = scorer.calculateFallbackPoints(
				steps = 10_000,
				distanceMeters = 0.0,
				durationMinutes = 0.0,
			)
			result shouldBeExactly 100.0
		}

		@Test
		fun `returns distance points when distance dominates`() {
			// 30_000 m × 0.005 = 150
			val result = scorer.calculateFallbackPoints(
				steps = 0,
				distanceMeters = 30_000.0,
				durationMinutes = 0.0,
			)
			result shouldBeExactly 150.0
		}

		@Test
		fun `returns duration points when duration dominates`() {
			// 120 min × 0.5 = 60
			val result = scorer.calculateFallbackPoints(
				steps = 0,
				distanceMeters = 0.0,
				durationMinutes = 120.0,
			)
			result shouldBeExactly 60.0
		}

		@Test
		fun `picks the maximum of the three`() {
			val result = scorer.calculateFallbackPoints(
				steps = 1_000,       // 10
				distanceMeters = 5_000.0, // 25
				durationMinutes = 60.0,   // 30  ← winner
			)
			result shouldBeExactly 30.0
		}

		@Test
		fun `negative inputs are clamped to zero`() {
			val result = scorer.calculateFallbackPoints(
				steps = -100,
				distanceMeters = -500.0,
				durationMinutes = -10.0,
			)
			result shouldBeExactly 0.0
		}

		@Test
		fun `custom policy is respected`() {
			val custom = PointsScoringPolicy(fallbackPointsPerStep = 1.0)
			val customScorer = PointsScorer(custom)
			val result = customScorer.calculateFallbackPoints(
				steps = 5,
				distanceMeters = 0.0,
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

	companion object {
		private const val ON_FOOT = 2
		private const val CONFIDENCE = 100
	}
}
