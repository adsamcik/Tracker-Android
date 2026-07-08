package com.adsamcik.tracker.tracker.altitude

import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.math.abs

@DisplayName("BarometricAltitudeFormula")
class BarometricAltitudeFormulaTest {

	@Nested
	@DisplayName("pressure to altitude")
	inner class PressureToAltitude {
		@Test
		fun `standard atmosphere pressures convert to known altitudes`() {
			val seaLevel = BarometricAltitudeFormula.pressureToAltitudeM(1013.25f)
			val fiveHundredMeters = BarometricAltitudeFormula.pressureToAltitudeM(954.6184f)
			val oneThousandMeters = BarometricAltitudeFormula.pressureToAltitudeM(898.7646f)

			seaLevel.shouldNotBeNull()
			fiveHundredMeters.shouldNotBeNull()
			oneThousandMeters.shouldNotBeNull()
			abs(seaLevel) shouldBeLessThan 0.1
			abs(fiveHundredMeters - 500.0) shouldBeLessThan 0.1
			abs(oneThousandMeters - 1000.0) shouldBeLessThan 0.1
		}

		@Test
		fun `invalid pressure inputs return null`() {
			BarometricAltitudeFormula.pressureToAltitudeM(0f).shouldBeNull()
			BarometricAltitudeFormula.pressureToAltitudeM(-1f).shouldBeNull()
			BarometricAltitudeFormula.pressureToAltitudeM(Float.NaN).shouldBeNull()
			BarometricAltitudeFormula.pressureToAltitudeM(Float.POSITIVE_INFINITY).shouldBeNull()
		}
	}

	@Nested
	@DisplayName("sea-level pressure")
	inner class SeaLevelPressure {
		@Test
		fun `known altitude and pressure derive calibrated sea-level pressure`() {
			val seaLevelAtZero = BarometricAltitudeFormula.seaLevelPressureHpa(
				altitudeM = 0.0,
				pressureHpa = 1013.25f
			)
			val seaLevelAtFiveHundredMeters = BarometricAltitudeFormula.seaLevelPressureHpa(
				altitudeM = 500.0,
				pressureHpa = 954.6184f
			)

			seaLevelAtZero.shouldNotBeNull()
			seaLevelAtFiveHundredMeters.shouldNotBeNull()
			abs(seaLevelAtZero - 1013.25) shouldBeLessThan 0.01
			abs(seaLevelAtFiveHundredMeters - 1013.25) shouldBeLessThan 0.01
		}

		@Test
		fun `invalid calibration inputs return null`() {
			BarometricAltitudeFormula.seaLevelPressureHpa(Double.NaN, 955f).shouldBeNull()
			BarometricAltitudeFormula.seaLevelPressureHpa(Double.POSITIVE_INFINITY, 955f).shouldBeNull()
			BarometricAltitudeFormula.seaLevelPressureHpa(500.0, 0f).shouldBeNull()
			BarometricAltitudeFormula.seaLevelPressureHpa(500.0, -1f).shouldBeNull()
			BarometricAltitudeFormula.seaLevelPressureHpa(500.0, Float.NaN).shouldBeNull()
			BarometricAltitudeFormula.seaLevelPressureHpa(500.0, Float.POSITIVE_INFINITY).shouldBeNull()
		}
	}
}
