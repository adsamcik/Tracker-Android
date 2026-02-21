package com.adsamcik.tracker.game.minigame

/**
 * Abstract base for an active mini-game session running alongside tracking.
 * Receives location updates from the tracker pipeline.
 */
abstract class MiniGameSession {
	/** Current game state. */
	abstract val state: MiniGameState

	/** Current score value. */
	abstract val score: Double

	/** Human-readable status for floating UI chip. */
	abstract val statusText: String

	/**
	 * Called on each location update from the tracker.
	 * @param latitude current latitude
	 * @param longitude current longitude
	 * @param speedMps current speed in m/s (may be 0 if unavailable)
	 * @param accuracyM location accuracy in meters
	 * @param timestampMs location timestamp in millis
	 */
	abstract fun onLocationUpdate(
		latitude: Double,
		longitude: Double,
		speedMps: Float,
		accuracyM: Float,
		timestampMs: Long,
	)

	/** Called when the tracking session ends. Finalize score. */
	abstract fun onSessionEnd()

	/** Calculate XP earned from this session. */
	abstract fun calculateXp(): Int
}

/** Mini-game lifecycle states. */
enum class MiniGameState {
	IDLE,
	RUNNING,
	WARNING,
	FINISHED,
}
