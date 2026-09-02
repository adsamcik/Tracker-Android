package com.adsamcik.tracker.game.repository

import com.adsamcik.tracker.shared.base.di.QualifiedStepCount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface GameRepository {
	fun getPointsToday(): Flow<Int>
	fun getStepsSummary(): StateFlow<StepsSummaryData?>
	fun getPlayerProfile(): Flow<PlayerProfileUi?>

	/**
	 * Ensure exactly one reward exists for [reward.rewardId].
	 *
	 * Implementations must atomically return [GameRewardEnsureResult.AlreadyEnsured]
	 * when the same reward id was persisted by an earlier finalize attempt.
	 * The compatibility default deliberately performs no write: existing
	 * repositories keep compiling without falsely claiming idempotency until the
	 * persistence workstream implements this contract.
	 */
	suspend fun ensureMiniGameReward(reward: GameReward): GameRewardEnsureResult =
		GameRewardEnsureResult.Unsupported

	/**
	 * Credit a mini-game's points into the "points earned today" ledger.
	 * Compatibility API for the current ViewModel-owned session flow; it does
	 * not provide the per-run idempotency guarantee of [ensureMiniGameReward].
	 *
	 * Writes a single [com.adsamcik.tracker.points.data.PointsAwarded] row
	 * via the same DAO as tracking-session points, so the value flows back
	 * into the dashboard immediately. Suspending; safe to call from the main
	 * thread.
	 *
	 * Privacy: no raw location samples are persisted — only the points
	 * amount, the timestamp and the mini-game id are recorded.
	 *
	 * @param gameId mini-game stable id (used as the source identifier for dedupe)
	 * @param xp points amount returned by [com.adsamcik.tracker.game.minigame.MiniGameSession.calculatePoints]
	 * @param earnedAtMs wall-clock millis when the session ended
	 */
	suspend fun creditMiniGameXp(gameId: String, xp: Int, earnedAtMs: Long)
}

data class GameReward(
	val rewardId: String,
	val gameId: String,
	val points: Int,
	val earnedAtMs: Long,
) {
	init {
		require(rewardId.isNotBlank()) { "Reward id cannot be blank" }
		require(gameId.isNotBlank()) { "Game id cannot be blank" }
		require(points >= 0) { "Reward points cannot be negative" }
		require(earnedAtMs >= 0L) { "Reward timestamp cannot be negative" }
	}
}

sealed interface GameRewardEnsureResult {
	data object Created : GameRewardEnsureResult
	data object AlreadyEnsured : GameRewardEnsureResult

	/**
	 * Temporary source-compatibility result returned until persistence adopts
	 * [GameRepository.ensureMiniGameReward].
	 */
	data object Unsupported : GameRewardEnsureResult

	data class Rejected(
		val reason: GameRewardRejectionReason,
	) : GameRewardEnsureResult
}

enum class GameRewardRejectionReason {
	PERSISTENCE_UNAVAILABLE,
	INVALID_REWARD,
}

data class StepsSummaryData(
	val stepsToday: QualifiedStepCount,
	val stepsWeek: QualifiedStepCount,
	val goalDay: Int,
	val goalWeek: Int,
)

data class PlayerProfileUi(
	val level: Int,
	val totalXp: Long,
	val xpIntoCurrentLevel: Long,
	val xpForNextLevel: Long,
)
