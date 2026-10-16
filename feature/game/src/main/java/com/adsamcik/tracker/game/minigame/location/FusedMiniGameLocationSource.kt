package com.adsamcik.tracker.game.minigame.location

import android.annotation.SuppressLint
import android.app.Application
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
import kotlinx.coroutines.flow.mapNotNull

/**
 * Mini-game source backed by the app's shared Location owner.
 *
 * Each subscription holds an independent temporary lease and releases it on cancellation.
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
				maximumAgeMillis = if (request.intervalMillis >= 2_500L) 5_000L
					else maxOf(MINIMUM_SAMPLE_MAXIMUM_AGE_MS, request.intervalMillis * 2L),
				cancelOnAppBackground = false,
			),
		).mapNotNull { location ->
			// An absent observation time is not evidence that this is a new fix.
			if (location.time <= 0L) return@mapNotNull null
			MiniGameLocationSample(
				latitude = location.latitude,
				longitude = location.longitude,
				speedMps = if (location.hasSpeed()) location.speed else 0f,
				accuracyM = if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE,
				timestampMs = location.time,
			)
		}.buffer(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
	}

	private companion object {
		const val MINI_GAME_LOCATION_TAG = "mini-game-location"
	}
}

private const val MINIMUM_SAMPLE_MAXIMUM_AGE_MS = 1_000L
