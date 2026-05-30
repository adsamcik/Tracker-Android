package com.adsamcik.tracker.game.minigame.location

import android.annotation.SuppressLint
import android.app.Application
import android.os.Looper
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.extension.hasLocationPermission
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
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
 *
 * Cadence/accuracy comes from the per-game [LocationRequest] passed in by the
 * caller (see [com.adsamcik.tracker.game.minigame.MiniGame.desiredLocationRequest]),
 * so battery cost matches each game's needs.
 */
@Singleton
class FusedMiniGameLocationSource @Inject constructor(
	private val application: Application,
) : MiniGameLocationSource {

	@SuppressLint("MissingPermission")
	override fun samples(request: LocationRequest): Flow<MiniGameLocationSample> = callbackFlow {
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
					// Non-blocking publish: the UI only ever cares about the latest fix,
					// so a saturated channel means the previous sample is already stale
					// and can be safely dropped. trySendBlocking would have parked the
					// location callback thread behind a slow main-thread collector and
					// risked an ANR.
					trySend(sample)
				}
			}
		}

		try {
			client.requestLocationUpdates(request, callback, Looper.getMainLooper())
		} catch (e: SecurityException) {
			close(e)
			return@callbackFlow
		}

		awaitClose {
			client.removeLocationUpdates(callback)
		}
	}.buffer(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
}
