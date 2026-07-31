package com.adsamcik.tracker.game.ui.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.game.data.MiniGameScore
import com.adsamcik.tracker.game.data.MiniGameScoreRepository
import com.adsamcik.tracker.game.minigame.MiniGameRegistry
import com.adsamcik.tracker.game.minigame.MiniGameScoreUnit
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * One typed history row for a single past run.
 *
 * @param rank 1-based rank within the game (rank #1 is the personal best).
 * @param display the score in the game's own unit (meters / cells / duration),
 *   never a generic "pts" value.
 * @param xpAwarded XP earned by the run — a separate currency from the score.
 * @param playedAtMs wall-clock time the run finished.
 * @param isPersonalBest `true` for the top-ranked run in this game.
 */
internal data class MiniGameScoreRow(
	val rank: Int,
	val display: MiniGameScoreDisplay,
	val xpAwarded: Int,
	val playedAtMs: Long,
	val isPersonalBest: Boolean,
)

/**
 * One mini-game's score history grouped for display.
 *
 * @param gameId stable mini-game id (matches [com.adsamcik.tracker.game.minigame.MiniGame.id]).
 * @param nameRes display name string resource, or `null` if the game has been
 *   removed from the registry but rows still exist on disk.
 * @param scoreUnit the game's score unit, or `null` for an unknown game.
 * @param rows typed rows, sorted by score descending (personal best first).
 */
internal data class MiniGameScoreGroup(
	val gameId: String,
	val nameRes: Int?,
	val scoreUnit: MiniGameScoreUnit?,
	val rows: List<MiniGameScoreRow>,
)

/**
 * Backs the past-runs screen.
 *
 * Pulls persisted scores (capped at [HISTORY_LIMIT] for performance), groups
 * them by game id, orders each group
 * by score descending, and maps each row into its game's real unit so the UI
 * never shows a generic point score. Rank #1 in each group is flagged as the
 * personal best.
 *
 * Empty list is a valid state — UI surfaces a first-run empty-state card.
 */
@HiltViewModel
internal class MiniGameScoresViewModel @Inject constructor(
	private val scoreRepository: MiniGameScoreRepository,
	private val registry: MiniGameRegistry,
) : ViewModel() {

	val groups: StateFlow<List<MiniGameScoreGroup>?> = scoreRepository.observeRecent(HISTORY_LIMIT)
		.map { rows -> buildGroups(rows) }
		.stateIn(
			viewModelScope,
			SharingStarted.WhileSubscribed(STATE_STOP_TIMEOUT_MS),
			null,
		)

	private fun buildGroups(rows: List<MiniGameScore>): List<MiniGameScoreGroup> {
		if (rows.isEmpty()) return emptyList()
		val byGame = rows.groupBy { it.gameId }
		val knownOrder = registry.allSorted().mapIndexed { index, game -> game.id to index }.toMap()
		return byGame.entries
			.map { (gameId, items) ->
				val game = registry.findById(gameId)
				val scoreUnit = game?.scoreUnit
				val typedRows = items
					.sortedByDescending { it.score }
					.mapIndexed { index, entity ->
						MiniGameScoreRow(
							rank = index + 1,
							display = miniGameScoreDisplay(scoreUnit, entity.score),
							xpAwarded = entity.xpAwarded,
							playedAtMs = entity.playedAt,
							isPersonalBest = index == 0,
						)
					}
				MiniGameScoreGroup(
					gameId = gameId,
					nameRes = game?.nameRes,
					scoreUnit = scoreUnit,
					rows = typedRows,
				)
			}
			.sortedBy { knownOrder[it.gameId] ?: Int.MAX_VALUE }
	}

	private companion object {
		private const val HISTORY_LIMIT = 200
		private const val STATE_STOP_TIMEOUT_MS = 5_000L
	}
}
