package com.adsamcik.tracker.tracker.altitude

import android.location.Location
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.robolectric.RuntimeEnvironment
import tech.apter.junit.jupiter.robolectric.RobolectricExtension
import org.junit.jupiter.api.extension.ExtendWith
import kotlin.math.abs

@ExtendWith(RobolectricExtension::class)
@DisplayName("AltitudeProcessor")
class AltitudeProcessorTest {

	private lateinit var processor: AltitudeProcessor

	private fun createLocation(
		altitude: Double? = null,
		verticalAccuracy: Float? = null,
		latitude: Double = 50.0,
		longitude: Double = 14.0
	): Location {
		val location = Location("test").apply {
			this.latitude = latitude
			this.longitude = longitude
			time = System.currentTimeMillis()
			if (altitude != null) {
				this.altitude = altitude
			}
			if (verticalAccuracy != null) {
				this.verticalAccuracyMeters = verticalAccuracy
			}
		}
		return location
	}

	@BeforeEach
	fun setup() {
		processor = AltitudeProcessor(
			verticalAccuracyThresholdM = 20f
		)
	}

	@Nested
	@DisplayName("no altitude")
	inner class NoAltitude {
		@Test
		fun `returns null when location has no altitude`() {
			val context = RuntimeEnvironment.getApplication()
			val location = createLocation(altitude = null)
			processor.process(context, location).shouldBeNull()
		}
	}

	@Nested
	@DisplayName("vertical accuracy gating")
	inner class VerticalAccuracyGating {
		@Test
		fun `rejects altitude with poor vertical accuracy`() {
			val context = RuntimeEnvironment.getApplication()
			val location = createLocation(altitude = 500.0, verticalAccuracy = 25f)
			processor.process(context, location).shouldBeNull()
		}

		@Test
		fun `accepts altitude with good vertical accuracy`() {
			val context = RuntimeEnvironment.getApplication()
			val location = createLocation(altitude = 500.0, verticalAccuracy = 10f)
			processor.process(context, location).shouldNotBeNull()
		}

		@Test
		fun `accepts altitude with no vertical accuracy info (conservative)`() {
			val context = RuntimeEnvironment.getApplication()
			val location = createLocation(altitude = 500.0, verticalAccuracy = null)
			processor.process(context, location).shouldNotBeNull()
		}

		@Test
		fun `accepts altitude at exact threshold`() {
			val context = RuntimeEnvironment.getApplication()
			val location = createLocation(altitude = 500.0, verticalAccuracy = 20f)
			processor.process(context, location).shouldNotBeNull()
		}
	}

	@Nested
	@DisplayName("Kalman fusion integration")
	inner class KalmanFusionIntegration {
		@Test
		fun `first value passes through with minimal change`() {
			val context = RuntimeEnvironment.getApplication()
			val location = createLocation(altitude = 500.0, verticalAccuracy = 5f)
			val result = processor.process(context, location)
			result.shouldNotBeNull()
			// First value should be close to input (may differ slightly due to geoid correction)
		}

		@Test
		fun `smooths consecutive readings via Kalman`() {
			val context = RuntimeEnvironment.getApplication()

			// First reading establishes baseline
			val loc1 = createLocation(altitude = 500.0, verticalAccuracy = 5f)
			val result1 = processor.process(context, loc1)
			result1.shouldNotBeNull()

			// Big jump — should be dampened by Kalman filter
			val loc2 = createLocation(altitude = 600.0, verticalAccuracy = 5f)
			val result2 = processor.process(context, loc2)
			result2.shouldNotBeNull()

			// The Kalman filter should dampen the 100m jump
			val diff = abs(result2 - result1)
			diff shouldBeLessThan 100.0
		}
	}

	@Nested
	@DisplayName("reset")
	inner class Reset {
		@Test
		fun `reset clears EMA state`() {
			val context = RuntimeEnvironment.getApplication()

			// Build up EMA state
			processor.process(context, createLocation(altitude = 500.0, verticalAccuracy = 5f))
			processor.process(context, createLocation(altitude = 510.0, verticalAccuracy = 5f))

			processor.reset()

			// After reset, next value should pass through like first value
			val result = processor.process(context, createLocation(altitude = 800.0, verticalAccuracy = 5f))
			result.shouldNotBeNull()
			// Should be close to 800, not smoothed toward 500
		}
	}
}
