package com.adsamcik.tracker.game.minigame.location

import android.annotation.SuppressLint
import android.app.Application
import android.os.Looper
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.extension.hasLocationPermission
import com.adsamcik.tracker.shared.base.extension.hasPreciseLocationPermission
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Default mini-game location source backed by Google Play Services Fused Location.
 *
 * Self-contained: does not depend on TrackerService. Each subscription opens its
 * own update stream and tears it down on cancellation, so leaving the session
 * screen guarantees no background sensor activity.
 *
 * No raw samples are persisted by this class — they are forwarded to the
 * collector and forgotten.
 */
@Singleton
class FusedMiniGameLocationSource @Inject constructor(
	private val application: Application,
) : MiniGameLocationSource {

	@SuppressLint("MissingPermission")
	override fun samples(): Flow<MiniGameLocationSample> = callbackFlow {
		if (!application.hasLocationPermission) {
			close(SecurityException("Location permission not granted"))
			return@callbackFlow
		}

		val client = LocationServices.getFusedLocationProviderClient(application)
		val callback = object : LocationCallback() {
			override fun onLocationResult(result: LocationResult) {
				for (loc in result.locations) {
					val sample = MiniGameLocationSample(
						latitude = loc.latitude,
						longitude = loc.longitude,
						speedMps = if (loc.hasSpeed()) loc.speed else 0f,
						accuracyM = if (loc.hasAccuracy()) loc.accuracy else Float.MAX_VALUE,
						timestampMs = loc.time.takeIf { it > 0L } ?: Time.nowMillis,
					)
					// Drop frames if downstream is slow — never block the location thread.
					trySendBlocking(sample)
				}
			}
		}

		val priority = if (application.hasPreciseLocationPermission) {
			Priority.PRIORITY_HIGH_ACCURACY
		} else {
			Priority.PRIORITY_BALANCED_POWER_ACCURACY
		}
		val request = LocationRequest.Builder(UPDATE_INTERVAL_MS)
			.setPriority(priority)
			.setMinUpdateIntervalMillis(MIN_INTERVAL_MS)
			.setMinUpdateDistanceMeters(0f)
			.build()

		try {
			client.requestLocationUpdates(request, callback, Looper.getMainLooper())
		} catch (e: SecurityException) {
			close(e)
			return@callbackFlow
		}

		awaitClose {
			client.removeLocationUpdates(callback)
		}
	}

	private companion object {
		private const val UPDATE_INTERVAL_MS = 2_000L
		private const val MIN_INTERVAL_MS = 1_000L
	}
}
