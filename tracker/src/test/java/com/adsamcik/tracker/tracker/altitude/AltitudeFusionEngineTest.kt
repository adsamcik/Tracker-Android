package com.adsamcik.tracker.tracker.altitude

import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.math.abs

@DisplayName("AltitudeFusionEngine")
class AltitudeFusionEngineTest {

	private lateinit var engine: AltitudeFusionEngine

	@BeforeEach
	fun setup() {
		engine = AltitudeFusionEngine(alpha = 0.98)
	}

	@Nested
	@DisplayName("calibration")
	inner class Calibration {
		@Test
		fun `not calibrated initially`() {
			engine.isCalibrated shouldBe false
		}

		@Test
		fun `calibrated after providing GPS and pressure`() {
			engine.calibrate(500.0, 955f, 1000L)
			engine.isCalibrated shouldBe true
		}

		@Test
		fun `pressureToAltitude returns null before calibration`() {
			engine.pressureToAltitude(955f).shouldBeNull()
		}

		@Test
		fun `pressureToAltitude returns value after calibration`() {
			engine.calibrate(500.0, 955f, 1000L)
			val altitude = engine.pressureToAltitude(955f)
			altitude.shouldNotBeNull()
			// Should be very close to the calibration GPS altitude
			abs(altitude - 500.0) shouldBeLessThan 1.0
		}

		@Test
		fun `calibration makes barometer match GPS altitude`() {
			val gpsAlt = 350.0
			val pressure = 970f
			engine.calibrate(gpsAlt, pressure, 1000L)
			val baroAlt = engine.pressureToAltitude(pressure)
			baroAlt.shouldNotBeNull()
			abs(baroAlt - gpsAlt) shouldBeLessThan 0.1
		}

		@Test
		fun `needsRecalibration returns true when not calibrated`() {
			engine.needsRecalibration(0L) shouldBe true
		}

		@Test
		fun `needsRecalibration returns false right after calibration`() {
			engine.calibrate(500.0, 955f, 1000L)
			engine.needsRecalibration(1000L) shouldBe false
		}

		@Test
		fun `needsRecalibration returns true after interval`() {
			engine.calibrate(500.0, 955f, 1000L)
			val afterInterval = 1000L + AltitudeFusionEngine.DEFAULT_RECALIBRATION_INTERVAL_MS
			engine.needsRecalibration(afterInterval) shouldBe true
		}
	}

	@Nested
	@DisplayName("update with GPS only")
	inner class GpsOnly {
		@Test
		fun `first GPS update initializes fused altitude`() {
			val result = engine.update(gpsAltitudeMsl = 500.0, baroPressureHpa = null, timeMs = 1000L)
			result.shouldNotBeNull()
			result shouldBe 500.0
		}

		@Test
		fun `subsequent GPS updates blend with previous`() {
			engine.update(gpsAltitudeMsl = 500.0, baroPressureHpa = null, timeMs = 1000L)
			val result = engine.update(gpsAltitudeMsl = 510.0, baroPressureHpa = null, timeMs = 2000L)
			result.shouldNotBeNull()
			// With alpha=0.98: 0.98*500 + 0.02*510 = 500.2
			abs(result - 500.2) shouldBeLessThan 0.01
		}
	}

	@Nested
	@DisplayName("full fusion (GPS + barometer)")
	inner class FullFusion {
		@Test
		fun `barometer tracks relative changes between GPS updates`() {
			// Initial GPS+baro calibration
			engine.update(gpsAltitudeMsl = 500.0, baroPressureHpa = 955f, timeMs = 1000L)

			// Barometer detects 10m ascent (pressure decreases ~1.2 hPa per 10m at this altitude)
			// First compute what pressure would give 510m with our calibration
			val newPressure = 955f - 1.2f // approximate
			val result = engine.update(gpsAltitudeMsl = null, baroPressureHpa = newPressure, timeMs = 2000L)

			result.shouldNotBeNull()
			// Should track the barometer change since it's the only new data (Case 4)
			result shouldBeGreaterThan 500.0
		}

		@Test
		fun `GPS corrects barometer drift`() {
			// Calibrate
			engine.update(gpsAltitudeMsl = 500.0, baroPressureHpa = 955f, timeMs = 1000L)

			// Simulate barometer-only updates (baro drifting slightly)
			engine.update(gpsAltitudeMsl = null, baroPressureHpa = 955f, timeMs = 2000L)
			engine.update(gpsAltitudeMsl = null, baroPressureHpa = 955f, timeMs = 3000L)

			// GPS comes back with same altitude — should correct any small drift
			val result = engine.update(gpsAltitudeMsl = 500.0, baroPressureHpa = 955f, timeMs = 4000L)
			result.shouldNotBeNull()
			abs(result - 500.0) shouldBeLessThan 1.0
		}

		@Test
		fun `complementary filter dampens GPS noise`() {
			engine.update(gpsAltitudeMsl = 500.0, baroPressureHpa = 955f, timeMs = 1000L)
			engine.update(gpsAltitudeMsl = 500.0, baroPressureHpa = 955f, timeMs = 2000L)

			// GPS spike to 520m while barometer stays same (no real altitude change)
			val result = engine.update(gpsAltitudeMsl = 520.0, baroPressureHpa = 955f, timeMs = 3000L)
			result.shouldNotBeNull()

			// Should be much closer to 500 than to 520 (alpha=0.98 trusts baro)
			abs(result - 500.0) shouldBeLessThan abs(result - 520.0)
		}
	}

	@Nested
	@DisplayName("edge cases")
	inner class EdgeCases {
		@Test
		fun `returns null with no data`() {
			engine.update(gpsAltitudeMsl = null, baroPressureHpa = null, timeMs = 1000L).shouldBeNull()
		}

		@Test
		fun `returns null with only barometer before calibration`() {
			engine.update(gpsAltitudeMsl = null, baroPressureHpa = 955f, timeMs = 1000L).shouldBeNull()
		}

		@Test
		fun `reset clears all state`() {
			engine.update(gpsAltitudeMsl = 500.0, baroPressureHpa = 955f, timeMs = 1000L)
			engine.reset()
			engine.isCalibrated shouldBe false
			engine.currentAltitude.shouldBeNull()
		}
	}
}
