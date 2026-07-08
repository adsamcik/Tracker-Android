package com.adsamcik.tracker.tracker.altitude

import kotlin.math.pow

/**
 * Shared barometric altitude conversions based on the standard atmosphere formula.
 */
internal object BarometricAltitudeFormula {
	const val STANDARD_SEA_LEVEL_PRESSURE_HPA: Double = 1013.25

	private const val BAROMETRIC_CONSTANT_M = 44330.0
	private const val PRESSURE_TO_ALTITUDE_EXPONENT = 0.1903
	private const val ALTITUDE_TO_PRESSURE_EXPONENT = 1.0 / PRESSURE_TO_ALTITUDE_EXPONENT

	fun isValidPressure(pressureHpa: Float): Boolean =
		pressureHpa.isFinite() && pressureHpa > 0f

	fun pressureToAltitudeM(
		pressureHpa: Float,
		seaLevelPressureHpa: Double = STANDARD_SEA_LEVEL_PRESSURE_HPA
	): Double? {
		if (!isValidPressure(pressureHpa) || !seaLevelPressureHpa.isFinite() || seaLevelPressureHpa <= 0.0) {
			return null
		}

		val pressureRatio = pressureHpa / seaLevelPressureHpa
		val altitudeM = BAROMETRIC_CONSTANT_M * (1.0 - pressureRatio.pow(PRESSURE_TO_ALTITUDE_EXPONENT))
		return altitudeM.takeIf { it.isFinite() }
	}

	fun seaLevelPressureHpa(
		altitudeM: Double,
		pressureHpa: Float
	): Double? {
		if (!altitudeM.isFinite() || !isValidPressure(pressureHpa)) return null

		val ratio = 1.0 - altitudeM / BAROMETRIC_CONSTANT_M
		if (!ratio.isFinite() || ratio <= 0.0) return null

		val seaLevelPressureHpa = pressureHpa / ratio.pow(ALTITUDE_TO_PRESSURE_EXPONENT)
		return seaLevelPressureHpa.takeIf { it.isFinite() && it > 0.0 }
	}
}
