package com.adsamcik.tracker.game.minigame

/**
 * Privacy-safe Fuse Run presentation state. The charge and player positions
 * remain private inside the engine; the UI receives only derived distances,
 * durations, counts, and streaks.
 */
internal data class FuseRunVisualPayload(
	val hasStarted: Boolean,
	val defusedCharges: Int,
	val targetCharges: Int,
	val roundNumber: Int,
	val distanceFromChargeMeters: Double,
	val requiredDistanceMeters: Double,
	val remainingTimeMs: Long,
	val roundDurationMs: Long,
	val currentStreak: Int,
	val bestStreak: Int,
) : MiniGameVisualPayload {
	init {
		require(defusedCharges >= 0) { "Defused charge count cannot be negative" }
		require(targetCharges > 0) { "Target charge count must be positive" }
		require(roundNumber > 0) { "Round number must be positive" }
		require(distanceFromChargeMeters.isFinite() && distanceFromChargeMeters >= 0.0) {
			"Charge distance must be finite and non-negative"
		}
		require(requiredDistanceMeters.isFinite() && requiredDistanceMeters > 0.0) {
			"Required distance must be finite and positive"
		}
		require(remainingTimeMs >= 0L) { "Remaining fuse time cannot be negative" }
		require(roundDurationMs > 0L) { "Round duration must be positive" }
		require(remainingTimeMs <= roundDurationMs) {
			"Remaining fuse time cannot exceed round duration"
		}
		require(currentStreak >= 0) { "Current streak cannot be negative" }
		require(bestStreak >= currentStreak) { "Best streak cannot trail the current streak" }
	}

	val distanceFraction: Double
		get() = (distanceFromChargeMeters / requiredDistanceMeters).coerceIn(0.0, 1.0)

	val fuseFraction: Double
		get() = (remainingTimeMs.toDouble() / roundDurationMs).coerceIn(0.0, 1.0)

	val isFuseCritical: Boolean
		get() = hasStarted && remainingTimeMs <= CRITICAL_FUSE_MS && defusedCharges < targetCharges

	val isGoalReached: Boolean
		get() = defusedCharges >= targetCharges

	private companion object {
		const val CRITICAL_FUSE_MS: Long = 8_000L
	}
}
