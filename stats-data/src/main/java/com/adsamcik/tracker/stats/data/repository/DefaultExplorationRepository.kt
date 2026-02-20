package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.dao.ExplorationCellDao
import com.adsamcik.tracker.stats.api.repository.ExplorationRepository
import com.adsamcik.tracker.stats.api.repository.ExplorationStats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class DefaultExplorationRepository @Inject constructor(
	private val explorationCellDao: ExplorationCellDao,
) : ExplorationRepository {

	override fun observeCellCount(level: Int): Flow<Int> {
		return explorationCellDao.countAtLevelFlow(level)
	}

	override fun observeStats(): Flow<ExplorationStats> {
		return explorationCellDao.countAtLevelFlow(14).map { count ->
			ExplorationStats(
				totalCells = count,
				recentDiscoveries = 0,
			)
		}
	}
}
