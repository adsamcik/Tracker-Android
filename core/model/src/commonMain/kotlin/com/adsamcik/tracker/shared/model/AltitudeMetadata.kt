package com.adsamcik.tracker.shared.model

/**
 * Reference surface for an altitude value.
 *
 * The stable [storageName] is used by durable signals, Room, and exports. New readers must map
 * unknown names to [UNKNOWN_LEGACY] rather than guessing a compatible reference surface.
 */
enum class AltitudeDatum(val storageName: String) {
	/** Mean sea level as produced by AndroidX's Android-model geoid conversion. */
	ANDROID_MODEL_MSL("android_model_msl"),
	/** Android-model MSL estimate updated by both a GPS conversion and the barometer. */
	FUSED_ANDROID_MODEL_MSL("fused_android_model_msl"),
	/** A barometric continuation/prediction relative to its prior calibration. */
	RELATIVE_BAROMETRIC("relative_barometric"),
	/** Raw Android [android.location.Location.altitude] evidence above the WGS-84 ellipsoid. */
	WGS84_ELLIPSOID("wgs84_ellipsoid"),
	/** Historical/imported value whose reference surface cannot be demonstrated. */
	UNKNOWN_LEGACY("unknown_legacy"),
	;

	/** True only when this value may be labelled as an Android-model MSL altitude. */
	val isAndroidModelMsl: Boolean
		get() = this == ANDROID_MODEL_MSL || this == FUSED_ANDROID_MODEL_MSL

	/**
	 * Whether two values can be used in one continuous altitude segment. Android-model and fused
	 * Android-model MSL share a reference surface; all other datums require an exact match.
	 */
	fun isContinuousWith(other: AltitudeDatum): Boolean = when {
		this == UNKNOWN_LEGACY || other == UNKNOWN_LEGACY -> false
		isAndroidModelMsl && other.isAndroidModelMsl -> true
		else -> this == other
	}

	companion object {
		fun fromStorageName(value: String?): AltitudeDatum = entries.firstOrNull {
			it.storageName == value
		} ?: UNKNOWN_LEGACY
	}
}

/** How Tracker obtained a processed altitude estimate. */
enum class AltitudeSource(val storageName: String) {
	GPS_CONVERSION("gps_conversion"),
	FUSED_GPS_BAROMETER("fused_gps_barometer"),
	BAROMETER_PREDICTION("barometer_prediction"),
	PREDICTION("prediction"),
	IMPORTED("imported"),
	UNKNOWN_LEGACY("unknown_legacy"),
	;

	companion object {
		fun fromStorageName(value: String?): AltitudeSource = entries.firstOrNull {
			it.storageName == value
		} ?: UNKNOWN_LEGACY
	}
}

/** Result of attempting to obtain Android-model MSL from a raw ellipsoid observation. */
enum class AltitudeConversionStatus(val storageName: String) {
	SUCCESS("success"),
	/** Conversion was not attempted, including a location that did not contain an altitude. */
	NOT_ATTEMPTED("not_attempted"),
	NO_MSL_OUTPUT("no_msl_output"),
	INVALID_INPUT("invalid_input"),
	IO_FAILURE("io_failure"),
	UNEXPECTED_FAILURE("unexpected_failure"),
	/** A converted MSL value was not admitted to fusion because vertical accuracy was too poor. */
	VERTICAL_ACCURACY_REJECTED("vertical_accuracy_rejected"),
	UNKNOWN_LEGACY("unknown_legacy"),
	;

	companion object {
		fun fromStorageName(value: String?): AltitudeConversionStatus = entries.firstOrNull {
			it.storageName == value
		} ?: UNKNOWN_LEGACY
	}
}

/** Stable identifiers for the production altitude contract, not empirical tuning parameters. */
object AltitudeContractVersions {
	const val MODEL_VERSION = 1
	const val ESTIMATOR_VERSION = 1
	const val CALIBRATION_VERSION = 1
}

/** Absolute MSL value suitable for an MSL-labelled maximum or datum-less export field. */
fun LocationSample.androidModelMslAltitudeOrNull(): Double? =
	altitudeM?.toDouble()?.takeIf { it.isFinite() && altitudeDatum.isAndroidModelMsl }

/** Whether this sample has a finite, identified altitude that can start a continuous segment. */
fun LocationSample.hasIdentifiedAltitude(): Boolean =
	altitudeM?.isFinite() == true && altitudeDatum != AltitudeDatum.UNKNOWN_LEGACY

/**
 * Whether [this] can continue an altitude segment after [previous].
 *
 * A non-null equal clock domain is required so no delta bridges a session/reboot boundary. Unknown
 * datum values and mismatched reference surfaces force the caller to reset its baseline instead.
 */
fun LocationSample.isAltitudeContinuousAfter(previous: LocationSample?): Boolean {
	val previousSample = previous ?: return false
	val domain = clockDomainId?.takeIf(String::isNotBlank) ?: return false
	return hasIdentifiedAltitude() &&
		previousSample.hasIdentifiedAltitude() &&
		domain == previousSample.clockDomainId &&
		altitudeDatum.isContinuousWith(previousSample.altitudeDatum)
}
