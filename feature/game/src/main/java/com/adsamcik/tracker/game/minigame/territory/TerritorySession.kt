package com.adsamcik.tracker.game.minigame.territory

import com.adsamcik.tracker.game.minigame.MiniGameFeedbackCue
import com.adsamcik.tracker.game.minigame.MiniGameCompletionOutcome
import com.adsamcik.tracker.game.minigame.MiniGameConfigurations
import com.adsamcik.tracker.game.minigame.MiniGamePhase
import com.adsamcik.tracker.game.minigame.MiniGameSession
import com.adsamcik.tracker.game.minigame.MiniGameSignal
import com.adsamcik.tracker.game.minigame.MiniGameSignalQuality
import com.adsamcik.tracker.game.minigame.MiniGameState
import com.adsamcik.tracker.game.minigame.MiniGameVisualPayload
import com.adsamcik.tracker.game.minigame.TerritoryConfiguration
import com.adsamcik.tracker.game.minigame.TerritoryRelativeCell
import kotlin.math.floor
import kotlin.math.min

/**
 * Territory session: claim unique ~50m grid cells.
 * Cells use a private local-meter projection anchored to the first accepted fix.
 * The origin never leaves the engine; snapshots publish only a 7x7 relative view.
 * Only counts ON_FOOT speeds (< 8 m/s), accuracy < 30m, 2s debounce.
 */
