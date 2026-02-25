package com.adsamcik.tracker.tracker.altitude

import android.content.Context
import android.location.Location
import androidx.annotation.WorkerThread
import androidx.core.location.altitude.AltitudeConverterCompat

/**
 * Processes raw GPS altitude through a correction and fusion pipeline:
 * 1. Geoid correction (WGS-84 ellipsoid → Mean Sea Level)
 * 2. Vertical accuracy gating (reject unreliable altitude readings)
 * 3. Kalman sensor fusion with barometer (optimal noise weighting)
 *
 * The Kalman filter provides both smoothing and fusion, dynamically weighting
 * GPS (using verticalAccuracy²) and barometer (calibrated, ~1m² noise) sources.
 *
 * Thread-safety: This class is NOT thread-safe. Use from a single coroutine context.
 */
internal class AltitudeProcessor(
	private val verticalAccuracyThresholdM: Float = DEFAULT_VERTICAL_ACCURACY_THRESHOLD_M
) {
	private val fusionEngine = AltitudeFusionEngine()

	/**
	 * Whether the fusion engine has been calibrated with a GPS+barometer pair.
	 */
	val isFusionCalibrated: Boolean
		get() = fusionEngine.isCalibrated

	/**
	 * Processes a location's altitude through the full pipeline (GPS-only path).
	 * Used when no barometer data is available.
	 *
	 * Must be called on a worker thread (geoid model lookup is I/O).
	 *
	 * @param context Application context for geoid model access.
	 * @param location The raw GPS location to process. Modified in place.
	 * @return The processed altitude in meters above MSL, or null if altitude
	 *         was unavailable or failed quality gating.
	 */
	@WorkerThread
	fun process(context: Context, location: Location): Double? {
		return processWithBarometer(context, location, baroPressureHpa = null)
	}

	/**
	 * Processes altitude through the full pipeline with barometer fusion.
	 *
	 * @param context Application context for geoid model access.
	 * @param location The raw GPS location. Modified in place with geoid correction.
	 * @param baroPressureHpa Current barometer pressure in hPa, or null if unavailable.
	 * @return The fused altitude in meters above MSL, or null.
	 */
	@WorkerThread
	fun processWithBarometer(
		context: Context,
		location: Location,
		baroPressureHpa: Float?
	): Double? {
		// Step 1: Geoid correction (ellipsoid → MSL)
		val mslAltitude = if (location.hasAltitude()) {
			applyGeoidCorrection(context, location)
		} else {
			null
		}

		// Step 2: Vertical accuracy gating
		val gatedAltitude = if (mslAltitude != null && passesVerticalAccuracyGate(location)) {
			mslAltitude
		} else {
			null
		}

		// Step 3: Get vertical accuracy for Kalman weighting
		val verticalAccuracyM = if (location.hasVerticalAccuracy()) {
			location.verticalAccuracyMeters
		} else {
			null
		}

		// Step 4: Kalman fusion (GPS + barometer)
		return fusionEngine.update(
			gpsAltitudeMsl = gatedAltitude,
			gpsVerticalAccuracyM = verticalAccuracyM,
			baroPressureHpa = baroPressureHpa,
			timeMs = location.time
		)
	}

	/**
	 * Applies geoid correction to convert ellipsoidal altitude to MSL altitude.
	 * Uses AndroidX AltitudeConverterCompat for backward compatibility.
	 *
	 * @return MSL altitude in meters, or null if correction failed.
	 */
	@WorkerThread
	private fun applyGeoidCorrection(context: Context, location: Location): Double? {
		return try {
			AltitudeConverterCompat.addMslAltitudeToLocation(context, location)
			if (location.hasMslAltitude()) {
				location.mslAltitudeMeters
			} else {
				// Fallback: use raw altitude if geoid model unavailable
				location.altitude
			}
		} catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
			// Geoid model may not be available; fall back to raw altitude
			location.altitude
		}
	}

	/**
	 * Checks if the location's vertical accuracy meets the quality threshold.
	 * Locations without vertical accuracy information are allowed through
	 * (conservative: don't reject data we can't evaluate).
	 */
	private fun passesVerticalAccuracyGate(location: Location): Boolean {
		if (!location.hasVerticalAccuracy()) return true
		return location.verticalAccuracyMeters <= verticalAccuracyThresholdM
	}

	/**
	 * Resets all state (Kalman filter + calibration). Call when starting a new tracking session.
	 */
	fun reset() {
		fusionEngine.reset()
	}

	companion object {
		/**
		 * Default vertical accuracy threshold in meters.
		 * GPS altitude readings with vertical accuracy worse than this are rejected.
		 * 20m is a reasonable threshold — most outdoor GPS fixes are <15m.
		 */
		const val DEFAULT_VERTICAL_ACCURACY_THRESHOLD_M = 20f
	}
}
