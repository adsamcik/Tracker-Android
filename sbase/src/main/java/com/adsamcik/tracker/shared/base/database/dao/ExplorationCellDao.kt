package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.ExplorationCellEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ExplorationCellDao {
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insert(cell: ExplorationCellEntity): Long

	@Query("SELECT * FROM exploration_cell WHERE cell_token = :token")
	suspend fun getByToken(token: String): ExplorationCellEntity?

	@Query("SELECT cell_token FROM exploration_cell WHERE level = :level")
	suspend fun getAllTokensAtLevel(level: Int): List<String>

	@Query("SELECT COUNT(*) FROM exploration_cell WHERE level = :level")
	suspend fun countAtLevel(level: Int): Int

	/**
	 * Count cells at a given level, returning Long for achievement metrics.
	 */
	@Query("SELECT COUNT(*) FROM exploration_cell WHERE level = :level")
	suspend fun countAtLevelLong(level: Int): Long

	@Query("SELECT COUNT(*) FROM exploration_cell WHERE level = :level")
	fun countAtLevelFlow(level: Int): Flow<Int>

	@Query("SELECT COUNT(*) FROM exploration_cell WHERE first_discovered_at >= :sinceMs AND level = :level")
	suspend fun countDiscoveredSince(sinceMs: Long, level: Int): Int

	@Query("""
		UPDATE exploration_cell
		SET quality = :quality,
			last_visited_at = :lastVisitedAt,
			visit_count = visit_count + 1,
			season_bitmask = season_bitmask | :seasonBit
		WHERE cell_token = :token
	""")
	suspend fun updateVisit(token: String, quality: Int, lastVisitedAt: Long, seasonBit: Int)

	/**
	 * Returns the most recently discovered cells at the given level, ordered newest first.
	 * Used for the exploration UI to show recent discoveries.
	 */
	@Query("SELECT * FROM exploration_cell WHERE level = :level ORDER BY first_discovered_at DESC LIMIT :limit")
	suspend fun getRecentAtLevel(level: Int, limit: Int): List<ExplorationCellEntity>

	@Query("SELECT * FROM exploration_cell WHERE level = :level ORDER BY first_discovered_at DESC LIMIT :limit")
	fun getRecentAtLevelFlow(level: Int, limit: Int): Flow<List<ExplorationCellEntity>>

	/**
	 * Returns all distinct season_bitmask values across cells at the given level.
	 * Caller should bitwise-OR these together to determine which seasons have been explored
	 * (spring=1, summer=2, autumn=4, winter=8).
	 */
	@Query("SELECT DISTINCT season_bitmask FROM exploration_cell WHERE level = :level")
	suspend fun getDistinctSeasonBitmasks(level: Int): List<Int>

	@Query("SELECT DISTINCT season_bitmask FROM exploration_cell WHERE level = :level")
	fun getDistinctSeasonBitmasksFlow(level: Int): Flow<List<Int>>

	@Query("DELETE FROM exploration_cell")
	fun deleteAll()
    @Query("DELETE FROM exploration_cell WHERE first_discovered_at < :beforeMs")
    suspend fun deleteOlderThan(beforeMs: Long): Int
}
