package com.adsamcik.tracker.feature.game.api.ranking

import kotlinx.coroutines.flow.Flow

enum class WeeklyRank {
	BRONZE,
	SILVER,
	GOLD,
	PLATINUM,
	DIAMOND,
}

data class WeeklyRankTier(
	val rank: WeeklyRank,
	val minimumPoints: Int,
)

data class WeeklyRankingSnapshot(
	val weekStartEpochDay: Long,
	val accumulatedPoints: Double,
	val tiers: List<WeeklyRankTier>,
	val currentRank: WeeklyRank,
	val nextTier: WeeklyRankTier?,
	val progressToNextRank: Float,
)

sealed interface WeeklyRankingResult {
	data class Success(val ranking: WeeklyRankingSnapshot) : WeeklyRankingResult

	data object DataUnavailable : WeeklyRankingResult
}

interface WeeklyRankingService {
	fun observeCurrentWeek(): Flow<WeeklyRankingResult>
}
