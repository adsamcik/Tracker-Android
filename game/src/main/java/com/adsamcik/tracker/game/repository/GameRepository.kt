package com.adsamcik.tracker.game.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface GameRepository {
	fun getPointsToday(): Flow<Int>
	fun getStepsSummary(): StateFlow<StepsSummaryData?>
	fun getPlayerProfile(): Flow<PlayerProfileUi?>
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
