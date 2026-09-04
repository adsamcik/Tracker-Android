package com.adsamcik.tracker.game.session

import com.adsamcik.tracker.game.repository.GameReward
import com.adsamcik.tracker.game.repository.GameRewardEnsureResult
import com.adsamcik.tracker.game.repository.miniGameRewardId
import com.adsamcik.tracker.shared.base.database.data.MiniGameScoreEntity
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.base.startup.TrackingStartupStage
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Test

class GameFinalizationReconcilerTest {

	@Test
	fun `bounded reconciliation rebuilds canonical rewards from durable score rows`() = runTest {
		val loadedLimits = mutableListOf<Int>()
		val rows = listOf(
			MiniGameScoreEntity(id = 1, gameId = "outrun", score = 10.0, xpAwarded = 40, playedAt = 100L),
			MiniGameScoreEntity(id = 2, gameId = "territory", score = 3.0, xpAwarded = 50, playedAt = 200L),
		)
		val gate = SerializedTestTrackingStartupGate()
		val rewards = mutableListOf<GameReward>()
		val reconciler = GameFinalizationReconciler(
			loadScores = { limit ->
				loadedLimits += limit
				rows.take(limit)
			},
			trackingStartupGate = gate,
			ensureRewardInsideAcceptedGeneration = { reward, generation ->
				rewards += reward
				generation shouldBe 1L
				GameRewardEnsureResult.Created
			},
			batchLimit = 1,
		)

		val summary = reconciler.reconcile()

		loadedLimits shouldBe listOf(1)
		rewards.shouldHaveSize(1)
		rewards.single() shouldBe GameReward(
			rewardId = miniGameRewardId("outrun", 100L),
			gameId = "outrun",
			points = 40,
			earnedAtMs = 100L,
		)
		summary.examined shouldBe 1
		summary.repaired shouldBe 1
		gate.operationGenerations shouldBe listOf(1L, 1L)
	}

	@Test
	fun `row loaded before deletion cannot repair reward in replacement generation`() = runTest {
		val durableRows = mutableListOf(
			MiniGameScoreEntity(
				id = 1,
				gameId = "outrun",
				score = 10.0,
				xpAwarded = 40,
				playedAt = 100L,
			),
		)
		val gate = SerializedTestTrackingStartupGate().apply {
			afterNextOperation = {
				closeDeleteReopen { durableRows.clear() }
			}
		}
		val rewards = mutableListOf<GameReward>()
		val reconciler = GameFinalizationReconciler(
			loadScores = { _ -> durableRows.toList() },
			trackingStartupGate = gate,
			ensureRewardInsideAcceptedGeneration = { reward, _ ->
				rewards += reward
				GameRewardEnsureResult.Created
			},
		)

		val summary = reconciler.reconcile()

		durableRows shouldBe emptyList()
		rewards shouldBe emptyList()
		summary shouldBe GameFinalizationReconciliationSummary(
			examined = 1,
			repaired = 0,
			alreadyComplete = 0,
			failed = 1,
		)
		gate.operationGenerations shouldBe listOf(1L, 1L)
	}

	private class SerializedTestTrackingStartupGate : TrackingStartupGate {
		@Volatile private var ready = true
		@Volatile private var generation = 1L
		private val operationMutex = Mutex()
		var afterNextOperation: (suspend () -> Unit)? = null
		val operationGenerations = mutableListOf<Long>()

		override val isReady: Boolean
			get() = ready

		override val currentGeneration: Long
			get() = generation

		override suspend fun reconcile(retryFailedStorage: Boolean): TrackingStartupResult =
			if (ready) {
				TrackingStartupResult.Ready(false, 0L)
			} else {
				TrackingStartupResult.Blocked(
					TrackingStartupStage.STORAGE,
					"TEST_DELETION_CLOSED",
				)
			}

		override suspend fun <T> withReadyGenerationOperation(
			expectedGeneration: Long,
			operation: suspend () -> T,
		): T? {
			operationGenerations += expectedGeneration
			val result = operationMutex.withLock {
				if (isReadyGeneration(expectedGeneration)) operation() else null
			}
			afterNextOperation?.also { afterNextOperation = null }?.invoke()
			return result
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
}
