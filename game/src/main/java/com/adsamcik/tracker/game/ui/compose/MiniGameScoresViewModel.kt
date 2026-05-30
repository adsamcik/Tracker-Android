package com.adsamcik.tracker.game.ui.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.game.minigame.MiniGameRegistry
import com.adsamcik.tracker.shared.base.database.dao.MiniGameScoreDao
import com.adsamcik.tracker.shared.base.database.data.MiniGameScoreEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * One mini-game's score history grouped for display.
 *
 * @param gameId stable mini-game id (matches [com.adsamcik.tracker.game.minigame.MiniGame.id]).
 * @param nameRes display name string resource, or `null` if the game has been
 *   removed from the registry but rows still exist on disk.
 * @param entries score rows, sorted by score descending (rank #1 first).
 */
data class MiniGameScoreGroup(
	val gameId: String,
	val nameRes: Int?,
	val entries: List<MiniGameScoreEntity>,
)

/**
 * Backs the past-runs screen.
 *
 * Pulls every persisted [MiniGameScoreEntity] (capped at [HISTORY_LIMIT] for
 * performance), groups them by [MiniGameScoreEntity.gameId], and orders each
 * group by score descending so the user always sees their personal best first.
 *
 * Empty list is a valid state — UI surfaces an empty-state card.
 */
@HiltViewModel
class MiniGameScoresViewModel @Inject constructor(
	private val scoreDao: MiniGameScoreDao,
	private val registry: MiniGameRegistry,
) : ViewModel() {

	val groups: StateFlow<List<MiniGameScoreGroup>?> = scoreDao.getRecent(HISTORY_LIMIT)
		.map { rows -> buildGroups(rows) }
		.stateIn(
			viewModelScope,
			SharingStarted.WhileSubscribed(STATE_STOP_TIMEOUT_MS),
			null,
		)

	private fun buildGroups(rows: List<MiniGameScoreEntity>): List<MiniGameScoreGroup> {
		if (rows.isEmpty()) return emptyList()
		val byGame = rows.groupBy { it.gameId }
		val knownOrder = registry.allSorted().mapIndexed { index, game -> game.id to index }.toMap()
		return byGame.entries
			.map { (gameId, items) ->
				MiniGameScoreGroup(
					gameId = gameId,
					nameRes = registry.findById(gameId)?.nameRes,
					entries = items.sortedByDescending { it.score },
				)
			}
			.sortedBy { knownOrder[it.gameId] ?: Int.MAX_VALUE }
	}

	private companion object {
		private const val HISTORY_LIMIT = 200
		private const val STATE_STOP_TIMEOUT_MS = 5_000L
	}
}
