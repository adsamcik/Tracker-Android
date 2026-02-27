package com.adsamcik.tracker.tracker.component.trigger

import android.Manifest
import android.content.Context
import android.os.Looper
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.extension.hasPreciseLocationPermission
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.api.BackgroundTrackingApi
import com.adsamcik.tracker.tracker.component.DynamicIntervalCollectionTrigger
import com.adsamcik.tracker.tracker.component.TrackerTimerErrorData
import com.adsamcik.tracker.tracker.component.TrackerTimerErrorSeverity
import com.adsamcik.tracker.tracker.component.TrackerTimerReceiver
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * Collection trigger that uses Fused Location Provider in Google Play Services.
 * Supports dynamic interval updates for policy-based adaptation.
 * Accepts either fine (precise) or coarse (approximate) location permission.
 */
internal class FusedLocationCollectionTrigger : LocationCollectionTrigger(), DynamicIntervalCollectionTrigger {
	override val requiredPermissions: Collection<String>
		get() = listOf(
			Manifest.permission.ACCESS_FINE_LOCATION,
			Manifest.permission.ACCESS_COARSE_LOCATION
		)

	override val titleRes: Int
		get() = R.string.settings_tracker_timer_fused

	private val locationCallback: LocationCallback = object : LocationCallback() {
		override fun onLocationResult(result: LocationResult) {
			// receiver can be null during the race between onDisable and a pending
			// location delivery — this is expected and not an error.
			if (receiver == null) {
				return
			}

			onNewData(result.locations)
		}

		override fun onLocationAvailability(availability: LocationAvailability) {
			if (!availability.isLocationAvailable) {
				val errorData = TrackerTimerErrorData(
						TrackerTimerErrorSeverity.NOTIFY_USER,
						R.string.notification_looking_for_gps
				)
				val localReceiver = receiver ?: return
				localReceiver.onError(errorData)
			}
		}
	}

	override fun onEnable(context: Context, receiver: TrackerTimerReceiver) {
		super.onEnable(context, receiver)

		val minUpdateDelayInSeconds = BackgroundTrackingApi.cachedParams.minTimeSeconds
		val minDistanceInMeters = BackgroundTrackingApi.cachedParams.minDistanceMeters

		val client = LocationServices.getFusedLocationProviderClient(context)
		val request = LocationRequest.Builder(minUpdateDelayInSeconds * Time.SECOND_IN_MILLISECONDS)
			.setPriority(selectPriority(context))
			.setMinUpdateDistanceMeters(minDistanceInMeters.toFloat())
			.setMinUpdateIntervalMillis(minUpdateDelayInSeconds * Time.SECOND_IN_MILLISECONDS)
			.build()

		try {
			// checked by component manager
			@Suppress("MissingPermission")
			client.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
		} catch (e: SecurityException) {
			Reporter.report(e)
			receiver.onError(
				TrackerTimerErrorData(
					TrackerTimerErrorSeverity.STOP_SERVICE,
					R.string.notification_looking_for_gps,
					"Location permission revoked: ${e.message}"
				)
			)
		}
	}

	override fun onDisable(context: Context) {
		super.onDisable(context)
		LocationServices.getFusedLocationProviderClient(context)
				.removeLocationUpdates(locationCallback)
	}

	override fun updateInterval(context: Context, intervalSeconds: Int, minDistanceMeters: Int) {
		// Re-requesting updates with the same callback updates delivery parameters without a forced stop/start gap.
		val client = LocationServices.getFusedLocationProviderClient(context)
		val request = LocationRequest.Builder(intervalSeconds * Time.SECOND_IN_MILLISECONDS)
			.setPriority(selectPriority(context))
			.setMinUpdateDistanceMeters(minDistanceMeters.toFloat())
			.setMinUpdateIntervalMillis(intervalSeconds * Time.SECOND_IN_MILLISECONDS)
			.build()

		try {
			// checked by component manager
			@Suppress("MissingPermission")
			client.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
		} catch (e: SecurityException) {
			Reporter.report(e)
			receiver?.onError(
				TrackerTimerErrorData(
					TrackerTimerErrorSeverity.STOP_SERVICE,
					R.string.notification_looking_for_gps,
					"Location permission revoked during interval update: ${e.message}"
				)
			)
		}
	}

	private fun selectPriority(context: Context): Int {
		return if (context.hasPreciseLocationPermission) {
			Priority.PRIORITY_HIGH_ACCURACY
		} else {
			Priority.PRIORITY_BALANCED_POWER_ACCURACY
		}
	}
}
