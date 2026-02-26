package com.adsamcik.tracker.tracker.altitude

import kotlin.math.pow

/**
 * Fuses GPS altitude with barometric pressure using a 1D Kalman filter.
 *
 * GPS provides absolute altitude (noisy, ~10-30m error, no drift).
 * Barometer provides relative altitude changes (smooth, ~0.5m noise, but drifts with weather).
 *
 * The Kalman filter optimally weights each source based on measurement noise:
 * - GPS: high noise (uses verticalAccuracy² or default 225 m²)
 * - Barometer: low noise (~1 m²), calibrated against GPS baseline
 *
 * Includes GPS-calibrated barometer baseline: uses the first good GPS fix to
 * determine local sea-level pressure, eliminating weather-dependent absolute error.
 * Recalibrates periodically to compensate for barometer drift.
 *
 * Thread-safety: NOT thread-safe. Use from a single coroutine context.
 */
internal class AltitudeFusionEngine(
	private val recalibrationIntervalMs: Long = DEFAULT_RECALIBRATION_INTERVAL_MS,
	private val defaultGpsMeasurementNoiseM2: Double = DEFAULT_GPS_MEASUREMENT_NOISE_M2,
	private val baroMeasurementNoiseM2: Double = DEFAULT_BARO_MEASUREMENT_NOISE_M2
) {
	private val kalmanFilter = AltitudeKalmanFilter()

	// Calibrated sea-level pressure (derived from GPS + barometer at calibration time)
	private var calibratedSeaLevelPressureHpa: Double? = null
	private var lastCalibrationTimeMs: Long = 0L

	// Previous barometer altitude for change tracking
	private var previousBaroAltitudeM: Double? = null

	/**
	 * Whether the fusion engine has been calibrated with at least one GPS fix.
	 */
	val isCalibrated: Boolean
		get() = calibratedSeaLevelPressureHpa != null

	/**
	 * The current fused altitude estimate, or null if not yet initialized.
	 */
	val currentAltitude: Double?
		get() = if (kalmanFilter.isInitialized) kalmanFilter.altitude else null

	/**
	 * Current vertical velocity estimate in m/s, or null if not initialized.
	 */
	val verticalVelocity: Double?
		get() = if (kalmanFilter.isInitialized) kalmanFilter.verticalVelocity else null

	/**
	 * Calibrate the barometer baseline using a known GPS altitude.
	 * Back-calculates the local sea-level pressure so barometer readings
	 * produce correct absolute altitude.
	 *
	 * @param gpsAltitudeMsl GPS altitude in meters above MSL.
	 * @param currentPressureHpa Current barometer pressure reading in hPa.
	 * @param timeMs Current time in milliseconds.
	 */
	fun calibrate(gpsAltitudeMsl: Double, currentPressureHpa: Float, timeMs: Long) {
		// Inverse of barometric formula: P0 = P / (1 - alt/44330)^(1/0.1903)
		val ratio = 1.0 - gpsAltitudeMsl / BAROMETRIC_CONSTANT
		if (ratio <= 0.0) return // Invalid altitude (above atmosphere)
		calibratedSeaLevelPressureHpa = currentPressureHpa / ratio.pow(BAROMETRIC_EXPONENT)
		lastCalibrationTimeMs = timeMs
	}

	/**
	 * Returns whether recalibration is needed based on elapsed time.
	 */
	fun needsRecalibration(currentTimeMs: Long): Boolean {
		val seaLevel = calibratedSeaLevelPressureHpa ?: return true
		return (currentTimeMs - lastCalibrationTimeMs) >= recalibrationIntervalMs
	}

	/**
	 * Convert barometer pressure to altitude using the calibrated baseline.
	 * Returns null if not calibrated.
	 */
	fun pressureToAltitude(pressureHpa: Float): Double? {
		val seaLevel = calibratedSeaLevelPressureHpa ?: return null
		return BAROMETRIC_CONSTANT * (1.0 - (pressureHpa / seaLevel).pow(BAROMETRIC_INV_EXPONENT))
	}

	/**
	 * Update the fused altitude with new GPS and/or barometer data.
	 *
	 * Both parameters are nullable — the engine handles partial updates gracefully.
	 *
	 * @param gpsAltitudeMsl GPS altitude in meters above MSL (already geoid-corrected).
	 *                       Null if GPS altitude unavailable or failed quality gate.
	 * @param gpsVerticalAccuracyM GPS vertical accuracy in meters (68% confidence).
	 *                             Null to use default noise.
	 * @param baroPressureHpa Current barometer pressure in hPa.
	 *                        Null if barometer unavailable.
	 * @param timeMs Current time in milliseconds.
	 * @return The fused altitude estimate, or null if insufficient data.
	 */
	fun update(
		gpsAltitudeMsl: Double?,
		gpsVerticalAccuracyM: Float? = null,
		baroPressureHpa: Float? = null,
		timeMs: Long
	): Double? {
		// Try to calibrate/recalibrate if we have both GPS and barometer
		if (gpsAltitudeMsl != null && baroPressureHpa != null) {
			if (!isCalibrated || needsRecalibration(timeMs)) {
				calibrate(gpsAltitudeMsl, baroPressureHpa, timeMs)
			}
		}

		// Run prediction step if filter is already initialized
		if (kalmanFilter.isInitialized) {
			kalmanFilter.predict(timeMs)
		}

		// GPS measurement update
		if (gpsAltitudeMsl != null) {
			val gpsNoise = if (gpsVerticalAccuracyM != null) {
				(gpsVerticalAccuracyM * gpsVerticalAccuracyM).toDouble()
			} else {
				defaultGpsMeasurementNoiseM2
			}
			kalmanFilter.update(gpsAltitudeMsl, gpsNoise, timeMs)
		}

		// Barometer measurement update
		val baroAltitude = baroPressureHpa?.let { pressureToAltitude(it) }
		if (baroAltitude != null) {
			kalmanFilter.update(baroAltitude, baroMeasurementNoiseM2, timeMs)
		}

		// Track barometer altitude for external consumers
		if (baroAltitude != null) {
			previousBaroAltitudeM = baroAltitude
		}

		return currentAltitude
	}

	/**
	 * Backward-compatible update overload (no vertical accuracy parameter).
	 */
	fun update(
		gpsAltitudeMsl: Double?,
		baroPressureHpa: Float?,
		timeMs: Long
	): Double? = update(
		gpsAltitudeMsl = gpsAltitudeMsl,
		gpsVerticalAccuracyM = null,
		baroPressureHpa = baroPressureHpa,
		timeMs = timeMs
	)

	/**
	 * Resets all state. Call when starting a new tracking session.
	 */
	fun reset() {
		calibratedSeaLevelPressureHpa = null
		lastCalibrationTimeMs = 0L
		previousBaroAltitudeM = null
		kalmanFilter.reset()
	}

	companion object {
		/**
		 * Default GPS measurement noise variance (σ² in m²).
		 * 225 = 15m σ, which is typical for consumer GPS vertical accuracy.
		 */
		const val DEFAULT_GPS_MEASUREMENT_NOISE_M2 = 225.0

		/**
		 * Default barometer measurement noise variance (σ² in m²).
		 * 1.0 = 1m σ, reflecting calibrated barometer accuracy.
		 */
		const val DEFAULT_BARO_MEASUREMENT_NOISE_M2 = 1.0

		/**
		 * Default recalibration interval: 5 minutes.
		 * Barometer drift is typically ~1 hPa/hour (~8m/hour),
		 * so recalibrating every 5 minutes limits drift error to ~0.7m.
		 */
		const val DEFAULT_RECALIBRATION_INTERVAL_MS = 5L * 60L * 1000L

		/** Constant in the barometric altitude formula. */
		private const val BAROMETRIC_CONSTANT = 44330.0

		/** Exponent in the barometric formula: pressure → altitude. */
		private const val BAROMETRIC_INV_EXPONENT = 0.1903

		/** Inverse exponent for altitude → pressure back-calculation. */
		private const val BAROMETRIC_EXPONENT = 1.0 / BAROMETRIC_INV_EXPONENT
	}
}
