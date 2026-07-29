package com.adsamcik.tracker.tracker.component.trigger

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.LocationAcquisitionMode
import com.adsamcik.tracker.shared.base.data.LocationRequestPriority
import com.adsamcik.tracker.shared.base.extension.locationManager
import com.adsamcik.tracker.shared.base.extension.hasSelfPermission
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.component.AdaptiveLocationCollectionTrigger
import com.adsamcik.tracker.tracker.api.BackgroundTrackingApi
import com.adsamcik.tracker.tracker.component.DynamicIntervalCollectionTrigger
import com.adsamcik.tracker.tracker.component.LocationRequestFidelity
import com.adsamcik.tracker.tracker.component.TrackerTimerErrorData
import com.adsamcik.tracker.tracker.component.TrackerTimerErrorSeverity
import com.adsamcik.tracker.tracker.component.TrackerTimerReceiver

/**
 * Collection trigger that uses native Android location manager with GPS_PROVIDER.
 * Supports dynamic interval updates for policy-based adaptation.
 * Requires ACCESS_FINE_LOCATION since GPS_PROVIDER needs precise location.
 */
internal class AndroidLocationCollectionTrigger : LocationCollectionTrigger(),
	DynamicIntervalCollectionTrigger,
	AdaptiveLocationCollectionTrigger {
	override val acquisitionMode: LocationAcquisitionMode = LocationAcquisitionMode.PLATFORM_GPS
	override val requestPriority: LocationRequestPriority
		get() = requestFidelity.toRequestPriority()

	@Volatile
	private var requestFidelity: LocationRequestFidelity = LocationRequestFidelity.HIGH_ACCURACY

	override val requiredPermissions: Collection<String>
		get() = REQUIRED_PERMISSIONS

	// GPS_PROVIDER requires FINE location; passive/network modes can work with approximate location.
	override fun hasRequiredPermissions(context: Context): Boolean {
		return if (
			requestFidelity == LocationRequestFidelity.HIGH_ACCURACY ||
			requestFidelity == LocationRequestFidelity.PROBE
		) {
			context.hasSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
		} else {
			context.hasSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
				context.hasSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
		}
	}

	override val titleRes: Int
		get() = TITLE_RES

	private val locationListener: LocationListener = object : LocationListener {
		override fun onLocationChanged(location: Location) {
			receiver ?: return
			onNewData(listOf(location))
		}

		override fun onProviderDisabled(provider: String) {
			val localReceiver = receiver ?: return
			localReceiver.onLocationProviderAvailabilityChanged(
				available = false,
				reason = "ANDROID_PROVIDER_DISABLED:$provider",
			)
			val errorData = TrackerTimerErrorData(
				TrackerTimerErrorSeverity.NOTIFY_USER,
				R.string.notification_looking_for_gps,
			)
			localReceiver.onError(errorData)
		}

		override fun onProviderEnabled(provider: String) {
			receiver?.onLocationProviderAvailabilityChanged(
				available = true,
				reason = "ANDROID_PROVIDER_ENABLED:$provider",
			)
		}
	}

	override fun onEnable(context: Context, receiver: TrackerTimerReceiver) {
		super.onEnable(context, receiver)

		val minUpdateDelayInSeconds = BackgroundTrackingApi.cachedParams.minTimeSeconds
		val minDistanceInMeters = BackgroundTrackingApi.cachedParams.minDistanceMeters

		val locationManager = context.locationManager
		// Permission is checked by the component system before we get here, but the user
		// can revoke FINE_LOCATION between that check and this call (or during a dynamic
		// interval restart). Surface as a recoverable error instead of crashing the
		// foreground service.
		try {
			@Suppress("MissingPermission")
			requestLocationUpdates(
				locationManager = locationManager,
				intervalMs = minUpdateDelayInSeconds * Time.SECOND_IN_MILLISECONDS,
				minDistanceMeters = minDistanceInMeters,
			)
		} catch (_: SecurityException) {
			receiver.onLocationProviderAvailabilityChanged(
				available = false,
				reason = "ANDROID_LOCATION_PERMISSION_REVOKED",
			)
			receiver.onError(
				TrackerTimerErrorData(
					TrackerTimerErrorSeverity.STOP_SERVICE,
					R.string.notification_looking_for_gps,
				)
			)
		}
	}

	override fun onDisable(context: Context) {
		super.onDisable(context)

		context.locationManager.removeUpdates(locationListener)
	}

	override fun updateInterval(context: Context, intervalSeconds: Int, minDistanceMeters: Int) {
		// LocationManager has no in-place interval mutation API; restart is required to apply new values.
		val locationManager = context.locationManager
		locationManager.removeUpdates(locationListener)

		// Same SecurityException race as onEnable — permission may be revoked between
		// the original check and this restart on a policy/quality change.
		try {
			@Suppress("MissingPermission")
			requestLocationUpdates(
				locationManager = locationManager,
				intervalMs = intervalSeconds * Time.SECOND_IN_MILLISECONDS,
				minDistanceMeters = minDistanceMeters,
			)
		} catch (_: SecurityException) {
			val localReceiver = receiver
			localReceiver?.onLocationProviderAvailabilityChanged(
				available = false,
				reason = "ANDROID_LOCATION_PERMISSION_REVOKED_DURING_UPDATE",
			)
			localReceiver?.onError(
				TrackerTimerErrorData(
					TrackerTimerErrorSeverity.STOP_SERVICE,
					R.string.notification_looking_for_gps,
				)
			)
		}
	}

	override fun updateRequestFidelity(fidelity: LocationRequestFidelity) {
		requestFidelity = fidelity
	}

	@Suppress("MissingPermission")
	private fun requestLocationUpdates(
		locationManager: LocationManager,
		intervalMs: Long,
		minDistanceMeters: Int,
	) {
		if (requestFidelity == LocationRequestFidelity.DISABLED) return
		locationManager.requestLocationUpdates(
			selectedProvider(locationManager),
			intervalMs.coerceAtLeast(0L),
			minDistanceMeters.coerceAtLeast(0).toFloat(),
			locationListener,
			Looper.getMainLooper(),
		)
	}

	private fun selectedProvider(locationManager: LocationManager): String = when (requestFidelity) {
		LocationRequestFidelity.DISABLED,
		LocationRequestFidelity.PASSIVE,
		-> LocationManager.PASSIVE_PROVIDER
		LocationRequestFidelity.LOW_POWER,
		LocationRequestFidelity.BALANCED,
		-> if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
			LocationManager.NETWORK_PROVIDER
		} else {
			LocationManager.PASSIVE_PROVIDER
		}
		LocationRequestFidelity.HIGH_ACCURACY,
		LocationRequestFidelity.PROBE,
		-> LocationManager.GPS_PROVIDER
	}

	private fun LocationRequestFidelity.toRequestPriority(): LocationRequestPriority = when (this) {
		LocationRequestFidelity.DISABLED,
		LocationRequestFidelity.PASSIVE,
		-> LocationRequestPriority.PASSIVE
		LocationRequestFidelity.LOW_POWER -> LocationRequestPriority.LOW_POWER
		LocationRequestFidelity.BALANCED -> LocationRequestPriority.BALANCED
		LocationRequestFidelity.HIGH_ACCURACY,
		LocationRequestFidelity.PROBE,
		-> LocationRequestPriority.HIGH_ACCURACY
	}

	companion object {
		val REQUIRED_PERMISSIONS = listOf(
			Manifest.permission.ACCESS_FINE_LOCATION,
			Manifest.permission.ACCESS_COARSE_LOCATION,
		)
		const val TITLE_RES = R.string.settings_tracker_timer_location
	}
}
