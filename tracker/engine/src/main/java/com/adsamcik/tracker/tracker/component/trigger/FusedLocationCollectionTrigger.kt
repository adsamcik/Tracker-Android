package com.adsamcik.tracker.tracker.component.trigger

import android.Manifest
import android.content.Context
import android.os.Looper
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.LocationAcquisitionMode
import com.adsamcik.tracker.shared.base.data.LocationRequestPriority
import com.adsamcik.tracker.shared.base.extension.hasPreciseLocationPermission
import com.adsamcik.tracker.shared.preferences.tracking.TrackingPreset
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.api.BackgroundTrackingApi
import com.adsamcik.tracker.tracker.component.AdaptiveLocationCollectionTrigger
import com.adsamcik.tracker.tracker.component.DynamicIntervalCollectionTrigger
import com.adsamcik.tracker.tracker.component.LocationRequestFidelity
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
 * Supports dynamic interval and request-fidelity updates for policy-based adaptation.
 * Accepts either fine (precise) or coarse (approximate) location permission.
 */
internal class FusedLocationCollectionTrigger : LocationCollectionTrigger(),
	DynamicIntervalCollectionTrigger,
	AdaptiveLocationCollectionTrigger {
	override val acquisitionMode: LocationAcquisitionMode = LocationAcquisitionMode.FUSED
	override val requestPriority: LocationRequestPriority
		get() = activeRequestPriority

	@Volatile
	private var activeRequestPriority: LocationRequestPriority = LocationRequestPriority.UNKNOWN

	@Volatile
	private var requestFidelity: LocationRequestFidelity = LocationRequestFidelity.BALANCED

	override val requiredPermissions: Collection<String>
		get() = REQUIRED_PERMISSIONS

	override val titleRes: Int
		get() = TITLE_RES

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
			val localReceiver = receiver ?: return
			localReceiver.onLocationProviderAvailabilityChanged(
				available = availability.isLocationAvailable,
				reason = "FUSED_LOCATION_AVAILABILITY",
			)
			if (!availability.isLocationAvailable) {
				val errorData = TrackerTimerErrorData(
					TrackerTimerErrorSeverity.NOTIFY_USER,
					R.string.notification_looking_for_gps,
				)
				localReceiver.onError(errorData)
			}
		}
	}

	override fun onEnable(context: Context, receiver: TrackerTimerReceiver) {
		super.onEnable(context, receiver)

		val minUpdateDelayInSeconds = BackgroundTrackingApi.cachedParams.minTimeSeconds
		val minDistanceInMeters = BackgroundTrackingApi.cachedParams.minDistanceMeters

		val client = LocationServices.getFusedLocationProviderClient(context)
		val configuredRequest = buildRequest(context, minUpdateDelayInSeconds, minDistanceInMeters)

		try {
			// checked by component manager
			@Suppress("MissingPermission")
			client.requestLocationUpdates(
				configuredRequest.request,
				locationCallback,
				Looper.getMainLooper(),
			)
			activeRequestPriority = configuredRequest.priority
		} catch (e: SecurityException) {
			Reporter.report(e)
			receiver.onLocationProviderAvailabilityChanged(
				available = false,
				reason = "FUSED_LOCATION_PERMISSION_REVOKED",
			)
			receiver.onError(
				TrackerTimerErrorData(
					TrackerTimerErrorSeverity.STOP_SERVICE,
					R.string.notification_looking_for_gps,
					"Location permission revoked: ${e.message}",
				),
			)
		}
	}

	override fun onDisable(context: Context) {
		super.onDisable(context)
		activeRequestPriority = LocationRequestPriority.UNKNOWN
		LocationServices.getFusedLocationProviderClient(context)
			.removeLocationUpdates(locationCallback)
	}

	override fun updateInterval(context: Context, intervalSeconds: Int, minDistanceMeters: Int) {
		// Re-requesting updates with the same callback updates delivery parameters without a forced
		// stop/start gap.
		val client = LocationServices.getFusedLocationProviderClient(context)
		val configuredRequest = buildRequest(context, intervalSeconds, minDistanceMeters)

		try {
			// checked by component manager
			@Suppress("MissingPermission")
			client.requestLocationUpdates(
				configuredRequest.request,
				locationCallback,
				Looper.getMainLooper(),
			)
			activeRequestPriority = configuredRequest.priority
		} catch (e: SecurityException) {
			Reporter.report(e)
			val localReceiver = receiver
			localReceiver?.onLocationProviderAvailabilityChanged(
				available = false,
				reason = "FUSED_LOCATION_PERMISSION_REVOKED_DURING_UPDATE",
			)
			localReceiver?.onError(
				TrackerTimerErrorData(
					TrackerTimerErrorSeverity.STOP_SERVICE,
					R.string.notification_looking_for_gps,
					"Location permission revoked during interval update: ${e.message}",
				),
			)
		}
	}

	override fun updateRequestFidelity(fidelity: LocationRequestFidelity) {
		requestFidelity = fidelity
	}

	private fun buildRequest(
		context: Context,
		intervalSeconds: Int,
		minDistanceMeters: Int,
	): ConfiguredLocationRequest {
		val intervalMs = intervalSeconds.coerceAtLeast(1) * Time.SECOND_IN_MILLISECONDS
		val priority = selectPriority(context)
		val batchingMultiplier = when (BackgroundTrackingApi.cachedParams.preset) {
			TrackingPreset.POWER_SAVE -> 3L
			TrackingPreset.BALANCED -> 2L
			TrackingPreset.HIGH_ACCURACY,
			TrackingPreset.CUSTOM,
			-> 1L
		}
		val request = LocationRequest.Builder(intervalMs)
			.setPriority(priority)
			.setMinUpdateDistanceMeters(minDistanceMeters.coerceAtLeast(0).toFloat())
			.setMinUpdateIntervalMillis(intervalMs)
			.setMaxUpdateDelayMillis(intervalMs * batchingMultiplier)
			.setWaitForAccurateLocation(priority == Priority.PRIORITY_HIGH_ACCURACY)
			.build()
		return ConfiguredLocationRequest(request, priority.toAcquisitionPriority())
	}

	private fun selectPriority(context: Context): Int {
		return when (requestFidelity) {
			LocationRequestFidelity.DISABLED,
			LocationRequestFidelity.PASSIVE,
			-> Priority.PRIORITY_PASSIVE
			LocationRequestFidelity.LOW_POWER -> Priority.PRIORITY_LOW_POWER
			LocationRequestFidelity.BALANCED -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
			LocationRequestFidelity.HIGH_ACCURACY,
			LocationRequestFidelity.PROBE,
			-> if (context.hasPreciseLocationPermission) {
				Priority.PRIORITY_HIGH_ACCURACY
			} else {
				Priority.PRIORITY_BALANCED_POWER_ACCURACY
			}
		}
	}

	private fun Int.toAcquisitionPriority(): LocationRequestPriority = when (this) {
		Priority.PRIORITY_PASSIVE -> LocationRequestPriority.PASSIVE
		Priority.PRIORITY_LOW_POWER -> LocationRequestPriority.LOW_POWER
		Priority.PRIORITY_BALANCED_POWER_ACCURACY -> LocationRequestPriority.BALANCED
		Priority.PRIORITY_HIGH_ACCURACY -> LocationRequestPriority.HIGH_ACCURACY
		else -> LocationRequestPriority.UNKNOWN
	}

	private data class ConfiguredLocationRequest(
		val request: LocationRequest,
		val priority: LocationRequestPriority,
	)

	companion object {
		val REQUIRED_PERMISSIONS = listOf(
			Manifest.permission.ACCESS_FINE_LOCATION,
			Manifest.permission.ACCESS_COARSE_LOCATION,
		)
		const val TITLE_RES = R.string.settings_tracker_timer_fused
	}
}
