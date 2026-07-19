package com.adsamcik.tracker.game.session

import com.adsamcik.tracker.game.repository.GameRepository
import com.adsamcik.tracker.game.repository.GameReward
import com.adsamcik.tracker.game.repository.GameRewardEnsureResult
import com.adsamcik.tracker.game.repository.PlayerProfileUi
import com.adsamcik.tracker.game.repository.StepsSummaryData
import com.adsamcik.tracker.game.repository.miniGameRewardId
import com.adsamcik.tracker.shared.base.database.data.MiniGameScoreEntity
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class GameFinalizationReconcilerTest {

	@Test
	fun `bounded reconciliation rebuilds canonical rewards from durable score rows`() = runTest {
		val loadedLimits = mutableListOf<Int>()
		val rows = listOf(
			MiniGameScoreEntity(id = 1, gameId = "outrun", score = 10.0, xpAwarded = 40, playedAt = 100L),
			MiniGameScoreEntity(id = 2, gameId = "territory", score = 3.0, xpAwarded = 50, playedAt = 200L),
		)
		val repository = RecordingRepository()
		val reconciler = GameFinalizationReconciler(
			loadScores = { limit ->
				loadedLimits += limit
				rows.take(limit)
			},
			repository = repository,
			batchLimit = 1,
		)

		val summary = reconciler.reconcile()

		loadedLimits shouldBe listOf(1)
		repository.rewards.shouldHaveSize(1)
		repository.rewards.single() shouldBe GameReward(
			rewardId = miniGameRewardId("outrun", 100L),
			gameId = "outrun",
			points = 40,
			earnedAtMs = 100L,
		)
		summary.examined shouldBe 1
		summary.repaired shouldBe 1
	}

	private class RecordingRepository : GameRepository {
		val rewards = mutableListOf<GameReward>()
		override fun getPointsToday(): Flow<Int> = flowOf(0)
		override fun getStepsSummary(): StateFlow<StepsSummaryData?> = MutableStateFlow(null)
		override fun getPlayerProfile(): Flow<PlayerProfileUi?> = flowOf(null)
		override suspend fun ensureMiniGameReward(reward: GameReward): GameRewardEnsureResult {
			rewards += reward
			return GameRewardEnsureResult.Created
		}
		override suspend fun creditMiniGameXp(gameId: String, xp: Int, earnedAtMs: Long) = Unit
	}
}
