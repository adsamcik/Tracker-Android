package com.adsamcik.tracker.points.scoring

import com.adsamcik.tracker.points.scoring.PointsScorer.ScoringLocation
import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.model.Location
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeGreaterThan
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

class PointsScorerSlopeGuardTest {

	private val scorer = PointsScorer()

	private fun createLocation(
		time: Long,
		latitude: Double,
		longitude: Double,
		altitude: Double,
	): Location = Location(
		time = time,
		latitude = latitude,
		longitude = longitude,
		altitude = altitude,
		horizontalAccuracy = null,
		verticalAccuracy = null,
		speed = null,
		speedAccuracy = null,
	)

	private fun createScoringLocation(
		time: Long,
		latitude: Double,
		longitude: Double,
		altitude: Double,
	): ScoringLocation = ScoringLocation(
		location = createLocation(time, latitude, longitude, altitude),
		activity = ActivityInfo(ON_FOOT_TYPE, CONFIDENCE),
	)

	@Nested
	inner class DivisionByZeroGuards {
		@Test
		fun `identical timestamps do not crash`() {
			val sameTime = 1_000_000L
			val locations = listOf(
				createScoringLocation(sameTime, 50.0, 14.0, 200.0),
				createScoringLocation(sameTime, 50.001, 14.001, 220.0),
			)

			assertDoesNotThrow {
				scorer.calculateSlope(locations)
			}
		}

		@Test
		fun `zero distance does not crash`() {
			val locations = listOf(
				createScoringLocation(1_000_000L, 50.0, 14.0, 200.0),
				createScoringLocation(2_000_000L, 50.0, 14.0, 220.0),
			)

			assertDoesNotThrow {
				scorer.calculateSlope(locations)
			}
		}

		@Test
		fun `identical timestamps and zero distance do not crash`() {
			val sameTime = 1_000_000L
			val locations = listOf(
				createScoringLocation(sameTime, 50.0, 14.0, 200.0),
				createScoringLocation(sameTime, 50.0, 14.0, 220.0),
			)

			assertDoesNotThrow {
				scorer.calculateSlope(locations)
			}
		}
	}

	@Nested
	inner class NormalCalculation {
		@Test
		fun `normal data produces slope entries with positive distance and speed`() {
			val locations = listOf(
				createScoringLocation(1_000_000L, 50.0, 14.0, 200.0),
				createScoringLocation(2_000_000L, 50.001, 14.001, 220.0),
			)

			val result = scorer.calculateSlope(locations)

			// First entry is the initial zero-value entry, second is the computed one.
			result shouldHaveSize 2
			val computed = result.last()
			computed.distance shouldBeGreaterThan 0.0
			computed.speedMPS shouldBeGreaterThan 0.0
		}
	}

	private companion object {
		// com.google.android.gms.location.DetectedActivity.ON_FOOT
		const val ON_FOOT_TYPE = 2
		const val CONFIDENCE = 100
	}
}
