package com.adsamcik.tracker.game.minigame.outrun

import com.adsamcik.tracker.game.minigame.MiniGameSession
import com.adsamcik.tracker.game.minigame.MiniGameState
import kotlin.math.max
import kotlin.math.min

/**
 * Outrun session: ghost moves at [ghostPaceMps] m/s.
 * Player distance tracked cumulatively from location updates.
 * Score = max distance player was ahead of ghost.
 */
internal class OutrunSession(
	private val ghostPaceMps: Double = DEFAULT_GHOST_PACE_MPS,
) : MiniGameSession() {

	private var startTimeMs: Long = 0L
	private var lastUpdateMs: Long = 0L
	private var playerDistanceM: Double = 0.0
	private var ghostDistanceM: Double = 0.0
	private var maxAheadM: Double = 0.0
	private var lastLat: Double = 0.0
	private var lastLon: Double = 0.0
	private var hasFirstFix: Boolean = false
	private var _state: MiniGameState = MiniGameState.IDLE

	override val state: MiniGameState get() = _state
	override val score: Double get() = maxAheadM

	override val statusText: String
		get() {
			val ahead = (playerDistanceM - ghostDistanceM).toInt()
			return if (ahead >= 0) "👻 +${ahead}m" else "👻 ${ahead}m"
		}

	override fun onLocationUpdate(
		latitude: Double,
		longitude: Double,
		speedMps: Float,
		accuracyM: Float,
		timestampMs: Long,
	) {
		if (accuracyM > MAX_ACCURACY_M) return

		if (!hasFirstFix) {
			hasFirstFix = true
			startTimeMs = timestampMs
			lastUpdateMs = timestampMs
			lastLat = latitude
			lastLon = longitude
			_state = MiniGameState.RUNNING
			return
		}

		// Player distance (haversine approximation for short distances)
		val dist = approximateDistance(lastLat, lastLon, latitude, longitude)
		if (dist > MIN_MOVE_M) {
			playerDistanceM += dist
			lastLat = latitude
			lastLon = longitude
		}

		// Ghost distance (constant pace, paused during vehicle speed)
		val dtSeconds = (timestampMs - lastUpdateMs) / 1000.0
		if (speedMps < VEHICLE_SPEED_THRESHOLD_MPS) {
			ghostDistanceM += ghostPaceMps * dtSeconds
		}
		lastUpdateMs = timestampMs

		// Update ahead/behind
		val ahead = playerDistanceM - ghostDistanceM
		maxAheadM = max(maxAheadM, ahead)

		// State transitions
		_state = when {
			ahead < -CAUGHT_THRESHOLD_M -> MiniGameState.FINISHED
			ahead < WARNING_THRESHOLD_M -> MiniGameState.WARNING
			else -> MiniGameState.RUNNING
		}
	}

	override fun onSessionEnd() {
		_state = MiniGameState.FINISHED
	}

	override fun calculatePoints(): Int {
		val basePoints = 30
		val distanceBonus = min(maxAheadM / 10.0, 170.0).toInt()
		return basePoints + distanceBonus
	}

	private fun approximateDistance(
		lat1: Double, lon1: Double, lat2: Double, lon2: Double,
	): Double {
		val dLat = Math.toRadians(lat2 - lat1)
		val dLon = Math.toRadians(lon2 - lon1)
		val avgLat = Math.toRadians((lat1 + lat2) / 2.0)
		val x = dLon * kotlin.math.cos(avgLat)
		return kotlin.math.sqrt(dLat * dLat + x * x) * EARTH_RADIUS_M
	}

	companion object {
		private const val DEFAULT_GHOST_PACE_MPS = 1.25 // ~4.5 km/h
		private const val MAX_ACCURACY_M = 30f
		private const val MIN_MOVE_M = 2.0
		private const val VEHICLE_SPEED_THRESHOLD_MPS = 8f // ~29 km/h
		private const val WARNING_THRESHOLD_M = 20.0
		private const val CAUGHT_THRESHOLD_M = 0.0
		private const val EARTH_RADIUS_M = 6_371_000.0
	}
}
