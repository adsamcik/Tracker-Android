package com.adsamcik.tracker.game.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GameRepositoryContractTest {

	@Test
	fun `legacy repository implementations do not silently claim idempotent reward support`() = runTest {
		val repository = LegacyFakeGameRepository()

		val result = repository.ensureMiniGameReward(
			GameReward(
				rewardId = "session-123",
				gameId = "outrun",
				points = 40,
				earnedAtMs = 1_000L,
			),
		)

		assertEquals(GameRewardEnsureResult.Unsupported, result)
		assertEquals(0, repository.legacyCreditCalls)
	}

	private class LegacyFakeGameRepository : GameRepository {
		var legacyCreditCalls: Int = 0

		override fun getPointsToday(): Flow<Int> = flowOf(0)

		override fun getStepsSummary(): StateFlow<StepsSummaryData?> = MutableStateFlow(null)

		override fun getPlayerProfile(): Flow<PlayerProfileUi?> = flowOf(null)

		override suspend fun creditMiniGameXp(gameId: String, xp: Int, earnedAtMs: Long) {
			legacyCreditCalls++
		}
	}
}
