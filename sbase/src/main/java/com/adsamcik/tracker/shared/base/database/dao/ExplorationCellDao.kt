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

	@Query("DELETE FROM exploration_cell")
	fun deleteAll()
}
