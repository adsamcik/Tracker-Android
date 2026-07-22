package com.adsamcik.tracker.tracker.altitude

import com.adsamcik.tracker.shared.model.AltitudeContractVersions
import com.adsamcik.tracker.shared.model.AltitudeDatum
import com.adsamcik.tracker.shared.model.AltitudeSource

/**
 * A fusion result with the source actually used by this cycle.
 *
 * Callers must use this result rather than inferring provenance from nullable inputs: a prior
 * calibration can legitimately support a barometric continuation after the current GPS conversion
 * fails, while that same failed conversion must never calibrate or update the GPS/MSL path.
 */
internal data class AltitudeFusionResult(
	val altitudeM: Double?,
	val datum: AltitudeDatum,
	val source: AltitudeSource,
	val estimatorVersion: Int = AltitudeContractVersions.ESTIMATOR_VERSION,
	val calibrationVersion: Int = 0,
)

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
 * Thread-safety: All public mutating methods are synchronized.
 */
internal class AltitudeFusionEngine(
	private val recalibrationIntervalMs: Long = DEFAULT_RECALIBRATION_INTERVAL_MS,
	private val defaultGpsMeasurementNoiseM2: Double = DEFAULT_GPS_MEASUREMENT_NOISE_M2,
	private val baroMeasurementNoiseM2: Double = DEFAULT_BARO_MEASUREMENT_NOISE_M2
) {
	private val kalmanFilter = AltitudeKalmanFilter()

	// Calibrated sea-level pressure (derived from GPS + barometer at calibration time)
	private var calibratedSeaLevelPressureHpa: Double? = null
	private var lastCalibrationElapsedTimeMs: Long = 0L

	// Previous barometer altitude for change tracking
	private var previousBaroAltitudeM: Double? = null
	private var lastEstimateDatum: AltitudeDatum = AltitudeDatum.UNKNOWN_LEGACY

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
	 * @param elapsedTimeMs Monotonic elapsed realtime in milliseconds.
	 */
	@Synchronized
	fun calibrate(gpsAltitudeMsl: Double, currentPressureHpa: Float, elapsedTimeMs: Long) {
		calibrateInternal(gpsAltitudeMsl, currentPressureHpa, elapsedTimeMs)
	}

	private fun calibrateInternal(
		gpsAltitudeMsl: Double,
		currentPressureHpa: Float,
		elapsedTimeMs: Long
	): Boolean {
		val seaLevelPressureHpa = BarometricAltitudeFormula.seaLevelPressureHpa(
			altitudeM = gpsAltitudeMsl,
			pressureHpa = currentPressureHpa
		) ?: return false
		calibratedSeaLevelPressureHpa = seaLevelPressureHpa
		lastCalibrationElapsedTimeMs = elapsedTimeMs
		return true
	}

	/**
	 * Returns whether recalibration is needed based on elapsed time.
	 */
	fun needsRecalibration(currentElapsedTimeMs: Long): Boolean {
		if (calibratedSeaLevelPressureHpa == null) return true
		return (currentElapsedTimeMs - lastCalibrationElapsedTimeMs) >= recalibrationIntervalMs
	}

	/**
	 * Convert barometer pressure to altitude using the calibrated baseline.
	 * Returns null if not calibrated.
	 */
	fun pressureToAltitude(pressureHpa: Float): Double? {
		val seaLevel = calibratedSeaLevelPressureHpa ?: return null
		return BarometricAltitudeFormula.pressureToAltitudeM(
			pressureHpa = pressureHpa,
			seaLevelPressureHpa = seaLevel
		)
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
	 * @param timeMs Monotonic elapsed realtime in milliseconds.
	 * @return The fused altitude estimate, or null if insufficient data.
	 */
	@Synchronized
	fun update(
		gpsAltitudeMsl: Double?,
		gpsVerticalAccuracyM: Float? = null,
		baroPressureHpa: Float? = null,
		timeMs: Long
	): Double? = updateWithProvenance(
		gpsAltitudeMsl = gpsAltitudeMsl,
		gpsVerticalAccuracyM = gpsVerticalAccuracyM,
		baroPressureHpa = baroPressureHpa,
		timeMs = timeMs,
	).altitudeM

	/**
	 * Runs one update while reporting which measurements were actually admitted to the estimate.
	 *
	 * [gpsAltitudeMsl] is deliberately named as an MSL-only input. The processor calls this method
	 * only after a successful Android-model conversion; raw WGS-84 ellipsoid values are never valid
	 * calibration or GPS-update inputs.
	 */
	@Synchronized
	fun updateWithProvenance(
		gpsAltitudeMsl: Double?,
		gpsVerticalAccuracyM: Float? = null,
		baroPressureHpa: Float? = null,
		timeMs: Long,
	): AltitudeFusionResult {
		val validGpsAltitudeMsl = gpsAltitudeMsl?.takeIf { it.isFinite() }
		val validGpsVerticalAccuracyM = gpsVerticalAccuracyM
			?.takeIf { it.isFinite() && it >= 0f }
		val validBaroPressureHpa = baroPressureHpa
			?.takeIf(BarometricAltitudeFormula::isValidPressure)

		var calibratedThisCycle = false

		// Try to calibrate/recalibrate if we have both GPS and barometer
		if (validGpsAltitudeMsl != null && validBaroPressureHpa != null) {
			if (!isCalibrated || needsRecalibration(timeMs)) {
				calibratedThisCycle = calibrateInternal(
					validGpsAltitudeMsl,
					validBaroPressureHpa,
					timeMs
				)
			}
		}

		// Run prediction step if filter is already initialized.
		val predictedThisCycle = kalmanFilter.isInitialized
		if (predictedThisCycle) {
			kalmanFilter.predict(timeMs)
		}

		// GPS measurement update
		var usedGpsMeasurement = false
		if (validGpsAltitudeMsl != null) {
			val gpsNoise = if (validGpsVerticalAccuracyM != null) {
				(validGpsVerticalAccuracyM * validGpsVerticalAccuracyM).toDouble()
			} else {
				defaultGpsMeasurementNoiseM2
			}
			kalmanFilter.update(validGpsAltitudeMsl, gpsNoise, timeMs)
			usedGpsMeasurement = true
		}

		// Barometer measurement update
		val baroAltitude = validBaroPressureHpa?.let { pressureToAltitude(it) }
		var usedBarometerMeasurement = false
		if (baroAltitude != null && !calibratedThisCycle) {
			kalmanFilter.update(baroAltitude, baroMeasurementNoiseM2, timeMs)
			usedBarometerMeasurement = true
		}

		// Track barometer altitude for external consumers
		if (baroAltitude != null) {
			previousBaroAltitudeM = baroAltitude
		}

		val altitude = currentAltitude
		val datum = when {
			altitude == null -> AltitudeDatum.UNKNOWN_LEGACY
			usedGpsMeasurement && usedBarometerMeasurement -> AltitudeDatum.FUSED_ANDROID_MODEL_MSL
			usedGpsMeasurement -> AltitudeDatum.ANDROID_MODEL_MSL
			usedBarometerMeasurement -> AltitudeDatum.RELATIVE_BAROMETRIC
			predictedThisCycle -> lastEstimateDatum
			else -> AltitudeDatum.UNKNOWN_LEGACY
		}
		val source = when {
			altitude == null -> AltitudeSource.UNKNOWN_LEGACY
			usedGpsMeasurement && usedBarometerMeasurement -> AltitudeSource.FUSED_GPS_BAROMETER
			usedGpsMeasurement -> AltitudeSource.GPS_CONVERSION
			usedBarometerMeasurement -> AltitudeSource.BAROMETER_PREDICTION
			predictedThisCycle -> AltitudeSource.PREDICTION
			else -> AltitudeSource.UNKNOWN_LEGACY
		}
		if (altitude != null && datum != AltitudeDatum.UNKNOWN_LEGACY) {
			lastEstimateDatum = datum
		}

		return AltitudeFusionResult(
			altitudeM = altitude,
			datum = datum,
			source = source,
			calibrationVersion = if (isCalibrated) {
				AltitudeContractVersions.CALIBRATION_VERSION
			} else {
				0
			},
		)
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
	@Synchronized
	fun reset() {
		calibratedSeaLevelPressureHpa = null
		lastCalibrationElapsedTimeMs = 0L
		previousBaroAltitudeM = null
		lastEstimateDatum = AltitudeDatum.UNKNOWN_LEGACY
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
	}
}
