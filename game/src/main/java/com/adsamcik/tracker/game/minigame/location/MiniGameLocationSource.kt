package com.adsamcik.tracker.game.minigame.location

import kotlinx.coroutines.flow.Flow

/**
 * A single fix delivered to a [com.adsamcik.tracker.game.minigame.MiniGameSession].
 *
 * Mini-game samples are never persisted: they exist only in-flight inside the
 * session VM and are dropped when the user leaves the screen.
 */
data class MiniGameLocationSample(
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
 *
 * Throws [SecurityException] inside the flow if the caller does not hold
 * a location permission. Callers must check permission before collecting.
 */
interface MiniGameLocationSource {
	/**
	 * Cold flow of location samples at roughly the requested cadence.
	 * Cancellation unsubscribes from the underlying provider.
	 */
	fun samples(): Flow<MiniGameLocationSample>
}
