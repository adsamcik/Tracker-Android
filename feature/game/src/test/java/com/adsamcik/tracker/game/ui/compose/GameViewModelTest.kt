package com.adsamcik.tracker.game.ui.compose

import app.cash.turbine.test
import com.adsamcik.tracker.game.data.MiniGameScore
import com.adsamcik.tracker.game.data.MiniGameScoreRepository
import com.adsamcik.tracker.game.leaderboard.GhostLeaderboardProvider
import com.adsamcik.tracker.game.leaderboard.LeaderboardMetric
import com.adsamcik.tracker.game.minigame.MiniGame
import com.adsamcik.tracker.game.minigame.MiniGameRegistry
import com.adsamcik.tracker.game.minigame.MiniGameSession
import com.adsamcik.tracker.game.minigame.MiniGameState
import com.adsamcik.tracker.game.repository.GameReward
import com.adsamcik.tracker.game.repository.GameRepository
import com.adsamcik.tracker.game.repository.PlayerProfileUi
import com.adsamcik.tracker.game.repository.StepsSummaryData
import com.adsamcik.tracker.shared.base.di.QualifiedStepCount
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.shared.base.database.dao.DailySummaryDao
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Verifies the consolidated hub state: unlock gating, per-game personal bests
 * in the game's own unit, and the local ghost-leaderboard wiring (metric
 * selection re-computes the state; no network is involved).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GameViewModelTest {

	private val testDispatcher = UnconfinedTestDispatcher()
	private val dispatchers = TestDispatchersProvider(testDispatcher)
	private lateinit var dailySummaryDao: DailySummaryDao
	private lateinit var leaderboardProvider: GhostLeaderboardProvider

	@Before
	fun setUp() {
		kotlinx.coroutines.Dispatchers.setMain(testDispatcher)
		dailySummaryDao = mockk()
		// Entirely local aggregation over an empty history — no network.
		coEvery { dailySummaryDao.getBetween(any(), any()) } returns emptyList()
		coEvery { dailySummaryDao.getAllBefore(any()) } returns emptyList()
		leaderboardProvider = GhostLeaderboardProvider(dailySummaryDao, dispatchers)
	}

	@After
	fun tearDown() {
		kotlinx.coroutines.Dispatchers.resetMain()
	}

	@Test
	fun hubStateGatesUnlocksAndMapsPersonalBestUnits() = runTest(testDispatcher) {
		// Player at level 6: Outrun and Territory unlocked; later games locked.
		val repository = FakeGameRepository(level = 6)
		val scoreRepository = FakeScoreRepository(
			listOf(
				scoreRow("outrun", 40.0),
				scoreRow("outrun", 87.0),
				scoreRow("territory", 12.0),
			),
		)
		val vm = GameViewModel(
			gameRepository = repository,
			miniGameRegistry = registry(),
			scoreRepository = scoreRepository,
			leaderboardProvider = leaderboardProvider,
		)

		vm.hubState.test {
			val state = awaitNonNull()
			state.playerLevel shouldBe 6
			val outrun = state.games.first { it.id == "outrun" }
			outrun.isUnlocked shouldBe true
			// Personal best is the MAX persisted score, in meters.
			outrun.personalBest shouldBe 87.0
			miniGameScoreDisplay(outrun.scoreUnit, outrun.personalBest!!) shouldBe
				MiniGameScoreDisplay.Meters(87)

			val territory = state.games.first { it.id == "territory" }
			territory.isUnlocked shouldBe true
			miniGameScoreDisplay(territory.scoreUnit, territory.personalBest!!) shouldBe
				MiniGameScoreDisplay.Cells(12)

			val zen = state.games.first { it.id == "zenwalk" }
			zen.isUnlocked shouldBe false
			zen.personalBest shouldBe null
			state.games.first { it.id == "fuserun" }.isUnlocked shouldBe false
			state.games.first { it.id == "switchback" }.isUnlocked shouldBe false

			cancelAndIgnoreRemainingEvents()
		}
	}

	@Test
	fun leaderboardBecomesReadyAndMetricSelectionRecomputes() = runTest(testDispatcher) {
		val vm = GameViewModel(
			gameRepository = FakeGameRepository(level = 9),
			miniGameRegistry = registry(),
			scoreRepository = FakeScoreRepository(emptyList()),
			leaderboardProvider = leaderboardProvider,
		)

		vm.hubState.test {
			var state = awaitNonNull()
			// Default metric is DISTANCE; leaderboard resolves to a Ready state.
			while (state.leaderboard !is LeaderboardUiState.Ready) {
				state = awaitItem() ?: continue
			}
			state.leaderboard.state.metric shouldBe
				LeaderboardMetric.DISTANCE

			vm.selectLeaderboardMetric(LeaderboardMetric.STEPS)

			var recomputed = awaitItem()
			while (recomputed == null ||
				recomputed.leaderboard !is LeaderboardUiState.Ready ||
				recomputed.leaderboard.state.metric != LeaderboardMetric.STEPS
			) {
				recomputed = awaitItem()
			}
			recomputed.leaderboard.state.metric shouldBe
				LeaderboardMetric.STEPS

			cancelAndIgnoreRemainingEvents()
		}
	}

	private suspend fun app.cash.turbine.ReceiveTurbine<GameHubState?>.awaitNonNull(): GameHubState {
		var item = awaitItem()
		while (item == null) item = awaitItem()
		return item
	}

	private fun scoreRow(gameId: String, score: Double) = MiniGameScore(
		gameId = gameId,
		score = score,
		xpAwarded = 10,
		playedAt = 0L,
	)

	private fun registry(): MiniGameRegistry {
		val specs = listOf(
			"outrun" to 3,
			"territory" to 6,
			"zenwalk" to 9,
			"fuserun" to 9,
			"switchback" to 12,
		)
		val games = specs.map { (gameId, unlock) ->
			object : MiniGame {
				override val id: String = gameId
				override val nameRes: Int = android.R.string.ok
				override val descriptionRes: Int = android.R.string.ok
				override val unlockLevel: Int = unlock
				override fun createSession(): MiniGameSession = object : MiniGameSession() {
					override val state: MiniGameState = MiniGameState.IDLE
					override val score: Double = 0.0
					override val statusText: String = ""
					override fun onLocationUpdate(
						latitude: Double,
						longitude: Double,
						speedMps: Float,
						accuracyM: Float,
						timestampMs: Long,
					) = Unit
					override fun onSessionEnd() = Unit
					override fun calculatePoints(): Int = 0
				}
			}
		}.toSet()
		return MiniGameRegistry(games)
	}
}

