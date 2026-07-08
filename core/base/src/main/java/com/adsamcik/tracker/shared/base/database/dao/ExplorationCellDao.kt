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

	@Query("SELECT COUNT(*) FROM exploration_cell WHERE first_discovered_at BETWEEN :fromMs AND :toMs AND level = :level")
	suspend fun countDiscoveredBetween(fromMs: Long, toMs: Long, level: Int): Long

	@Query("""
		UPDATE exploration_cell
		SET quality = :quality,
			last_visited_at = :lastVisitedAt,
			visit_count = visit_count + 1,
			season_bitmask = season_bitmask | :seasonBit
		WHERE cell_token = :token
	""")
	suspend fun updateVisit(token: String, quality: Int, lastVisitedAt: Long, seasonBit: Int)

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

	/** Count cells at [level] visited across all four seasons (season_bitmask == 15). */
	@Query("SELECT COUNT(*) FROM exploration_cell WHERE level = :level AND season_bitmask = 15")
	suspend fun countAllSeasonsCells(level: Int): Long

	/** Highest visit_count of any single cell at [level]. */
	@Query("SELECT COALESCE(MAX(visit_count), 0) FROM exploration_cell WHERE level = :level")
	suspend fun maxVisitCount(level: Int): Long

	/** Count cells at [level] with the given discovery [quality] tier (0..4). */
	@Query("SELECT COUNT(*) FROM exploration_cell WHERE level = :level AND quality = :quality")
	suspend fun countByQuality(level: Int, quality: Int): Long

	/** Geographic bounds (E7-encoded) of discovered cells at [level]; null fields when empty. */
	@Query(
		"""
		SELECT MIN(center_lat_e7) AS minLatE7, MAX(center_lat_e7) AS maxLatE7,
			MIN(center_lon_e7) AS minLonE7, MAX(center_lon_e7) AS maxLonE7
		FROM exploration_cell WHERE level = :level
		"""
	)
	suspend fun getCellBounds(level: Int): CellBounds?

	/** Maximum number of cells first discovered on a single LOCAL calendar day at [level]. */
	@Query(
		"""
		SELECT COALESCE(MAX(c), 0) FROM (
			SELECT COUNT(*) AS c FROM exploration_cell
			WHERE level = :level
			GROUP BY strftime('%Y-%m-%d', first_discovered_at / 1000, 'unixepoch', 'localtime')
		)
		"""
	)
	suspend fun maxCellsDiscoveredInDay(level: Int): Long

	/** Largest gap in whole days between a cell's first discovery and its last visit at [level]. */
	@Query("SELECT COALESCE(MAX((last_visited_at - first_discovered_at) / 86400000), 0) FROM exploration_cell WHERE level = :level")
	suspend fun maxRevisitGapDays(level: Int): Long

	/** E7-encoded centers of every discovered cell at [level] (for country lookup). */
	@Query("SELECT center_lat_e7 AS latE7, center_lon_e7 AS lonE7 FROM exploration_cell WHERE level = :level")
	suspend fun getCellCenters(level: Int): List<CellCenter>
}

/** E7-encoded center coordinate of a discovered cell. */
data class CellCenter(
	val latE7: Int,
	val lonE7: Int,
)

/**
 * Geographic bounding box (E7-encoded lat/lon) of discovered cells.
 * Fields are null when no cells exist at the queried level.
 */
data class CellBounds(
	val minLatE7: Int?,
	val maxLatE7: Int?,
	val minLonE7: Int?,
	val maxLonE7: Int?,
)
