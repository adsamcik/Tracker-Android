package com.adsamcik.tracker.tracker.altitude

import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.math.abs

@DisplayName("AltitudeKalmanFilter")
class AltitudeKalmanFilterTest {

	private lateinit var filter: AltitudeKalmanFilter

	@BeforeEach
	fun setup() {
		filter = AltitudeKalmanFilter()
	}

	@Nested
	@DisplayName("initialization")
	inner class Initialization {
		@Test
		fun `not initialized before first update`() {
			filter.isInitialized shouldBe false
		}

		@Test
		fun `initialized after first update`() {
			filter.update(500.0, 225.0, 1000L)
			filter.isInitialized shouldBe true
		}

		@Test
		fun `first update sets altitude to measurement`() {
			filter.update(500.0, 225.0, 1000L)
			filter.altitude shouldBe 500.0
		}

		@Test
		fun `first update sets velocity to zero`() {
			filter.update(500.0, 225.0, 1000L)
			filter.verticalVelocity shouldBe 0.0
		}
	}

	@Nested
	@DisplayName("prediction")
	inner class Prediction {
		@Test
		fun `prediction with zero velocity maintains altitude`() {
			filter.update(500.0, 225.0, 1000L)
			filter.predict(2000L) // 1 second later
			abs(filter.altitude - 500.0) shouldBeLessThan 0.01
		}

		@Test
		fun `uncertainty grows with prediction`() {
			filter.update(500.0, 225.0, 1000L)
			val initialUncertainty = filter.altitudeUncertainty
			filter.predict(2000L)
			filter.altitudeUncertainty shouldBeGreaterThan initialUncertainty
		}
	}

	@Nested
	@DisplayName("measurement updates")
	inner class MeasurementUpdates {
		@Test
		fun `converges toward repeated measurements`() {
			filter.update(500.0, 225.0, 1000L)
			repeat(20) { i ->
				filter.update(510.0, 225.0, (2000L + i * 1000L))
			}
			// Should converge toward 510
			abs(filter.altitude - 510.0) shouldBeLessThan 1.0
		}

		@Test
		fun `trusts low-noise measurements more`() {
			filter.update(500.0, 225.0, 1000L)

			// High-noise update → should barely move
			filter.update(600.0, 10000.0, 2000L)
			val afterHighNoise = filter.altitude

			filter.reset()
			filter.update(500.0, 225.0, 1000L)

			// Low-noise update → should move significantly
			filter.update(600.0, 1.0, 2000L)
			val afterLowNoise = filter.altitude

			// Low noise result should be closer to 600
			abs(afterLowNoise - 600.0) shouldBeLessThan abs(afterHighNoise - 600.0)
		}

		@Test
		fun `dampens GPS spike when barometer is stable`() {
			// Initialize with steady altitude
			filter.update(500.0, 225.0, 1000L) // GPS
			filter.update(500.0, 1.0, 1000L)   // baro (low noise)

			// Barometer still reads 500
			filter.update(500.0, 1.0, 2000L)

			// GPS spikes to 530
			filter.update(530.0, 225.0, 2000L)

			// Should be much closer to 500 than 530
			abs(filter.altitude - 500.0) shouldBeLessThan 15.0
		}

		@Test
		fun `estimates vertical velocity from ascending measurements`() {
			filter.update(500.0, 10.0, 0L)
			filter.update(505.0, 10.0, 5000L) // +5m in 5s = 1 m/s
			filter.update(510.0, 10.0, 10000L) // +5m in 5s = 1 m/s

			filter.verticalVelocity shouldBeGreaterThan 0.0
		}
	}

	@Nested
	@DisplayName("reset")
	inner class Reset {
		@Test
		fun `reset clears state`() {
			filter.update(500.0, 225.0, 1000L)
			filter.reset()
			filter.isInitialized shouldBe false
		}
	}
}
