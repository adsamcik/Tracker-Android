package com.adsamcik.tracker.tracker.altitude

import android.content.Context
import android.location.Location
import androidx.annotation.WorkerThread
import com.adsamcik.tracker.shared.model.AltitudeContractVersions
import com.adsamcik.tracker.shared.model.AltitudeConversionStatus
import com.adsamcik.tracker.shared.model.AltitudeDatum
import com.adsamcik.tracker.shared.model.AltitudeSource

/**
 * Processed altitude and the evidence needed to interpret it.
 *
 * [rawWgs84EllipsoidAltitudeM] is retained separately from [altitudeM]. A failed conversion never
 * fills [altitudeM] with the raw ellipsoid value.
 */
internal data class AltitudeProcessingResult(
	val rawWgs84EllipsoidAltitudeM: Double?,
	val rawAltitudeDatum: AltitudeDatum,
	val altitudeM: Double?,
	val datum: AltitudeDatum,
	val source: AltitudeSource,
	val conversionStatus: AltitudeConversionStatus,
	val modelVersion: Int = AltitudeContractVersions.MODEL_VERSION,
	val estimatorVersion: Int = AltitudeContractVersions.ESTIMATOR_VERSION,
	val calibrationVersion: Int = 0,
)

/**
 * Processes raw GPS altitude through a correction and fusion pipeline:
 * 1. Geoid correction (WGS-84 ellipsoid → Mean Sea Level)
 * 2. Vertical accuracy gating (reject unreliable altitude readings)
 * 3. Kalman sensor fusion with barometer (optimal noise weighting)
 *
 * The Kalman filter provides both smoothing and fusion, dynamically weighting
 * GPS (using verticalAccuracy²) and barometer (calibrated, ~1m² noise) sources.
 *
 * Thread-safety: All public mutating methods are synchronized via delegation to AltitudeFusionEngine.
 */
internal class AltitudeProcessor(
	private val verticalAccuracyThresholdM: Float = DEFAULT_VERTICAL_ACCURACY_THRESHOLD_M,
	private val geoidAltitudeConverter: GeoidAltitudeConverter = AndroidXGeoidAltitudeConverter
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
	 * @param location The raw GPS location to process. It is never modified.
	 * @return The processed altitude in meters above MSL, or null if altitude
	 *         was unavailable or failed quality gating.
	 */
	@WorkerThread
	fun process(context: Context, location: Location): Double? {
		return processResult(context, location).altitudeM
	}

	/** Processes one GPS-only fix while preserving conversion provenance. */
	@WorkerThread
	fun processResult(context: Context, location: Location): AltitudeProcessingResult =
		processWithBarometerResult(context, location, baroPressureHpa = null)

	/**
	 * Processes altitude through the full pipeline with barometer fusion.
	 *
	 * @param context Application context for geoid model access.
	 * @param location The raw GPS location. It remains the provider's WGS-84 ellipsoid observation.
	 * @param baroPressureHpa Current barometer pressure in hPa, or null if unavailable.
	 * @return The fused altitude in meters above MSL, or null.
	 */
	@WorkerThread
	fun processWithBarometer(
		context: Context,
		location: Location,
		baroPressureHpa: Float?
	): Double? = processWithBarometerResult(context, location, baroPressureHpa).altitudeM

	/**
	 * Processes altitude with provenance. This is the production path used by collection and
	 * persistence; the nullable overload remains only for local compatibility.
	 */
	@WorkerThread
	fun processWithBarometerResult(
		context: Context,
		location: Location,
		baroPressureHpa: Float?,
	): AltitudeProcessingResult {
		val rawEllipsoidAltitude = location.altitude.takeIf {
			location.hasAltitude() && it.isFinite()
		}
		val conversion = if (location.hasAltitude()) {
			geoidAltitudeConverter.toMslAltitude(context, location)
		} else {
			GeoidAltitudeConversionOutcome.NotAttempted
		}
		val convertedMsl = (conversion as? GeoidAltitudeConversionOutcome.Success)
			?.mslAltitudeM
			?.takeIf(Double::isFinite)
		val conversionStatus = when {
			conversion is GeoidAltitudeConversionOutcome.Success && convertedMsl == null ->
				AltitudeConversionStatus.INVALID_INPUT
			conversion is GeoidAltitudeConversionOutcome.Success && !passesVerticalAccuracyGate(location) ->
				AltitudeConversionStatus.VERTICAL_ACCURACY_REJECTED
			else -> conversion.toStatus()
		}

		// A raw WGS-84 value may never enter this MSL-only input. Failed conversion and poor vertical
		// accuracy therefore cannot calibrate the pressure baseline or GPS-update the filter.
		val admittedMsl = convertedMsl?.takeIf { passesVerticalAccuracyGate(location) }
		val verticalAccuracyM = if (location.hasVerticalAccuracy()) {
			location.verticalAccuracyMeters
		} else {
			null
		}
		val fusion = fusionEngine.updateWithProvenance(
			gpsAltitudeMsl = admittedMsl,
			gpsVerticalAccuracyM = verticalAccuracyM,
			baroPressureHpa = baroPressureHpa,
			timeMs = location.elapsedRealtimeNanos / 1_000_000L,
		)

		return AltitudeProcessingResult(
			rawWgs84EllipsoidAltitudeM = rawEllipsoidAltitude,
			rawAltitudeDatum = if (rawEllipsoidAltitude != null) {
				AltitudeDatum.WGS84_ELLIPSOID
			} else {
				AltitudeDatum.UNKNOWN_LEGACY
			},
			altitudeM = fusion.altitudeM,
			datum = fusion.datum,
			source = fusion.source,
			conversionStatus = conversionStatus,
			estimatorVersion = fusion.estimatorVersion,
			calibrationVersion = fusion.calibrationVersion,
		)
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

	private fun GeoidAltitudeConversionOutcome.toStatus(): AltitudeConversionStatus = when (this) {
		is GeoidAltitudeConversionOutcome.Success -> AltitudeConversionStatus.SUCCESS
		GeoidAltitudeConversionOutcome.NotAttempted -> AltitudeConversionStatus.NOT_ATTEMPTED
		GeoidAltitudeConversionOutcome.NoMslOutput -> AltitudeConversionStatus.NO_MSL_OUTPUT
		GeoidAltitudeConversionOutcome.InvalidInput -> AltitudeConversionStatus.INVALID_INPUT
		GeoidAltitudeConversionOutcome.IoFailure -> AltitudeConversionStatus.IO_FAILURE
		GeoidAltitudeConversionOutcome.UnexpectedFailure -> AltitudeConversionStatus.UNEXPECTED_FAILURE
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
