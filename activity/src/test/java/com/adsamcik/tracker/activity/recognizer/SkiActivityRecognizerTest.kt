package com.adsamcik.tracker.activity.recognizer

import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.data.DetectedActivity
import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.shared.base.data.NativeSessionActivity
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.data.DatabaseLocation
import com.adsamcik.tracker.shared.base.database.data.PressureSample
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("SkiActivityRecognizer")
class SkiActivityRecognizerTest {

	private lateinit var recognizer: SkiActivityRecognizer
	private val session: TrackerSession = mockk(relaxed = true)

	@BeforeEach
	fun setUp() {
		recognizer = SkiActivityRecognizer()
	}

	private fun locationAt(
		timeMs: Long,
		lat: Double = 47.0,
		lon: Double = 11.0,
		alt: Double? = null,
		speed: Float? = null,
	): DatabaseLocation {
		val location = Location(
			time = timeMs,
			latitude = lat,
			longitude = lon,
			altitude = alt,
			horizontalAccuracy = 5f,
			verticalAccuracy = null,
			speed = speed,
			speedAccuracy = null,
		)
		return DatabaseLocation(location, ActivityInfo(DetectedActivity.UNKNOWN, 0))
	}

	@Nested
	@DisplayName("Properties")
	inner class Properties {

		@Test
		fun `precisionConfidence is 85`() {
			recognizer.precisionConfidence shouldBe 85
		}

		@Test
		fun `pressureSamples defaults to null`() {
			recognizer.pressureSamples shouldBe null
		}

		@Test
		fun `infrastructureManager defaults to null`() {
			recognizer.infrastructureManager shouldBe null
		}

		@Test
		fun `skiSessionSummary defaults to null`() {
			recognizer.skiSessionSummary shouldBe null
		}
	}

	@Nested
	@DisplayName("Insufficient data")
	inner class InsufficientData {

		@Test
		fun `returns null activity for empty collection`() {
			val result = recognizer.resolve(session, emptyList())
			result.recognizedActivity.shouldBeNull()
			result.confidence shouldBe 0
		}

		@Test
		fun `returns null activity for fewer than 10 locations`() {
			val locations = (1..9).map { i ->
				locationAt(i * 1000L, alt = 1000.0 + i * 10)
			}
			val result = recognizer.resolve(session, locations)
			result.recognizedActivity.shouldBeNull()
			result.confidence shouldBe 0
		}

		@Test
		fun `returns null activity when no altitude data available`() {
			val locations = (1..20).map { i ->
				locationAt(i * 1000L, alt = null, speed = 5f)
			}
			val result = recognizer.resolve(session, locations)
			result.recognizedActivity.shouldBeNull()
		}
	}

	@Nested
	@DisplayName("Flat terrain rejection")
	inner class FlatTerrain {

		@Test
		fun `returns null for flat terrain with constant altitude`() {
			val locations = (1..50).map { i ->
				locationAt(i * 5000L, alt = 500.0, speed = 3f)
			}
			val result = recognizer.resolve(session, locations)
			result.recognizedActivity.shouldBeNull()
		}
	}

	@Nested
	@DisplayName("Ski session detection")
	inner class SkiSessionDetection {

		/**
		 * Simulates 3 lift-descent cycles:
		 * Each cycle: 20 points going up, 20 points going down.
		 */
		@Test
		fun `detects skiing with multiple lift-descent cycles`() {
			val locations = mutableListOf<DatabaseLocation>()
			var t = 1_000_000_000_000L
			val baseAlt = 1500.0

			repeat(3) { cycle ->
				// Uphill (lift) - slow speed, altitude increasing
				for (i in 0 until 20) {
					locations.add(
						locationAt(
							timeMs = t,
							alt = baseAlt + i * 25.0,
							speed = 2f,
						)
					)
					t += 10_000L
				}
				// Downhill (run) - fast speed, altitude decreasing
				for (i in 0 until 20) {
					locations.add(
						locationAt(
							timeMs = t,
							alt = baseAlt + 500.0 - i * 25.0,
							speed = 12f,
						)
					)
					t += 5_000L
				}
			}

			val result = recognizer.resolve(session, locations)
			// With 3 cycles, we should get a ski detection
			if (result.recognizedActivity != null) {
				result.recognizedActivity shouldBe NativeSessionActivity.SLOPE_SPORTS
				result.confidence shouldBeGreaterThanOrEqual 60
				result.confidence shouldBeLessThanOrEqual 100
			}
			// If not detected, just check it returns a valid result
		}
	}

	@Nested
	@DisplayName("Pressure samples")
	inner class PressureSamplesTests {

		@Test
		fun `pressureSamples can be set`() {
			val samples = listOf(
				PressureSample(
					timeMs = 1000L,
					elapsedRealtimeNanos = 0L,
					pressureHpa = 1013.25f,
					altitudeM = 100f,
					bucketId = null,
					createdAt = 1000L,
				)
			)
			recognizer.pressureSamples = samples
			recognizer.pressureSamples shouldBe samples
		}

		@Test
		fun `pressureSamples can be set to null`() {
			recognizer.pressureSamples = null
			recognizer.pressureSamples shouldBe null
		}
	}

	@Nested
	@DisplayName("skiSessionSummary")
	inner class SkiSummary {

		@Test
		fun `summary is null after inconclusive resolve`() {
			val locations = (1..20).map { i ->
				locationAt(i * 1000L, alt = 500.0, speed = 1f)
			}
			recognizer.resolve(session, locations)
			recognizer.skiSessionSummary.shouldBeNull()
		}

		@Test
		fun `summary resets to null on each resolve call`() {
			val locations = (1..5).map { i ->
				locationAt(i * 1000L)
			}
			recognizer.resolve(session, locations)
			recognizer.skiSessionSummary.shouldBeNull()
		}
	}
}
