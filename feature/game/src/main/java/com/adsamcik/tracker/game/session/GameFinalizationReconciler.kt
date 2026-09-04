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
		val rows = try {
			if (trackingStartupGate.reconcile() !is TrackingStartupResult.Ready) {
				return GameFinalizationReconciliationSummary(0, 0, 0, 1)
			}
			trackingStartupGate.withReadyGenerationOperation(expectedGeneration) {
				loadScores(batchLimit)
			} ?: return GameFinalizationReconciliationSummary(0, 0, 0, 1)
		} catch (cancellation: CancellationException) {
			throw cancellation
		} catch (_: Throwable) {
			return GameFinalizationReconciliationSummary(0, 0, 0, 1)
		}
		rows.forEach { row ->
			val result = try {
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
				} ?: run {
					failed++
					return@forEach
				}
			} catch (cancellation: CancellationException) {
				throw cancellation
			} catch (_: Throwable) {
				failed++
				return@forEach
			}
			when (result) {
				GameRewardEnsureResult.Created -> repaired++
				GameRewardEnsureResult.AlreadyEnsured -> alreadyComplete++
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

	private companion object {
		const val DEFAULT_BATCH_LIMIT: Int = 50
		const val MAX_BATCH_LIMIT: Int = 100
	}
}
