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
		engine = AltitudeFusionEngine()
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
		fun `standard atmosphere pressures convert to known altitudes after sea-level calibration`() {
			engine.calibrate(0.0, 1013.25f, 0L)

			val seaLevel = engine.pressureToAltitude(1013.25f)
			val fiveHundredMeters = engine.pressureToAltitude(954.6184f)
			val oneThousandMeters = engine.pressureToAltitude(898.7646f)

			seaLevel.shouldNotBeNull()
			fiveHundredMeters.shouldNotBeNull()
			oneThousandMeters.shouldNotBeNull()
			abs(seaLevel) shouldBeLessThan 0.1
			abs(fiveHundredMeters - 500.0) shouldBeLessThan 0.1
			abs(oneThousandMeters - 1000.0) shouldBeLessThan 0.1
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
		fun `subsequent GPS updates produce reasonable results`() {
			engine.update(gpsAltitudeMsl = 500.0, baroPressureHpa = null, timeMs = 1000L)
			val result = engine.update(gpsAltitudeMsl = 510.0, baroPressureHpa = null, timeMs = 2000L)
			result.shouldNotBeNull()
			// Kalman filter blends first and second reading; result should be between them
			result shouldBeGreaterThan 500.0
			result shouldBeLessThan 510.0
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
		fun `Kalman filter dampens GPS noise when barometer is stable`() {
			engine.update(gpsAltitudeMsl = 500.0, baroPressureHpa = 955f, timeMs = 1000L)
			engine.update(gpsAltitudeMsl = 500.0, baroPressureHpa = 955f, timeMs = 2000L)

			// GPS spike to 520m while barometer stays same (no real altitude change)
			val result = engine.update(gpsAltitudeMsl = 520.0, baroPressureHpa = 955f, timeMs = 3000L)
			result.shouldNotBeNull()

			// Kalman should trust barometer more (low noise) and dampen GPS spike
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
		fun `calibration rejects non-finite altitude and invalid pressure`() {
			engine.calibrate(Double.NaN, 955f, 1000L)
			engine.calibrate(Double.POSITIVE_INFINITY, 955f, 1000L)
			engine.calibrate(500.0, 0f, 1000L)
			engine.calibrate(500.0, -1f, 1000L)
			engine.calibrate(500.0, Float.NaN, 1000L)
			engine.calibrate(500.0, Float.POSITIVE_INFINITY, 1000L)

			engine.isCalibrated shouldBe false
			engine.currentAltitude.shouldBeNull()
		}

		@Test
		fun `pressureToAltitude returns null for invalid pressure`() {
			engine.calibrate(0.0, 1013.25f, 1000L)

			engine.pressureToAltitude(0f).shouldBeNull()
			engine.pressureToAltitude(-1f).shouldBeNull()
			engine.pressureToAltitude(Float.NaN).shouldBeNull()
			engine.pressureToAltitude(Float.POSITIVE_INFINITY).shouldBeNull()
		}

		@Test
		fun `invalid update inputs do not initialize or corrupt fused altitude`() {
			engine.update(
				gpsAltitudeMsl = Double.NaN,
				gpsVerticalAccuracyM = Float.NaN,
				baroPressureHpa = Float.NaN,
				timeMs = 1000L
			).shouldBeNull()
			engine.currentAltitude.shouldBeNull()

			engine.update(gpsAltitudeMsl = 500.0, gpsVerticalAccuracyM = 5f, timeMs = 2000L)
			engine.update(
				gpsAltitudeMsl = Double.POSITIVE_INFINITY,
				gpsVerticalAccuracyM = -1f,
				baroPressureHpa = -1f,
				timeMs = 3000L
			) shouldBe 500.0
			engine.currentAltitude shouldBe 500.0
		}

		@Test
		fun `reset clears all state`() {
			engine.update(gpsAltitudeMsl = 500.0, baroPressureHpa = 955f, timeMs = 1000L)
			engine.reset()
			engine.isCalibrated shouldBe false
			engine.currentAltitude.shouldBeNull()
		}

		@Test
		fun `calibrate with extreme altitude above atmosphere is rejected`() {
			// altitude >= 44330m makes ratio <= 0
			engine.calibrate(50000.0, 955f, 1000L)
			engine.isCalibrated shouldBe false
		}

		@Test
		fun `verticalVelocity is null before initialization`() {
			engine.verticalVelocity.shouldBeNull()
		}

		@Test
		fun `verticalVelocity available after initialization`() {
			engine.update(gpsAltitudeMsl = 500.0, baroPressureHpa = null, timeMs = 1000L)
			engine.verticalVelocity.shouldNotBeNull()
		}

		@Test
		fun `legacy 3-param overload produces same result as explicit null accuracy`() {
			val engine1 = AltitudeFusionEngine()
			val engine2 = AltitudeFusionEngine()

			// Use the 3-param overload
			val result1 = engine1.update(
				gpsAltitudeMsl = 500.0,
				baroPressureHpa = 955f,
				timeMs = 1000L
			)
			// Use the 4-param version with explicit null accuracy
			val result2 = engine2.update(
				gpsAltitudeMsl = 500.0,
				gpsVerticalAccuracyM = null,
				baroPressureHpa = 955f,
				timeMs = 1000L
			)

			result1.shouldNotBeNull()
			result2.shouldNotBeNull()
			result1 shouldBe result2
		}
	}

	@Nested
	@DisplayName("custom parameters")
	inner class CustomParameters {
		@Test
		fun `custom recalibration interval respected`() {
			val shortInterval = AltitudeFusionEngine(recalibrationIntervalMs = 1000L)
			shortInterval.calibrate(500.0, 955f, 0L)
			shortInterval.needsRecalibration(500L) shouldBe false
			shortInterval.needsRecalibration(1000L) shouldBe true
		}

		@Test
		fun `verticalAccuracy influences Kalman weighting`() {
			// Low accuracy (high noise) → result closer to prior state
			val engineHigh = AltitudeFusionEngine()
			engineHigh.update(gpsAltitudeMsl = 500.0, gpsVerticalAccuracyM = 5f, timeMs = 1000L)
			engineHigh.update(gpsAltitudeMsl = 500.0, gpsVerticalAccuracyM = 5f, timeMs = 2000L)
			val resultHigh = engineHigh.update(gpsAltitudeMsl = 550.0, gpsVerticalAccuracyM = 50f, timeMs = 3000L)

			// High accuracy (low noise) → result closer to new measurement
			val engineLow = AltitudeFusionEngine()
			engineLow.update(gpsAltitudeMsl = 500.0, gpsVerticalAccuracyM = 5f, timeMs = 1000L)
			engineLow.update(gpsAltitudeMsl = 500.0, gpsVerticalAccuracyM = 5f, timeMs = 2000L)
			val resultLow = engineLow.update(gpsAltitudeMsl = 550.0, gpsVerticalAccuracyM = 2f, timeMs = 3000L)

			resultHigh.shouldNotBeNull()
			resultLow.shouldNotBeNull()
			// Low noise result should be closer to 550
			abs(resultLow - 550.0) shouldBeLessThan abs(resultHigh - 550.0)
		}
	}
}
