package com.adsamcik.tracker.stats.api.repository

import kotlinx.coroutines.flow.Flow

data class ExplorationStats(
	val totalCells: Int = 0,
	val recentDiscoveries: Int = 0,
)

interface ExplorationRepository {
	fun observeCellCount(level: Int): Flow<Int>
	fun observeStats(): Flow<ExplorationStats>
}
