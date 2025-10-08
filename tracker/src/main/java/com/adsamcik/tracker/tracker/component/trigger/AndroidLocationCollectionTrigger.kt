package com.adsamcik.tracker.tracker.component.trigger

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.extension.locationManager
import com.adsamcik.tracker.shared.preferences.Preferences

import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.component.DynamicIntervalCollectionTrigger
import com.adsamcik.tracker.tracker.component.TrackerTimerErrorData
import com.adsamcik.tracker.tracker.component.TrackerTimerErrorSeverity
import com.adsamcik.tracker.tracker.component.TrackerTimerReceiver

/**
 * Collection trigger that uses native Android location manager.
 * Supports dynamic interval updates for policy-based adaptation.
 */
internal class AndroidLocationCollectionTrigger : LocationCollectionTrigger(), DynamicIntervalCollectionTrigger {
	override val requiredPermissions: Collection<String>
		get() = listOf(Manifest.permission.ACCESS_FINE_LOCATION)

	override val titleRes: Int
		get() = R.string.settings_tracker_timer_location

	private val locationListener: LocationListener = object : LocationListener {
		override fun onLocationChanged(location: Location) {
			onNewData(listOf(location))
		}

		override fun onProviderDisabled(provider: String) {
			val errorData = TrackerTimerErrorData(
					TrackerTimerErrorSeverity.NOTIFY_USER,
					R.string.notification_looking_for_gps
			)
			receiver?.onError(errorData)
		}

	}

	override fun onEnable(context: Context, receiver: TrackerTimerReceiver) {
		super.onEnable(context, receiver)

	val preferences = Preferences.getPref(context)
	val minUpdateDelayInSeconds = preferences.getIntRes(
		com.adsamcik.tracker.shared.preferences.R.string.settings_tracking_min_time_key,
		com.adsamcik.tracker.shared.preferences.R.integer.settings_tracking_min_time_default
	)
	val minDistanceInMeters = preferences.getIntRes(
		com.adsamcik.tracker.shared.preferences.R.string.settings_tracking_min_distance_key,
		com.adsamcik.tracker.shared.preferences.R.integer.settings_tracking_min_distance_default
	)

		val locationManager = context.locationManager
		//It is checked by the component system
		@Suppress("MissingPermission")
		locationManager.requestLocationUpdates(
				LocationManager.GPS_PROVIDER,
				minUpdateDelayInSeconds * Time.SECOND_IN_MILLISECONDS,
				minDistanceInMeters.toFloat(),
				locationListener,
				Looper.getMainLooper()
		)
	}

	override fun onDisable(context: Context) {
		super.onDisable(context)

		context.locationManager.removeUpdates(locationListener)
	}

	override fun updateInterval(context: Context, intervalSeconds: Int, minDistanceMeters: Int) {
		// Update location request interval dynamically by restarting with new parameters
		val locationManager = context.locationManager
		locationManager.removeUpdates(locationListener)

		// checked by component system
		@Suppress("MissingPermission")
		locationManager.requestLocationUpdates(
			LocationManager.GPS_PROVIDER,
			intervalSeconds * Time.SECOND_IN_MILLISECONDS,
			minDistanceMeters.toFloat(),
			locationListener,
			Looper.getMainLooper()
		)
	}
}

