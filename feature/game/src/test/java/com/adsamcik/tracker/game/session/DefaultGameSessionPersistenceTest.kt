package com.adsamcik.tracker.game.session

import com.adsamcik.tracker.game.minigame.MiniGameConfigurations
import com.adsamcik.tracker.game.repository.GameRepository
import com.adsamcik.tracker.game.repository.GameReward
import com.adsamcik.tracker.game.repository.GameRewardEnsureResult
import com.adsamcik.tracker.game.repository.PlayerProfileUi
import com.adsamcik.tracker.game.repository.StepsSummaryData
import com.adsamcik.tracker.game.repository.miniGameRewardId
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.shared.base.database.dao.MiniGameScoreDao
import com.adsamcik.tracker.shared.base.database.data.MiniGameScoreEntity
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DefaultGameSessionPersistenceTest {

	@Test
	fun `score commit precedes canonical idempotent reward ensure`() = runTest {
		val events = mutableListOf<String>()
		val scoreDao = RecordingScoreDao(events)
		val repository = RecordingRepository(events)
		val persistence = DefaultGameSessionPersistence(
			scoreDao = scoreDao,
			repository = repository,
			dispatchers = TestDispatchersProvider(StandardTestDispatcher(testScheduler)),
		)
		val commit = GameSessionCommit(
			sessionId = GameSessionId("session-1"),
			configuration = MiniGameConfigurations.DEFAULT_OUTRUN,
			score = 12.5,
			points = 41,
			completedAtMs = 123L,
		)

		persistence.commit(commit) shouldBe GameRewardEnsureResult.Created

		events shouldBe listOf("score", "reward")
		scoreDao.inserted.single() shouldBe MiniGameScoreEntity(
			gameId = "outrun",
			score = 12.5,
			xpAwarded = 41,
			playedAt = 123L,
		)
		repository.rewards.single() shouldBe GameReward(
			rewardId = miniGameRewardId("outrun", 123L),
			gameId = "outrun",
			points = 41,
			earnedAtMs = 123L,
		)
	}

	private class RecordingScoreDao(
		private val events: MutableList<String>,
	) : MiniGameScoreDao {
		val inserted = mutableListOf<MiniGameScoreEntity>()
		override fun getScoresByGame(gameId: String): Flow<List<MiniGameScoreEntity>> = flowOf(emptyList())
		override fun getHighScore(gameId: String): Double? = null
		override suspend fun getPersonalBest(gameId: String): Double? = null
		override fun getRecent(limit: Int): Flow<List<MiniGameScoreEntity>> = flowOf(emptyList())
		override suspend fun getRecentForReconciliation(limit: Int): List<MiniGameScoreEntity> = emptyList()
		override suspend fun countTotal(): Long = inserted.size.toLong()
		override fun deleteAll() = inserted.clear()
		override suspend fun insert(obj: MiniGameScoreEntity): Long {
			events += "score"
			inserted += obj
			return 1L
		}
		override suspend fun insert(obj: Collection<MiniGameScoreEntity>): List<Long> = obj.map { insert(it) }
		override suspend fun update(obj: MiniGameScoreEntity) = Unit
		override suspend fun update(obj: Collection<MiniGameScoreEntity>) = Unit
		override suspend fun delete(obj: MiniGameScoreEntity) = Unit
		override suspend fun delete(obj: Collection<MiniGameScoreEntity>) = Unit
	}

	private class RecordingRepository(
		private val events: MutableList<String>,
	) : GameRepository {
		val rewards = mutableListOf<GameReward>()
		override fun getPointsToday(): Flow<Int> = flowOf(0)
		override fun getStepsSummary(): StateFlow<StepsSummaryData?> = MutableStateFlow(null)
		override fun getPlayerProfile(): Flow<PlayerProfileUi?> = flowOf(null)
		override suspend fun ensureMiniGameReward(reward: GameReward): GameRewardEnsureResult {
			events += "reward"
			rewards += reward
			return GameRewardEnsureResult.Created
		}
		override suspend fun creditMiniGameXp(gameId: String, xp: Int, earnedAtMs: Long) = Unit
	}
}
