package com.adsamcik.tracker.tracker.component.trigger

import android.Manifest
import android.content.Context
import android.os.Looper
import com.adsamcik.tracker.logger.Reporter
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
			if (receiver == null) {
				Reporter.report("Received location update with null callback")
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
				receiver?.onError(errorData)
			}
		}
	}

	override fun onEnable(context: Context, receiver: TrackerTimerReceiver) {
		super.onEnable(context, receiver)

		val minUpdateDelayInSeconds = BackgroundTrackingApi.cachedParams.minTimeSeconds
		val minDistanceInMeters = BackgroundTrackingApi.cachedParams.minDistanceMeters

		// Adapt priority based on granted permissions: high accuracy for precise, balanced for coarse
		val priority = if (context.hasPreciseLocationPermission) {
			Priority.PRIORITY_HIGH_ACCURACY
		} else {
			Priority.PRIORITY_BALANCED_POWER_ACCURACY
		}

		val client = LocationServices.getFusedLocationProviderClient(context)
		val request = LocationRequest.Builder(minUpdateDelayInSeconds * Time.SECOND_IN_MILLISECONDS)
			.setPriority(priority)
			.setMinUpdateDistanceMeters(minDistanceInMeters.toFloat())
			.setMinUpdateIntervalMillis(minUpdateDelayInSeconds * Time.SECOND_IN_MILLISECONDS)
			.build()

		// checked by component manager
		@Suppress("MissingPermission")
		client.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
	}

	override fun onDisable(context: Context) {
		super.onDisable(context)
		LocationServices.getFusedLocationProviderClient(context)
				.removeLocationUpdates(locationCallback)
	}

	override fun updateInterval(context: Context, intervalSeconds: Int, minDistanceMeters: Int) {
		// Update location request interval dynamically by restarting with new parameters
		val client = LocationServices.getFusedLocationProviderClient(context)
		client.removeLocationUpdates(locationCallback)

		// Adapt priority based on granted permissions: high accuracy for precise, balanced for coarse
		val priority = if (context.hasPreciseLocationPermission) {
			Priority.PRIORITY_HIGH_ACCURACY
		} else {
			Priority.PRIORITY_BALANCED_POWER_ACCURACY
		}

		val request = LocationRequest.Builder(intervalSeconds * Time.SECOND_IN_MILLISECONDS)
			.setPriority(priority)
			.setMinUpdateDistanceMeters(minDistanceMeters.toFloat())
			.setMinUpdateIntervalMillis(intervalSeconds * Time.SECOND_IN_MILLISECONDS)
			.build()

		// checked by component manager
		@Suppress("MissingPermission")
		client.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
	}
}

