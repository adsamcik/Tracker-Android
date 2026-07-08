package com.adsamcik.tracker.tracker.altitude

import android.content.Context
import android.location.Location
import androidx.annotation.WorkerThread
import androidx.core.location.LocationCompat
import androidx.core.location.altitude.AltitudeConverterCompat

internal interface GeoidAltitudeConverter {
	@WorkerThread
	fun toMslAltitude(context: Context, location: Location): Double?
}

internal object AndroidXGeoidAltitudeConverter : GeoidAltitudeConverter {
	@WorkerThread
	override fun toMslAltitude(context: Context, location: Location): Double? {
		return try {
			AltitudeConverterCompat.addMslAltitudeToLocation(context, location)
			if (LocationCompat.hasMslAltitude(location)) {
				LocationCompat.getMslAltitudeMeters(location)
			} else {
				location.altitude
			}
		} catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
			location.altitude
		}
	}
}
