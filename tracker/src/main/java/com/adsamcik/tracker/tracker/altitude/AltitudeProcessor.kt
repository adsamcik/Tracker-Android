package com.adsamcik.tracker.tracker.altitude

import android.content.Context
import android.location.Location
import androidx.annotation.WorkerThread
import androidx.core.location.altitude.AltitudeConverterCompat

/**
 * Processes raw GPS altitude through a correction and smoothing pipeline:
 * 1. Geoid correction (WGS-84 ellipsoid → Mean Sea Level)
 * 2. Vertical accuracy gating (reject unreliable altitude readings)
 * 3. EMA smoothing (reduce high-frequency noise)
 *
 * Thread-safety: This class is NOT thread-safe. Use from a single coroutine context.
 */
internal class AltitudeProcessor(
	private val verticalAccuracyThresholdM: Float = DEFAULT_VERTICAL_ACCURACY_THRESHOLD_M,
	emaAlpha: Float = DEFAULT_EMA_ALPHA
) {
	private val emaFilter = EmaFilter(emaAlpha)

	/**
	 * Processes a location's altitude through the full pipeline.
	 * Modifies the [location] in place with the corrected MSL altitude.
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
		if (!location.hasAltitude()) return null

		// Step 1: Geoid correction (ellipsoid → MSL)
		val mslAltitude = applyGeoidCorrection(context, location)
			?: return null

		// Step 2: Vertical accuracy gating
		if (!passesVerticalAccuracyGate(location)) {
			return null
		}

		// Step 3: EMA smoothing
		return emaFilter.update(mslAltitude)
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
	 * Resets the EMA filter state. Call when starting a new tracking session.
	 */
	fun reset() {
		emaFilter.reset()
	}

	companion object {
		/**
		 * Default vertical accuracy threshold in meters.
		 * GPS altitude readings with vertical accuracy worse than this are rejected.
		 * 20m is a reasonable threshold — most outdoor GPS fixes are <15m.
		 */
		const val DEFAULT_VERTICAL_ACCURACY_THRESHOLD_M = 20f

		/**
		 * Default EMA smoothing factor.
		 * 0.2 provides moderate smoothing: responsive to real changes,
		 * dampens single-sample spikes. ~5-sample effective window.
		 */
		const val DEFAULT_EMA_ALPHA = 0.2f
	}
}
