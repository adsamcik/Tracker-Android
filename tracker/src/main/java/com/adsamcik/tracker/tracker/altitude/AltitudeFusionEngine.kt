package com.adsamcik.tracker.tracker.altitude

import kotlin.math.pow

/**
 * Fuses GPS altitude with barometric pressure changes using a complementary filter.
 *
 * GPS provides absolute altitude (noisy, ~10-30m error, no drift).
 * Barometer provides relative altitude changes (smooth, ~0.5m noise, but drifts with weather).
 *
 * The complementary filter combines these:
 *   fusedAlt = α * (prevFused + baroChange) + (1 - α) * gpsAlt
 *
 * Where α close to 1.0 trusts barometer for short-term and GPS for long-term.
 *
 * Includes GPS-calibrated barometer baseline: uses the first good GPS fix to
 * determine local sea-level pressure, eliminating weather-dependent absolute error.
 *
 * Thread-safety: NOT thread-safe. Use from a single coroutine context.
 */
internal class AltitudeFusionEngine(
	private val alpha: Double = DEFAULT_ALPHA,
	private val recalibrationIntervalMs: Long = DEFAULT_RECALIBRATION_INTERVAL_MS
) {
	init {
		require(alpha in 0.0..1.0) { "Alpha must be in [0, 1], was $alpha" }
	}

	// Calibrated sea-level pressure (derived from GPS + barometer at calibration time)
	private var calibratedSeaLevelPressureHpa: Double? = null
	private var lastCalibrationTimeMs: Long = 0L

	// Previous barometer altitude (for computing relative change)
	private var previousBaroAltitudeM: Double? = null

	// Current fused altitude estimate
	private var fusedAltitudeM: Double? = null

	/**
	 * Whether the fusion engine has been calibrated with at least one GPS fix.
	 */
	val isCalibrated: Boolean
		get() = calibratedSeaLevelPressureHpa != null

	/**
	 * The current fused altitude estimate, or null if not yet initialized.
	 */
	val currentAltitude: Double?
		get() = fusedAltitudeM

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
	 * Call this on each tracking cycle with whatever data is available.
	 * Both parameters are nullable — the engine handles partial updates.
	 *
	 * @param gpsAltitudeMsl GPS altitude in meters above MSL (already geoid-corrected).
	 *                       Null if GPS altitude unavailable or failed quality gate.
	 * @param baroPressureHpa Current barometer pressure in hPa.
	 *                        Null if barometer unavailable.
	 * @param timeMs Current time in milliseconds.
	 * @return The fused altitude estimate, or null if insufficient data.
	 */
	fun update(
		gpsAltitudeMsl: Double?,
		baroPressureHpa: Float?,
		timeMs: Long
	): Double? {
		// Try to calibrate/recalibrate if we have both GPS and barometer
		if (gpsAltitudeMsl != null && baroPressureHpa != null) {
			if (!isCalibrated || needsRecalibration(timeMs)) {
				calibrate(gpsAltitudeMsl, baroPressureHpa, timeMs)
			}
		}

		// Compute current barometer altitude
		val currentBaroAltitude = baroPressureHpa?.let { pressureToAltitude(it) }

		// Compute barometer altitude change (relative delta)
		val baroChange = if (currentBaroAltitude != null && previousBaroAltitudeM != null) {
			currentBaroAltitude - previousBaroAltitudeM!!
		} else {
			null
		}

		// Update previous barometer altitude
		if (currentBaroAltitude != null) {
			previousBaroAltitudeM = currentBaroAltitude
		}

		// Apply complementary filter
		val currentFused = fusedAltitudeM
		val newFused = when {
			// Case 1: Have both GPS and barometer change → full complementary filter
			gpsAltitudeMsl != null && baroChange != null && currentFused != null -> {
				alpha * (currentFused + baroChange) + (1.0 - alpha) * gpsAltitudeMsl
			}
			// Case 2: Have GPS but no barometer change → use GPS directly (bootstrap)
			gpsAltitudeMsl != null && currentFused == null -> {
				gpsAltitudeMsl
			}
			// Case 3: Have GPS and existing estimate but no baro → blend toward GPS
			gpsAltitudeMsl != null -> {
				alpha * currentFused!! + (1.0 - alpha) * gpsAltitudeMsl
			}
			// Case 4: Have barometer change but no GPS → extrapolate from baro
			baroChange != null && currentFused != null -> {
				currentFused + baroChange
			}
			// Case 5: Nothing useful → keep previous
			else -> currentFused
		}

		if (newFused != null) {
			fusedAltitudeM = newFused
		}

		return newFused
	}

	/**
	 * Resets all state. Call when starting a new tracking session.
	 */
	fun reset() {
		calibratedSeaLevelPressureHpa = null
		lastCalibrationTimeMs = 0L
		previousBaroAltitudeM = null
		fusedAltitudeM = null
	}

	companion object {
		/**
		 * Default complementary filter weight.
		 * 0.98 heavily trusts the barometer for short-term changes
		 * while GPS corrects drift over time.
		 */
		const val DEFAULT_ALPHA = 0.98

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
