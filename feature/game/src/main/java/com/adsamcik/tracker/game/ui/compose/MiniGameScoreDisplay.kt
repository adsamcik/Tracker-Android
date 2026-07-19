package com.adsamcik.tracker.game.ui.compose

import com.adsamcik.tracker.game.minigame.MiniGameScoreUnit
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Framework-neutral, unit-aware representation of a mini-game score.
 *
 * Each mini-game persists a single [Double] `score`, but that number means a
 * different thing per game (meters ahead, cells claimed, seconds in the pace
 * zone). This type carries the *typed* value so the UI can render a real unit
 * instead of a generic "pts" suffix. Pure Kotlin — no Android dependencies —
 * so it is exercised directly by unit tests.
 */
internal sealed interface MiniGameScoreDisplay {
	/** Outrun — best lead expressed in whole meters. */
	data class Meters(val meters: Int) : MiniGameScoreDisplay

	/** Territory — unique cells claimed. */
	data class Cells(val cells: Int) : MiniGameScoreDisplay

	/** Fuse Run — charges successfully defused. */
	data class Charges(val charges: Int) : MiniGameScoreDisplay

	/** Switchback — route turns successfully carved. */
	data class Turns(val turns: Int) : MiniGameScoreDisplay

	/**
	 * Zen Walk — time in the pace zone, pre-formatted as `m:ss`.
	 *
	 * @param totalSeconds raw seconds (retained for accessibility phrasing).
	 * @param formatted `m:ss` clock text.
	 */
	data class Duration(val totalSeconds: Long, val formatted: String) : MiniGameScoreDisplay

	/**
	 * Fallback for a game id no longer present in the registry. Keeps history
	 * readable without inventing a unit we cannot verify.
	 */
	data class Raw(val value: Long) : MiniGameScoreDisplay
}

/**
 * Map a persisted score to its typed [MiniGameScoreDisplay].
 *
 * @param unit the owning game's [MiniGameScoreUnit], or `null` when the game id
 *   is unknown (removed from the registry but rows survive on disk).
 * @param value the raw persisted score value.
 */
internal fun miniGameScoreDisplay(
	unit: MiniGameScoreUnit?,
	value: Double,
): MiniGameScoreDisplay {
	val safeValue = if (value.isFinite() && value >= 0.0) value else 0.0
	return when (unit) {
		MiniGameScoreUnit.DISTANCE_METERS -> MiniGameScoreDisplay.Meters(safeValue.roundToInt())
		MiniGameScoreUnit.CELL_COUNT -> MiniGameScoreDisplay.Cells(safeValue.roundToInt())
		MiniGameScoreUnit.DEFUSAL_COUNT -> MiniGameScoreDisplay.Charges(safeValue.roundToInt())
		MiniGameScoreUnit.TURN_COUNT -> MiniGameScoreDisplay.Turns(safeValue.roundToInt())
		MiniGameScoreUnit.DURATION_SECONDS -> {
			val seconds = safeValue.roundToLong()
			MiniGameScoreDisplay.Duration(seconds, formatDurationSeconds(seconds))
		}
		null -> MiniGameScoreDisplay.Raw(safeValue.roundToLong())
	}
}

/** Format a non-negative second count as `m:ss` (minutes are not zero-padded). */
internal fun formatDurationSeconds(totalSeconds: Long): String {
	val safe = totalSeconds.coerceAtLeast(0L)
	val minutes = safe / SECONDS_PER_MINUTE
	val seconds = safe % SECONDS_PER_MINUTE
	return "%d:%02d".format(minutes, seconds)
}

private const val SECONDS_PER_MINUTE = 60L
