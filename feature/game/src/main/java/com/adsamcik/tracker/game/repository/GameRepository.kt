package com.adsamcik.tracker.game.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface GameRepository {
	fun getPointsToday(): Flow<Int>
	fun getStepsSummary(): StateFlow<StepsSummaryData?>
	fun getPlayerProfile(): Flow<PlayerProfileUi?>

	/**
	 * Credit a mini-game's points into the "points earned today" ledger.
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

data class StepsSummaryData(
	val stepsToday: Int,
	val stepsWeek: Int,
	val goalDay: Int,
	val goalWeek: Int,
)

data class PlayerProfileUi(
	val level: Int,
	val totalXp: Long,
	val xpIntoCurrentLevel: Long,
	val xpForNextLevel: Long,
)