internal class TerritorySession(
	private val configuration: TerritoryConfiguration = MiniGameConfigurations.DEFAULT_TERRITORY,
	private val personalBestBeforeRun: Double? = null,
) : MiniGameSession(configuration, personalBestBeforeRun) {

	private val claimedCells = mutableSetOf<Cell>()
	private val recentCells = ArrayDeque<Cell>()
	private var originLatitude: Double? = null
	private var originLongitude: Double? = null
	private var lastUpdateMs: Long? = null
	private var _state: MiniGameState = MiniGameState.IDLE
	private var currentCell: Cell? = null
	private var lastSignal: MiniGameSignal = MiniGameSignal.UNKNOWN
	private var goalReached: Boolean = false
	private var personalBestCrossed: Boolean = false
	private var completionFeedbackEmitted: Boolean = false

	override val state: MiniGameState get() = _state
	override val score: Double get() = claimedCells.size.toDouble()
	override val snapshot
		get() = buildSnapshot(
			phase = state.toSnapshotPhase(),
			signal = lastSignal,
			currentScore = score,
			visualPayload = MiniGameVisualPayload.Territory(
				claimedCells = claimedCells.mapNotNullTo(linkedSetOf(), ::toRelativeCell),
				currentCell = currentCell?.let(::toRelativeCell),
				recentTrail = recentCells.mapNotNull(::toRelativeCell),
			),
		)

	override val statusText: String
		get() = "\uD83C\uDFF4 ${claimedCells.size} cells"

	override fun onLocationUpdate(
		latitude: Double,
		longitude: Double,
		speedMps: Float,
		accuracyM: Float,
		timestampMs: Long,
	) {
		if (_state == MiniGameState.FINISHED) return
		// Accuracy filter
		if (accuracyM > MAX_ACCURACY_M) {
			lastSignal = MiniGameSignal(MiniGameSignalQuality.POOR, ageMs = 0L, isStale = false)
			return
		}

		// Speed filter (on-foot only)
		if (speedMps > MAX_ON_FOOT_SPEED_MPS) return
		// Debounce
		if (lastUpdateMs != null && timestampMs - lastUpdateMs!! < DEBOUNCE_MS) return
		lastUpdateMs = timestampMs
		lastSignal = MiniGameSignal(MiniGameSignalQuality.GOOD, ageMs = 0L, isStale = false)

		if (_state == MiniGameState.IDLE) {
			_state = MiniGameState.RUNNING
			originLatitude = latitude
			originLongitude = longitude
		}

		val cell = locationToCell(latitude, longitude)
		currentCell = cell
		if (recentCells.lastOrNull() != cell) {
			if (recentCells.size == MAX_RECENT_TRAIL_CELLS) recentCells.removeFirst()
			recentCells.addLast(cell)
		}
		if (!claimedCells.add(cell)) return

		emitFeedback(MiniGameFeedbackCue.TerritoryCellClaimed)
		if (
			!personalBestCrossed &&
			personalBestBeforeRun != null &&
			claimedCells.size - 1 <= personalBestBeforeRun &&
			score > personalBestBeforeRun
		) {
			personalBestCrossed = true
			emitFeedback(MiniGameFeedbackCue.PersonalBestCrossed)
		}
		if (!goalReached && claimedCells.size >= configuration.goal.cells) {
			goalReached = true
			_state = MiniGameState.FINISHED
			emitFeedback(MiniGameFeedbackCue.GoalReached)
		}
	}

	private fun MiniGameState.toSnapshotPhase(): MiniGamePhase = when (this) {
		MiniGameState.IDLE -> MiniGamePhase.WAITING_TO_START
		MiniGameState.RUNNING -> MiniGamePhase.ACTIVE
		MiniGameState.WARNING -> MiniGamePhase.WARNING
		MiniGameState.FINISHED -> MiniGamePhase.COMPLETED
	}

	override fun onSessionEnd() {
		_state = MiniGameState.FINISHED
		emitCompletionFeedback()
	}

	override fun calculatePoints(): Int {
		val cells = claimedCells.size
		if (cells <= 1) return 0
		// 20 base + 10 per cell (diminishing after 15)
		val fullBonus = min(cells, FULL_BONUS_CELLS) * POINTS_PER_CELL
		val diminishedBonus = if (cells > FULL_BONUS_CELLS) {
			((cells - FULL_BONUS_CELLS) * POINTS_PER_CELL_DIMINISHED).toInt()
		} else 0
		return BASE_POINTS + fullBonus + diminishedBonus
	}

	private fun locationToCell(latitude: Double, longitude: Double): Cell {
		val originLat = requireNotNull(originLatitude)
		val originLon = requireNotNull(originLongitude)
		val northMeters = Math.toRadians(latitude - originLat) * EARTH_RADIUS_M
		val eastMeters = Math.toRadians(longitude - originLon) *
			EARTH_RADIUS_M * kotlin.math.cos(Math.toRadians(originLat))
		return Cell(
			row = floor(northMeters / CELL_SIZE_M).toLong(),
			column = floor(eastMeters / CELL_SIZE_M).toLong(),
		)
	}

	private fun toRelativeCell(cell: Cell): TerritoryRelativeCell? {
		if (cell.row !in MIN_VISUAL_OFFSET..MAX_VISUAL_OFFSET ||
			cell.column !in MIN_VISUAL_OFFSET..MAX_VISUAL_OFFSET
		) {
			return null
		}
		return TerritoryRelativeCell(cell.row.toInt(), cell.column.toInt())
	}

	private fun emitCompletionFeedback() {
		if (completionFeedbackEmitted) return
		completionFeedbackEmitted = true
		val outcome = when {
			personalBestBeforeRun == null -> MiniGameCompletionOutcome.FirstRun
			score > personalBestBeforeRun ->
				MiniGameCompletionOutcome.PersonalBest(score - personalBestBeforeRun)
			score == personalBestBeforeRun -> MiniGameCompletionOutcome.TiedBest
			else -> MiniGameCompletionOutcome.BelowBest(personalBestBeforeRun - score)
		}
		emitFeedback(MiniGameFeedbackCue.SessionCompleted(outcome))
	}

	private data class Cell(
		val row: Long,
		val column: Long,
	)

	companion object {
		private const val CELL_SIZE_M = 50.0
		private const val EARTH_RADIUS_M = 6_371_000.0
		private const val MIN_VISUAL_OFFSET = -3L
		private const val MAX_VISUAL_OFFSET = 3L
		private const val MAX_RECENT_TRAIL_CELLS = 12
		private const val MAX_ACCURACY_M = 30f
		private const val MAX_ON_FOOT_SPEED_MPS = 8f
		private const val DEBOUNCE_MS = 2000L
		private const val BASE_POINTS = 20
		private const val POINTS_PER_CELL = 10
		private const val POINTS_PER_CELL_DIMINISHED = 3.0
		private const val FULL_BONUS_CELLS = 15
	}
}
