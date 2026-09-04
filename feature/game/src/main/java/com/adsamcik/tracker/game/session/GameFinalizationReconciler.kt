package com.adsamcik.tracker.game.session

import com.adsamcik.tracker.game.repository.GameReward
import com.adsamcik.tracker.game.repository.GameRewardEnsureResult
import com.adsamcik.tracker.game.repository.miniGameRewardId
import com.adsamcik.tracker.shared.base.database.data.MiniGameScoreEntity
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import kotlinx.coroutines.CancellationException

internal data class GameFinalizationReconciliationSummary(
	val examined: Int,
	val repaired: Int,
	val alreadyComplete: Int,
	val failed: Int,
)

/**
 * Repairs bounded recent score commits whose cross-database reward write was
 * interrupted. Score rows are the durable commit record.
 */
internal class GameFinalizationReconciler(
	private val loadScores: suspend (Int) -> List<MiniGameScoreEntity>,
	private val trackingStartupGate: TrackingStartupGate,
	private val ensureRewardInsideAcceptedGeneration: suspend (
		reward: GameReward,
		acceptedGeneration: Long,
	) -> GameRewardEnsureResult,
	batchLimit: Int = DEFAULT_BATCH_LIMIT,
) {
	private val batchLimit = batchLimit.coerceIn(1, MAX_BATCH_LIMIT)

	suspend fun reconcile(): GameFinalizationReconciliationSummary {
		var repaired = 0
		var alreadyComplete = 0
		var failed = 0
		val expectedGeneration = trackingStartupGate.currentGeneration
		val rows = loadAcceptedRows(expectedGeneration) ?: return unavailableSummary()
		rows.forEach { row ->
			when (ensureAcceptedRow(row, expectedGeneration)) {
				GameRewardEnsureResult.Created -> repaired++
				GameRewardEnsureResult.AlreadyEnsured -> alreadyComplete++
				null,
				GameRewardEnsureResult.Unsupported,
				is GameRewardEnsureResult.Rejected,
				-> failed++
			}
		}
		return GameFinalizationReconciliationSummary(
			examined = rows.size,
			repaired = repaired,
			alreadyComplete = alreadyComplete,
			failed = failed,
		)
	}

	private suspend fun loadAcceptedRows(
		expectedGeneration: Long,
	): List<MiniGameScoreEntity>? = try {
		if (trackingStartupGate.reconcile() !is TrackingStartupResult.Ready) {
			null
		} else {
			trackingStartupGate.withReadyGenerationOperation(expectedGeneration) {
				loadScores(batchLimit)
			}
		}
	} catch (cancellation: CancellationException) {
		throw cancellation
	} catch (_: Throwable) {
		null
	}

	private suspend fun ensureAcceptedRow(
		row: MiniGameScoreEntity,
		expectedGeneration: Long,
	): GameRewardEnsureResult? = try {
		trackingStartupGate.withReadyGenerationOperation(expectedGeneration) {
			ensureRewardInsideAcceptedGeneration(
				GameReward(
					rewardId = miniGameRewardId(row.gameId, row.playedAt),
					gameId = row.gameId,
					points = row.xpAwarded,
					earnedAtMs = row.playedAt,
				),
				expectedGeneration,
			)
		}
	} catch (cancellation: CancellationException) {
		throw cancellation
	} catch (_: Throwable) {
		null
	}

	private fun unavailableSummary() = GameFinalizationReconciliationSummary(
		examined = 0,
		repaired = 0,
		alreadyComplete = 0,
		failed = 1,
	)

	private companion object {
		const val DEFAULT_BATCH_LIMIT: Int = 50
		const val MAX_BATCH_LIMIT: Int = 100
	}
}
