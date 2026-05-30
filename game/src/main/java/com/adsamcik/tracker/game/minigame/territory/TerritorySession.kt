package com.adsamcik.tracker.game.minigame.territory

import com.adsamcik.tracker.game.minigame.MiniGameSession
import com.adsamcik.tracker.game.minigame.MiniGameState
import kotlin.math.floor
import kotlin.math.min

/**
 * Territory session: claim unique ~50m grid cells.
 * Grid: floor(lat/0.00045), floor(lon/0.00063) — approx 50m at mid-latitudes.
 * Only counts ON_FOOT speeds (< 8 m/s), accuracy < 30m, 2s debounce.
 */
class TerritorySession : MiniGameSession() {

	private val claimedCells = mutableSetOf<Long>()
	private var lastUpdateMs: Long = 0L
	private var _state: MiniGameState = MiniGameState.IDLE

	override val state: MiniGameState get() = _state
	override val score: Double get() = claimedCells.size.toDouble()

	override val statusText: String
		get() = "\uD83C\uDFF4 ${claimedCells.size} cells"

	override fun onLocationUpdate(
		latitude: Double,
		longitude: Double,
		speedMps: Float,
		accuracyM: Float,
		timestampMs: Long,
	) {
		// Accuracy filter
		if (accuracyM > MAX_ACCURACY_M) return
		// Speed filter (on-foot only)
		if (speedMps > MAX_ON_FOOT_SPEED_MPS) return
		// Debounce
		if (lastUpdateMs != 0L && timestampMs - lastUpdateMs < DEBOUNCE_MS) return
		lastUpdateMs = timestampMs

		if (_state == MiniGameState.IDLE) {
			_state = MiniGameState.RUNNING
		}

		val cellRow = floor(latitude / CELL_LAT_SIZE).toLong()
		val cellCol = floor(longitude / CELL_LON_SIZE).toLong()
		val cellKey = cellRow * CELL_KEY_MULTIPLIER + cellCol
		claimedCells.add(cellKey)
	}

	override fun onSessionEnd() {
		_state = MiniGameState.FINISHED
	}

	override fun calculatePoints(): Int {
		val cells = claimedCells.size
		// 20 base + 10 per cell (diminishing after 15)
		val fullBonus = min(cells, FULL_BONUS_CELLS) * POINTS_PER_CELL
		val diminishedBonus = if (cells > FULL_BONUS_CELLS) {
			((cells - FULL_BONUS_CELLS) * POINTS_PER_CELL_DIMINISHED).toInt()
		} else 0
		return BASE_POINTS + fullBonus + diminishedBonus
	}

	companion object {
		private const val CELL_LAT_SIZE = 0.00045
		private const val CELL_LON_SIZE = 0.00063
		private const val CELL_KEY_MULTIPLIER = 1_000_000L
		private const val MAX_ACCURACY_M = 30f
		private const val MAX_ON_FOOT_SPEED_MPS = 8f
		private const val DEBOUNCE_MS = 2000L
		private const val BASE_POINTS = 20
		private const val POINTS_PER_CELL = 10
		private const val POINTS_PER_CELL_DIMINISHED = 3.0
		private const val FULL_BONUS_CELLS = 15
	}
}
