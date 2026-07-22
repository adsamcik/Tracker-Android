package com.adsamcik.tracker.tracker.altitude

import android.content.Context
import android.location.Location
import androidx.annotation.WorkerThread
import androidx.core.location.LocationCompat
import androidx.core.location.altitude.AltitudeConverterCompat
import java.io.IOException
import kotlinx.coroutines.CancellationException

internal interface GeoidAltitudeConverter {
	@WorkerThread
	fun toMslAltitude(context: Context, location: Location): GeoidAltitudeConversionOutcome
}

/**
 * Typed result of the Android-model geoid conversion.
 *
 * A failed conversion intentionally carries no altitude. In particular, the raw WGS-84 ellipsoid
 * value is evidence only and must never be substituted for an MSL value by a caller.
 */
internal sealed interface GeoidAltitudeConversionOutcome {
	data class Success(val mslAltitudeM: Double) : GeoidAltitudeConversionOutcome
	data object NotAttempted : GeoidAltitudeConversionOutcome
	data object NoMslOutput : GeoidAltitudeConversionOutcome
	data object InvalidInput : GeoidAltitudeConversionOutcome
	data object IoFailure : GeoidAltitudeConversionOutcome
	data object UnexpectedFailure : GeoidAltitudeConversionOutcome
}

internal object AndroidXGeoidAltitudeConverter : GeoidAltitudeConverter {
	@WorkerThread
	override fun toMslAltitude(
		context: Context,
		location: Location,
	): GeoidAltitudeConversionOutcome {
		if (!location.hasAltitude()) return GeoidAltitudeConversionOutcome.NotAttempted
		if (!location.isValidGeoidConversionInput()) {
			return GeoidAltitudeConversionOutcome.InvalidInput
		}

		// AndroidX writes the converted MSL fields onto the supplied Location. Never hand it the
		// provider-owned instance: Location.altitude remains the raw WGS-84 ellipsoid observation.
		val conversionLocation = Location(location)
		return try {
			AltitudeConverterCompat.addMslAltitudeToLocation(context, conversionLocation)
			if (LocationCompat.hasMslAltitude(conversionLocation)) {
				LocationCompat.getMslAltitudeMeters(conversionLocation)
					.takeIf(Double::isFinite)
					?.let(GeoidAltitudeConversionOutcome::Success)
					?: GeoidAltitudeConversionOutcome.InvalidInput
			} else {
				GeoidAltitudeConversionOutcome.NoMslOutput
			}
		} catch (e: CancellationException) {
			throw e
		} catch (e: IOException) {
			GeoidAltitudeConversionOutcome.IoFailure
		} catch (e: IllegalArgumentException) {
			GeoidAltitudeConversionOutcome.InvalidInput
		} catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
			GeoidAltitudeConversionOutcome.UnexpectedFailure
		}
	}

	private fun Location.isValidGeoidConversionInput(): Boolean =
		altitude.isFinite() &&
		latitude.isFinite() && latitude in -90.0..90.0 &&
		longitude.isFinite() && longitude in -180.0..180.0
}
