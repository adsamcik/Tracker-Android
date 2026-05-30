package com.adsamcik.tracker.game.minigame.location

import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.Location as TrackerLocation
import com.adsamcik.tracker.stats.api.TrackerLiveLocationFeed
import com.google.android.gms.location.LocationRequest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/**
 * Router that prefers the tracker's existing live location stream when the
 * tracker service is running, and falls back to a dedicated Fused Location
 * subscription otherwise.
 *
 * Rationale:
 *  - When the tracker service is active it already holds a precise GPS
 *    subscription via the FusedLocationProvider. Asking the framework for a
 *    second concurrent subscription from inside a mini-game session doubles
 *    the radio cost and wake-ups without producing extra information.
 *  - When the tracker is off, mini-games must remain self-sufficient so users
 *    can play without enabling background tracking. In that case the router
 *    delegates to [FusedMiniGameLocationSource], which opens and tears down
 *    its own subscription scoped to the flow lifetime.
 *
 * The decision is reactive: [TrackerLiveLocationFeed.isActiveFlow] drives a
 * `flatMapLatest` so that if the tracker starts or stops *during* a mini-game,
 * the source seamlessly switches sides. `flatMapLatest` guarantees the
 * previous upstream is cancelled before the new one is collected — in
 * particular, if the tracker stops mid-session the Fused fallback opens
 * automatically and vice versa.
 *
 * Backpressure is applied once at the outer flow (capacity 1, DROP_OLDEST) so
 * a slow main-thread collector cannot stall either upstream regardless of
 * which side is currently active.
 */
@Singleton
class LiveOrFusedMiniGameLocationSource @Inject constructor(
	private val liveFeed: TrackerLiveLocationFeed,
	private val fusedFallback: FusedMiniGameLocationSource,
) : MiniGameLocationSource {

	@OptIn(ExperimentalCoroutinesApi::class)
	override fun samples(request: LocationRequest): Flow<MiniGameLocationSample> =
		liveFeed.isActiveFlow
			.flatMapLatest { active ->
				if (active) {
					liveFeed.locations().map { it.toMiniGameLocationSample() }
				} else {
					fusedFallback.samples(request)
				}
			}
			.buffer(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

	private fun TrackerLocation.toMiniGameLocationSample(): MiniGameLocationSample =
		MiniGameLocationSample(
			latitude = latitude,
			longitude = longitude,
			speedMps = speed ?: 0f,
			accuracyM = horizontalAccuracy ?: Float.MAX_VALUE,
			timestampMs = time.takeIf { it > 0L } ?: Time.nowMillis,
		)
}
