package com.adsamcik.tracker.game.minigame.location

import com.google.android.gms.location.LocationRequest
import kotlinx.coroutines.flow.Flow

/**
 * A single fix delivered to a [com.adsamcik.tracker.game.minigame.MiniGameSession].
 *
 * Mini-game samples are never persisted: they exist only in-flight inside the
 * session VM and are dropped when the user leaves the screen.
 */
internal data class MiniGameLocationSample(
	val latitude: Double,
	val longitude: Double,
	val speedMps: Float,
	val accuracyM: Float,
	val timestampMs: Long,
)

/**
 * Source of live location samples for mini-game sessions.
 *
 * Implementations MUST:
 *  - Be self-sufficient: do not require the TrackerService to be running.
 *  - Stop emitting and release sensor handles when the flow is cancelled
 *    (use `callbackFlow { awaitClose { ... } }`).
 *  - Never persist raw samples to disk — mini-games are ephemeral.
 *  - Honour the per-game [LocationRequest] passed to [samples] so battery
 *    and accuracy match the game's needs (Outrun wants tight HIGH_ACCURACY
 *    fixes; Zen Walk is happy with sparse BALANCED ones).
 *
 * Throws [SecurityException] inside the flow if the caller does not hold
 * a location permission. Callers must check permission before collecting.
 */
internal interface MiniGameLocationSource {
	/**
	 * Cold flow of location samples honouring [request].
	 *
	 * Each new subscriber gets its own update stream; cancellation
	 * unsubscribes and releases the underlying provider callback.
	 */
	fun samples(request: LocationRequest): Flow<MiniGameLocationSample>
}