private class FakeGameRepository(level: Int) : GameRepository {
	private val points = MutableStateFlow(120)
	private val steps = MutableStateFlow<StepsSummaryData?>(
		StepsSummaryData(
			stepsToday = QualifiedStepCount.Ready(100),
			stepsWeek = QualifiedStepCount.Ready(700),
			goalDay = 6000,
			goalWeek = 42000,
		),
	)
	private val profile = MutableStateFlow<PlayerProfileUi?>(
		PlayerProfileUi(level = level, totalXp = 0L, xpIntoCurrentLevel = 0L, xpForNextLevel = 100L),
	)

	override fun getPointsToday(): Flow<Int> = points.asStateFlow()
	override fun getStepsSummary(): StateFlow<StepsSummaryData?> = steps.asStateFlow()
	override fun getPlayerProfile(): Flow<PlayerProfileUi?> = profile.asStateFlow()
	override suspend fun creditMiniGameXp(gameId: String, xp: Int, earnedAtMs: Long) = Unit
}

private class FakeScoreRepository(initial: List<MiniGameScore>) : MiniGameScoreRepository {
	private val all = MutableStateFlow(initial)

	override fun observePersonalBest(gameId: String): Flow<Double?> =
		MutableStateFlow(
			all.value.filter { it.gameId == gameId }.maxOfOrNull { it.score },
		).asStateFlow()

	override fun observeRecent(limit: Int): Flow<List<MiniGameScore>> = all.asStateFlow()
}
