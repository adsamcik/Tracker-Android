package com.adsamcik.tracker.points.data

/**
 * Tunable constants that govern how session points are calculated.
 * Extracted from PointsWorker so the scoring algorithm can be tested
 * and adjusted independently of orchestration logic.
 */
data class PointsScoringPolicy(
	val pointsPerMeterMps: Double = DEFAULT_POINTS_PER_METER_MPS,
	val slopeMultiplier: Double = DEFAULT_SLOPE_MULTIPLIER,
	val halfSlope: Double = DEFAULT_HALF_SLOPE,
	val altitudeThreshold: Double = DEFAULT_ALTITUDE_THRESHOLD,
	val fallbackPointsPerMeter: Double = DEFAULT_FALLBACK_POINTS_PER_METER,
	val fallbackPointsPerMinute: Double = DEFAULT_FALLBACK_POINTS_PER_MINUTE,
) {
	companion object {
		private const val DEFAULT_POINTS_PER_METER_MPS = 0.01
		private const val DEFAULT_SLOPE_MULTIPLIER = 12.0
		private val DEFAULT_HALF_SLOPE = kotlin.math.PI / 4
		private const val DEFAULT_ALTITUDE_THRESHOLD = 10.0
		private const val DEFAULT_FALLBACK_POINTS_PER_METER = 0.005
		private const val DEFAULT_FALLBACK_POINTS_PER_MINUTE = 0.5
	}
}
