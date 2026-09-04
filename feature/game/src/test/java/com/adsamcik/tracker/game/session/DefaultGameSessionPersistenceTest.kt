package com.adsamcik.tracker.game.session

import com.adsamcik.tracker.game.minigame.MiniGameConfigurations
import com.adsamcik.tracker.game.repository.GameReward
import com.adsamcik.tracker.game.repository.GameRewardEnsureResult
import com.adsamcik.tracker.game.repository.GameRewardRejectionReason
import com.adsamcik.tracker.game.repository.miniGameRewardId
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.shared.base.database.dao.MiniGameScoreDao
import com.adsamcik.tracker.shared.base.database.data.MiniGameScoreEntity
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultGameSessionPersistenceTest {

	@Test
	fun `score commit precedes canonical idempotent reward ensure`() = runTest {
		val events = mutableListOf<String>()
		val scoreDao = RecordingScoreDao(events)
		val gate = SerializedTestTrackingStartupGate()
		val rewards = mutableListOf<GameReward>()
		val acceptedGenerations = mutableListOf<Long>()
		val persistence = DefaultGameSessionPersistence(
			scoreDao = scoreDao,
			dispatchers = TestDispatchersProvider(StandardTestDispatcher(testScheduler)),
			trackingStartupGate = gate,
			ensureRewardInsideAcceptedGeneration = { reward, generation ->
				events += "reward"
				rewards += reward
				acceptedGenerations += generation
				GameRewardEnsureResult.Created
			},
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
		rewards.single() shouldBe GameReward(
			rewardId = miniGameRewardId("outrun", 123L),
			gameId = "outrun",
			points = 41,
			earnedAtMs = 123L,
		)
		acceptedGenerations shouldBe listOf(1L)
		gate.operationGenerations shouldBe listOf(1L)
	}

	@Test
	fun `admitted score and reward finish before deletion then deletion wins`() = runTest {
		val insertStarted = CompletableDeferred<Unit>()
		val releaseInsert = CompletableDeferred<Unit>()
		val events = mutableListOf<String>()
		val scoreDao = RecordingScoreDao(
			events = events,
			beforeInsert = {
				insertStarted.complete(Unit)
				releaseInsert.await()
			},
		)
		val gate = SerializedTestTrackingStartupGate()
		val rewards = mutableListOf<GameReward>()
		val persistence = DefaultGameSessionPersistence(
			scoreDao = scoreDao,
			dispatchers = TestDispatchersProvider(StandardTestDispatcher(testScheduler)),
			trackingStartupGate = gate,
			ensureRewardInsideAcceptedGeneration = { reward, _ ->
				rewards += reward
				GameRewardEnsureResult.Created
			},
		)

		val commitOperation = async { persistence.commit(commit()) }
		insertStarted.await()
		val deletion = async {
			gate.closeDeleteReopen {
				scoreDao.deleteAll()
				rewards.clear()
			}
		}
		runCurrent()

		deletion.isCompleted shouldBe false
		releaseInsert.complete(Unit)
		commitOperation.await() shouldBe GameRewardEnsureResult.Created
		deletion.await()

		scoreDao.inserted shouldBe emptyList()
		rewards shouldBe emptyList()
	}

	@Test
	fun `generation replaced before admission rejects score and reward`() = runTest {
		val events = mutableListOf<String>()
		val scoreDao = RecordingScoreDao(events)
		val gate = SerializedTestTrackingStartupGate().apply {
			beforeNextOperation = { closeDeleteReopen { scoreDao.deleteAll() } }
		}
		val rewards = mutableListOf<GameReward>()
		val persistence = DefaultGameSessionPersistence(
			scoreDao = scoreDao,
			dispatchers = TestDispatchersProvider(StandardTestDispatcher(testScheduler)),
			trackingStartupGate = gate,
			ensureRewardInsideAcceptedGeneration = { reward, _ ->
				rewards += reward
				GameRewardEnsureResult.Created
			},
		)

		persistence.commit(commit()) shouldBe GameRewardEnsureResult.Rejected(
			GameRewardRejectionReason.PERSISTENCE_UNAVAILABLE,
		)

		scoreDao.inserted shouldBe emptyList()
		rewards shouldBe emptyList()
	}

	private fun commit() = GameSessionCommit(
		sessionId = GameSessionId("session-race"),
		configuration = MiniGameConfigurations.DEFAULT_OUTRUN,
		score = 12.5,
		points = 41,
		completedAtMs = 123L,
	)

	private class SerializedTestTrackingStartupGate : TrackingStartupGate {
		@Volatile private var ready = true
		@Volatile private var generation = 1L
		private val operationMutex = Mutex()
		var beforeNextOperation: (suspend () -> Unit)? = null
		val operationGenerations = mutableListOf<Long>()

		override val isReady: Boolean
			get() = ready

		override val currentGeneration: Long
			get() = generation

		override suspend fun reconcile(retryFailedStorage: Boolean): TrackingStartupResult =
			TrackingStartupResult.Ready(false, 0L)

		override suspend fun <T> withReadyGenerationOperation(
			expectedGeneration: Long,
			operation: suspend () -> T,
		): T? {
			operationGenerations += expectedGeneration
			beforeNextOperation?.also { beforeNextOperation = null }?.invoke()
			return operationMutex.withLock {
				if (isReadyGeneration(expectedGeneration)) operation() else null
			}
		}

		suspend fun closeDeleteReopen(delete: suspend () -> Unit) {
			ready = false
			generation += 1L
			operationMutex.withLock {
				delete()
				ready = true
			}
		}
	}

	private class RecordingScoreDao(
		private val events: MutableList<String>,
		private val beforeInsert: suspend () -> Unit = {},
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
			beforeInsert()
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
}
