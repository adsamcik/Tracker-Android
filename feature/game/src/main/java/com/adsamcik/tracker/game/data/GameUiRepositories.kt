package com.adsamcik.tracker.game.data

import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.dao.AchievementProgressDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationCellDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationStreakDao
import com.adsamcik.tracker.shared.base.database.dao.MiniGameScoreDao
import com.adsamcik.tracker.stats.api.metric.MetricKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

data class ExplorationProgress(
	val totalCells: Int,
	val dailyStreak: Int,
	val bestStreak: Int,
	val seasonsBitmask: Int,
	val recentDiscoveries: List<ExplorationDiscovery>,
)

data class ExplorationDiscovery(
	val token: String,
	val quality: Int,
	val discoveredAt: Long,
)

data class AchievementProgress(
	val metric: MetricKey,
	val lastTierIndex: Int,
	val lastValue: Double,
	val updatedAt: Long,
)

/**
 * Entity-free read port for exploration and achievement presentation.
 */
interface ExplorationProgressRepository {
	val exploration: Flow<ExplorationProgress>
	val achievements: Flow<List<AchievementProgress>>
}

@Singleton
internal class RoomExplorationProgressRepository @Inject constructor(
	explorationCellDao: ExplorationCellDao,
	explorationStreakDao: ExplorationStreakDao,
	achievementProgressDao: AchievementProgressDao,
	dispatchers: DispatchersProvider,
) : ExplorationProgressRepository {
	override val exploration: Flow<ExplorationProgress> = combine(
		explorationCellDao.countAtLevelFlow(EXPLORATION_LEVEL),
		explorationStreakDao.getByTypeFlow(STREAK_TYPE_DAILY),
		explorationCellDao.getDistinctSeasonBitmasksFlow(EXPLORATION_LEVEL),
		explorationCellDao.getRecentAtLevelFlow(EXPLORATION_LEVEL, RECENT_LIMIT),
	) { totalCells, streak, seasonBitmasks, recentEntities ->
		ExplorationProgress(
			totalCells = totalCells,
			dailyStreak = streak?.currentCount ?: 0,
			bestStreak = streak?.bestCount ?: 0,
			seasonsBitmask = seasonBitmasks.fold(0) { accumulated, mask ->
				accumulated or mask
			},
			recentDiscoveries = recentEntities.map { entity ->
				ExplorationDiscovery(
					token = entity.cellToken,
					quality = entity.quality,
					discoveredAt = entity.firstDiscoveredAt,
				)
			},
		)
	}.flowOn(dispatchers.io)

	override val achievements: Flow<List<AchievementProgress>> =
		achievementProgressDao.getAllFlow()
			.map { rows ->
				rows.mapNotNull { row ->
					MetricKey.fromStorageKey(row.metricKey)?.let { metric ->
						AchievementProgress(
							metric = metric,
							lastTierIndex = row.lastTierIndex,
							lastValue = row.lastValue,
							updatedAt = row.updatedAt,
						)
					}
				}
			}
			.flowOn(dispatchers.io)

	private companion object {
		const val EXPLORATION_LEVEL = 14
		const val RECENT_LIMIT = 5
		const val STREAK_TYPE_DAILY = "DAILY_DISCOVERY"
	}
}

data class MiniGameScore(
	val gameId: String,
	val score: Double,
	val xpAwarded: Int,
	val playedAt: Long,
)

/**
 * Read port for score presentation. Score writes remain owned by session persistence.
 */
interface MiniGameScoreRepository {
	fun observePersonalBest(gameId: String): Flow<Double?>
	fun observeRecent(limit: Int): Flow<List<MiniGameScore>>
}

@Singleton
internal class RoomMiniGameScoreRepository @Inject constructor(
	private val scoreDao: MiniGameScoreDao,
	private val dispatchers: DispatchersProvider,
) : MiniGameScoreRepository {
	override fun observePersonalBest(gameId: String): Flow<Double?> =
		scoreDao.getScoresByGame(gameId)
			.map { rows -> rows.maxOfOrNull { it.score } }
			.flowOn(dispatchers.io)

	override fun observeRecent(limit: Int): Flow<List<MiniGameScore>> =
		scoreDao.getRecent(limit)
			.map { rows -> rows.map { it.toFeatureModel() } }
			.flowOn(dispatchers.io)

	private fun com.adsamcik.tracker.shared.base.database.data.MiniGameScoreEntity.toFeatureModel() =
		MiniGameScore(
			gameId = gameId,
			score = score,
			xpAwarded = xpAwarded,
			playedAt = playedAt,
		)
}
