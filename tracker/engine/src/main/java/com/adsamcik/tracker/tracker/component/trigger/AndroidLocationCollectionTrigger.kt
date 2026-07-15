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
import com.adsamcik.tracker.shared.base.extension.hasSelfPermission
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.api.BackgroundTrackingApi
import com.adsamcik.tracker.tracker.component.DynamicIntervalCollectionTrigger
import com.adsamcik.tracker.tracker.component.TrackerTimerErrorData
import com.adsamcik.tracker.tracker.component.TrackerTimerErrorSeverity
import com.adsamcik.tracker.tracker.component.TrackerTimerReceiver

/**
 * Collection trigger that uses native Android location manager with GPS_PROVIDER.
 * Supports dynamic interval updates for policy-based adaptation.
 * Requires ACCESS_FINE_LOCATION since GPS_PROVIDER needs precise location.
 */
internal class AndroidLocationCollectionTrigger : LocationCollectionTrigger(), DynamicIntervalCollectionTrigger {
	override val requiredPermissions: Collection<String>
		get() = REQUIRED_PERMISSIONS

	// GPS_PROVIDER requires FINE location — override OR-logic from base interface
	override fun hasRequiredPermissions(context: Context): Boolean {
		return context.hasSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
	}

	override val titleRes: Int
		get() = TITLE_RES

	private val locationListener: LocationListener = object : LocationListener {
		override fun onLocationChanged(location: Location) {
			receiver ?: return
			onNewData(listOf(location))
		}

		override fun onProviderDisabled(provider: String) {
			val errorData = TrackerTimerErrorData(
					TrackerTimerErrorSeverity.NOTIFY_USER,
					R.string.notification_looking_for_gps
			)
			val localReceiver = receiver ?: return
			localReceiver.onError(errorData)
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
			locationManager.requestLocationUpdates(
					LocationManager.GPS_PROVIDER,
					minUpdateDelayInSeconds * Time.SECOND_IN_MILLISECONDS,
					minDistanceInMeters.toFloat(),
					locationListener,
					Looper.getMainLooper()
			)
		} catch (_: SecurityException) {
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
			locationManager.requestLocationUpdates(
				LocationManager.GPS_PROVIDER,
				intervalSeconds * Time.SECOND_IN_MILLISECONDS,
				minDistanceMeters.toFloat(),
				locationListener,
				Looper.getMainLooper()
			)
		} catch (_: SecurityException) {
			receiver?.onError(
				TrackerTimerErrorData(
					TrackerTimerErrorSeverity.STOP_SERVICE,
					R.string.notification_looking_for_gps,
				)
			)
		}
	}

	companion object {
		val REQUIRED_PERMISSIONS = listOf(
			Manifest.permission.ACCESS_FINE_LOCATION,
			Manifest.permission.ACCESS_COARSE_LOCATION,
		)
		const val TITLE_RES = R.string.settings_tracker_timer_location
	}
}
