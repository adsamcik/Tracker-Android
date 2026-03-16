package com.adsamcik.tracker.points.work

import com.adsamcik.tracker.points.scoring.PointsScorer
import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.shared.base.database.data.DatabaseLocation
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeGreaterThan
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

class PointsWorkerCalculateSlopeTest {

	private val scorer = PointsScorer()

	private fun createLocation(
		time: Long,
		latitude: Double,
		longitude: Double,
		altitude: Double
	): Location = Location(
		time = time,
		latitude = latitude,
		longitude = longitude,
		altitude = altitude,
		horizontalAccuracy = null,
		verticalAccuracy = null,
		speed = null,
		speedAccuracy = null
	)

	private fun createDatabaseLocation(
		time: Long,
		latitude: Double,
		longitude: Double,
		altitude: Double
	): DatabaseLocation = DatabaseLocation(
		location = createLocation(time, latitude, longitude, altitude),
		activityInfo = ActivityInfo(ON_FOOT_TYPE, CONFIDENCE)
	)

	@Nested
	inner class DivisionByZeroGuards {
		@Test
		fun `identical timestamps do not crash`() {
			val sameTime = 1_000_000L
			val locations = listOf(
				createDatabaseLocation(sameTime, 50.0, 14.0, 200.0),
				createDatabaseLocation(sameTime, 50.001, 14.001, 220.0)
			)

			assertDoesNotThrow {
				scorer.calculateSlope(locations)
			}
		}

		@Test
		fun `zero distance does not crash`() {
			val locations = listOf(
				createDatabaseLocation(1_000_000L, 50.0, 14.0, 200.0),
				createDatabaseLocation(2_000_000L, 50.0, 14.0, 220.0)
			)

			assertDoesNotThrow {
				scorer.calculateSlope(locations)
			}
		}

		@Test
		fun `identical timestamps and zero distance do not crash`() {
			val sameTime = 1_000_000L
			val locations = listOf(
				createDatabaseLocation(sameTime, 50.0, 14.0, 200.0),
				createDatabaseLocation(sameTime, 50.0, 14.0, 220.0)
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
				createDatabaseLocation(1_000_000L, 50.0, 14.0, 200.0),
				createDatabaseLocation(2_000_000L, 50.001, 14.001, 220.0)
			)

			val result = scorer.calculateSlope(locations)

			// First entry is the initial zero-value entry, second is the computed one
			result shouldHaveSize 2
			val computed = result.last()
			computed.distance shouldBeGreaterThan 0.0
			computed.speedMPS shouldBeGreaterThan 0.0
		}
	}

	companion object {
		// com.google.android.gms.location.DetectedActivity.ON_FOOT
		private const val ON_FOOT_TYPE = 2
		private const val CONFIDENCE = 100
	}
}
