package com.adsamcik.tracker.game.ui.compose

import app.cash.turbine.test
import com.adsamcik.tracker.game.data.MiniGameScore
import com.adsamcik.tracker.game.data.MiniGameScoreRepository
import com.adsamcik.tracker.game.minigame.MiniGame
import com.adsamcik.tracker.game.minigame.MiniGameRegistry
import com.adsamcik.tracker.game.minigame.MiniGameSession
import com.adsamcik.tracker.game.minigame.MiniGameState
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Tests for [MiniGameScoresViewModel] grouping + ordering behavior.
 *
 * Pure JVM test — no Android context required.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MiniGameScoresViewModelTest {

	private lateinit var scoreRepository: ScriptedScoreRepository
	private lateinit var dispatcher: TestDispatcher

	@Before
	fun setUp() {
		dispatcher = StandardTestDispatcher()
		Dispatchers.setMain(dispatcher)
		scoreRepository = ScriptedScoreRepository()
	}

	@After
	fun tearDown() {
		Dispatchers.resetMain()
	}

	@Test
	fun emptyDaoYieldsEmptyList() = runTest(dispatcher) {
		val vm = MiniGameScoresViewModel(scoreRepository, registryWith("outrun"))
		vm.groups.test {
			val first = awaitItem()
			if (first == null) {
				advanceUntilIdle()
				awaitItem() shouldBe emptyList()
			} else {
				first shouldBe emptyList()
			}
		}
	}

	@Test
	fun groupsRowsByGameIdAndOrdersByScoreDesc() = runTest(dispatcher) {
		scoreRepository.emit(
			listOf(
				row("outrun", 50.0, 1L),
				row("outrun", 120.0, 2L),
				row("territory", 30.0, 3L),
				row("outrun", 80.0, 4L),
			),
		)
		val vm = MiniGameScoresViewModel(scoreRepository, registryWith("outrun", "territory"))

		vm.groups.test {
			val groups = awaitNonNull(this)
			groups shouldHaveSize 2
			val outrun = groups.first { it.gameId == "outrun" }
			outrun.rows.map { it.display } shouldBe listOf(
				MiniGameScoreDisplay.Meters(120),
				MiniGameScoreDisplay.Meters(80),
				MiniGameScoreDisplay.Meters(50),
			)
			outrun.rows.map { it.isPersonalBest } shouldBe listOf(true, false, false)
			val territory = groups.first { it.gameId == "territory" }
			territory.rows shouldHaveSize 1
			territory.rows.single().display shouldBe MiniGameScoreDisplay.Cells(30)
		}
	}

	@Test
	fun unknownGameIdSurfacesWithNullNameRes() = runTest(dispatcher) {
		scoreRepository.emit(listOf(row("ghost-game", 10.0, 1L)))
		val vm = MiniGameScoresViewModel(scoreRepository, registryWith())

		vm.groups.test {
			val groups = awaitNonNull(this)
			groups shouldHaveSize 1
			groups.single().nameRes shouldBe null
			groups.single().gameId shouldBe "ghost-game"
			groups.single().scoreUnit shouldBe null
			groups.single().rows.single().display shouldBe MiniGameScoreDisplay.Raw(10L)
		}
	}

	private fun row(gameId: String, score: Double, time: Long) = MiniGameScore(
		gameId = gameId,
		score = score,
		xpAwarded = 10,
		playedAt = time,
	)

	private fun registryWith(vararg ids: String): MiniGameRegistry {
		val games = ids.map { id ->
			object : MiniGame {
				override val id: String = id
				override val nameRes: Int = android.R.string.ok
				override val descriptionRes: Int = android.R.string.ok
				override val unlockLevel: Int = 1
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

	/**
	 * Skips at most one `null` priming emission from `stateIn` and returns the
	 * first non-null group list. Keeps individual assertions free of priming
	 * noise without making the tests timing-sensitive.
	 */
	private suspend fun awaitNonNull(
		turbine: app.cash.turbine.ReceiveTurbine<List<MiniGameScoreGroup>?>,
	): List<MiniGameScoreGroup> {
		val first = turbine.awaitItem()
		return first ?: turbine.awaitItem()!!
	}
}

private class ScriptedScoreRepository : MiniGameScoreRepository {
	private val recent = MutableStateFlow<List<MiniGameScore>>(emptyList())

	fun emit(rows: List<MiniGameScore>) { recent.value = rows }

	override fun observePersonalBest(gameId: String): Flow<Double?> =
		MutableStateFlow(
			recent.value.filter { it.gameId == gameId }.maxOfOrNull { it.score },
		).asStateFlow()

	override fun observeRecent(limit: Int): Flow<List<MiniGameScore>> = recent.asStateFlow()
}
