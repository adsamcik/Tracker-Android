package com.adsamcik.tracker.game.ui.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.leaderboard.GhostLeaderboardProvider
import com.adsamcik.tracker.game.leaderboard.LeaderboardMetric
import com.adsamcik.tracker.game.leaderboard.LeaderboardState
import com.adsamcik.tracker.game.minigame.MiniGameRegistry
import com.adsamcik.tracker.game.minigame.OutrunConfiguration
import com.adsamcik.tracker.game.minigame.TerritoryConfiguration
import com.adsamcik.tracker.game.minigame.ZenWalkConfiguration
import com.adsamcik.tracker.game.minigame.FuseRunConfiguration
import com.adsamcik.tracker.game.minigame.SwitchbackConfiguration
import com.adsamcik.tracker.game.repository.GameRepository
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.dao.MiniGameScoreDao
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Consolidated state for the Game hub, ordered play-first.
 *
 * @param playerLevel current level, used to gate mini-game unlocks.
 * @param games distinct play cards (Outrun/Territory/Zen), each with its own
 *   identity, unlock state and personal best in the game's own unit.
 * @param pointsToday points earned today (Progress section).
 * @param steps daily/weekly step goals (Goals section).
 * @param leaderboard local, self-referential "This Week" ghost leaderboard.
 * @param activeSession summary of an in-flight foreground game session, or
 *   `null` when none is running. Populated once the session-service workstream
 *   provides a [com.adsamcik.tracker.game.session.GameSessionController]
 *   binding; the hub already reserves the top slot for it.
 */
internal data class GameHubState(
	val playerLevel: Int,
	val games: List<MiniGamePlayCardUi>,
	val pointsToday: Int,
	val steps: StepsSummaryUi?,
	val leaderboard: LeaderboardUiState,
	val activeSession: ActiveGameSessionUi? = null,
)

/** Forward-compatible summary of an active foreground game session. */
internal data class ActiveGameSessionUi(
	val gameNameRes: Int,
	val isPaused: Boolean,
)

internal sealed interface LeaderboardUiState {
	data object Loading : LeaderboardUiState
	data class Ready(val state: LeaderboardState) : LeaderboardUiState
	data object Error : LeaderboardUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
internal class GameViewModel @Inject constructor(
	private val gameRepository: GameRepository,
	private val miniGameRegistry: MiniGameRegistry,
	private val scoreDao: MiniGameScoreDao,
	private val leaderboardProvider: GhostLeaderboardProvider,
	private val dispatchers: DispatchersProvider,
) : ViewModel() {

	private val selectedMetric = MutableStateFlow(DEFAULT_METRIC)
	private val leaderboardRetry = MutableStateFlow(0)

	/** Reactive per-game personal bests (max persisted score), on the io dispatcher. */
	private val personalBests: Flow<Map<String, Double>> = run {
		val games = miniGameRegistry.allSorted()
		if (games.isEmpty()) {
			flowOf(emptyMap())
		} else {
			combine(
				games.map { game ->
					scoreDao.getScoresByGame(game.id)
						.map { rows -> game.id to rows.maxOfOrNull { it.score } }
				},
			) { pairs ->
				pairs.mapNotNull { (id, best) -> best?.let { id to it } }.toMap()
			}.flowOn(dispatchers.io)
		}
	}

	private val leaderboard: Flow<LeaderboardUiState> =
		combine(selectedMetric, leaderboardRetry) { metric, _ -> metric }
			.flatMapLatest { metric ->
				flow {
					emit(LeaderboardUiState.Loading)
					// GhostLeaderboardProvider is entirely local (DailySummary
					// aggregation) and performs its own DAO work on io — no network.
					emit(
						runCatching { leaderboardProvider.getLeaderboard(metric) }
							.fold(
								onSuccess = { LeaderboardUiState.Ready(it) },
								onFailure = { LeaderboardUiState.Error },
							),
					)
				}
			}

	val hubState: StateFlow<GameHubState?> = combine(
		gameRepository.getPointsToday(),
		gameRepository.getStepsSummary(),
		gameRepository.getPlayerProfile(),
		personalBests,
		leaderboard,
	) { points, steps, profile, bests, leaderboardState ->
		val level = profile?.level ?: DEFAULT_LEVEL
		GameHubState(
			playerLevel = level,
			games = buildPlayCards(level, bests),
			pointsToday = points,
			steps = steps?.let {
				StepsSummaryUi(it.stepsToday, it.stepsWeek, it.goalDay, it.goalWeek)
			},
			leaderboard = leaderboardState,
			activeSession = null,
		)
	}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STATE_STOP_TIMEOUT_MS), null)

	fun selectLeaderboardMetric(metric: LeaderboardMetric) {
		selectedMetric.value = metric
	}

	fun retryLeaderboard() {
		leaderboardRetry.value += 1
	}

	private fun buildPlayCards(
		level: Int,
		bests: Map<String, Double>,
	): List<MiniGamePlayCardUi> = miniGameRegistry.allSorted().map { game ->
		val presentation = game.presentation
		MiniGamePlayCardUi(
			id = game.id,
			nameRes = game.nameRes,
			goalLineRes = goalLineRes(game.id, game.descriptionRes),
			icon = presentation.icon,
			accentRole = presentation.accentRole,
			shapeRole = presentation.shapeRole,
			scoreUnit = game.scoreUnit,
			unlockLevel = game.unlockLevel,
			isUnlocked = level >= game.unlockLevel,
			personalBest = bests[game.id],
		)
	}

	private fun goalLineRes(gameId: String, fallbackRes: Int): Int = when (gameId) {
		OutrunConfiguration.GAME_ID -> R.string.minigame_outrun_goal_line
		TerritoryConfiguration.GAME_ID -> R.string.minigame_territory_goal_line
		ZenWalkConfiguration.GAME_ID -> R.string.minigame_zenwalk_goal_line
		FuseRunConfiguration.GAME_ID -> R.string.minigame_fuserun_goal_line
		SwitchbackConfiguration.GAME_ID -> R.string.minigame_switchback_goal_line
		else -> fallbackRes
	}

	private companion object {
		const val STATE_STOP_TIMEOUT_MS = 5_000L
		const val DEFAULT_LEVEL = 1
		val DEFAULT_METRIC = LeaderboardMetric.DISTANCE
	}
}
