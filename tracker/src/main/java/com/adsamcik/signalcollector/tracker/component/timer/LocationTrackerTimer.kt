package com.adsamcik.signalcollector.tracker.component.timer

import android.Manifest
import android.content.Context
import android.location.Location
import android.os.Looper
import com.adsamcik.signalcollector.common.Reporter
import com.adsamcik.signalcollector.common.Time
import com.adsamcik.signalcollector.common.preference.Preferences
import com.adsamcik.signalcollector.tracker.R
import com.adsamcik.signalcollector.tracker.component.TrackerTimerComponent
import com.adsamcik.signalcollector.tracker.component.TrackerTimerErrorData
import com.adsamcik.signalcollector.tracker.component.TrackerTimerErrorSeverity
import com.adsamcik.signalcollector.tracker.component.TrackerTimerReceiver
import com.adsamcik.signalcollector.tracker.data.collection.MutableCollectionTempData
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices

internal class LocationTrackerTimer : TrackerTimerComponent {
	override val requiredPermissions: Collection<String> get() = listOf(Manifest.permission.ACCESS_FINE_LOCATION)

	private var receiver: TrackerTimerReceiver? = null

	private var previousLocation: Location? = null
	private var elapsedRealtimeStartNanos: Long = 0

	private val locationCallback: LocationCallback = object : LocationCallback() {
		override fun onLocationResult(result: LocationResult) {
			if (receiver == null) {
				Reporter.report("Received location update with null callback")
				return
			}

			if (result.lastLocation.elapsedRealtimeNanos + MAX_LOCATION_AGE_IN_NANOS > elapsedRealtimeStartNanos) {
				val tempData = createCollectionTempData(result)
				receiver?.onUpdate(tempData)

				previousLocation = result.lastLocation
			}
		}

		override fun onLocationAvailability(availability: LocationAvailability) {
			if (!availability.isLocationAvailable) {
				val errorData = TrackerTimerErrorData(TrackerTimerErrorSeverity.NOTIFY_USER,
						R.string.notification_looking_for_gps)
				receiver?.onError(errorData)
			}
		}
	}

	private fun createCollectionTempData(locationResult: LocationResult): MutableCollectionTempData {
		val location = locationResult.lastLocation
		return MutableCollectionTempData(location.time, location.elapsedRealtimeNanos).apply {
			setLocationResult(locationResult)

			val previousLocation = previousLocation
			if (previousLocation != null &&
					(locationResult.lastLocation.elapsedRealtimeNanos - previousLocation.elapsedRealtimeNanos) < PREVIOUS_LOCATION_MAX_AGE_IN_SECONDS * Time.SECOND_IN_NANOSECONDS) {
				val distance = location.distanceTo(previousLocation)
				setPreviousLocation(previousLocation, distance)
			}
		}
	}

	override fun onEnable(context: Context, receiver: TrackerTimerReceiver) {
		this.receiver = receiver

		val preferences = Preferences.getPref(context)
		val minUpdateDelayInSeconds = preferences.getIntRes(R.string.settings_tracking_min_time_key,
				R.integer.settings_tracking_min_time_default)
		val minDistanceInMeters = preferences.getIntRes(R.string.settings_tracking_min_distance_key,
				R.integer.settings_tracking_min_distance_default)

		val client = LocationServices.getFusedLocationProviderClient(context)
		val request = LocationRequest.create().apply {
			interval = minUpdateDelayInSeconds * Time.SECOND_IN_MILLISECONDS
			fastestInterval = minUpdateDelayInSeconds * Time.SECOND_IN_MILLISECONDS
			smallestDisplacement = minDistanceInMeters.toFloat()
			priority = LocationRequest.PRIORITY_HIGH_ACCURACY
		}

		elapsedRealtimeStartNanos = Time.elapsedRealtimeNanos

		client.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
	}

	override fun onDisable(context: Context) {
		LocationServices.getFusedLocationProviderClient(context)
				.removeLocationUpdates(locationCallback)
		this.receiver = null
	}

	companion object {
		const val PREVIOUS_LOCATION_MAX_AGE_IN_SECONDS = 30
		const val MAX_LOCATION_AGE_IN_NANOS = 10 * Time.SECOND_IN_NANOSECONDS
	}
}
