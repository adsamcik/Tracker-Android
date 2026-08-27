package com.adsamcik.tracker.game.minigame.location

import android.annotation.SuppressLint
import android.app.Application
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.extension.hasLocationPermission
import com.adsamcik.tracker.shared.base.location.UiLocationProvider
import com.adsamcik.tracker.shared.base.location.UiLocationRequest
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.Priority
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * Default standalone mini-game source backed by the shared fused/framework UI provider.
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
internal class FusedMiniGameLocationSource @Inject constructor(
	private val application: Application,
	private val uiLocationProvider: UiLocationProvider,
) : MiniGameLocationSource {

	@SuppressLint("MissingPermission")
	override fun samples(request: LocationRequest): Flow<MiniGameLocationSample> {
		if (!application.hasLocationPermission) {
			return flow { throw SecurityException("Location permission not granted") }
		}
		return uiLocationProvider.locationUpdates(
			UiLocationRequest(
				tag = MINI_GAME_LOCATION_TAG,
				intervalMillis = request.intervalMillis,
				minUpdateIntervalMillis = request.minUpdateIntervalMillis,
				minimumDisplacementMeters = request.minUpdateDistanceMeters,
				highAccuracy = request.priority == Priority.PRIORITY_HIGH_ACCURACY,
			),
		).map { location ->
			MiniGameLocationSample(
				latitude = location.latitude,
				longitude = location.longitude,
				speedMps = if (location.hasSpeed()) location.speed else 0f,
				accuracyM = if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE,
				timestampMs = location.time.takeIf { it > 0L } ?: Time.nowMillis,
			)
		}.buffer(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
	}

	private companion object {
		const val MINI_GAME_LOCATION_TAG = "mini-game-location"
	}
}
