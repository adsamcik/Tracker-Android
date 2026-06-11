package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.CellSample
import com.adsamcik.tracker.shared.base.database.data.CoordinateProvenance
import kotlinx.coroutines.flow.Flow

/**
 * DAO for accessing cell_sample table.
 */
@Dao
interface CellSampleDao : BaseDao<CellSample> {
	
	/**
	 * Get cell samples within time range as Flow.
	 */
	@Query("SELECT * FROM cell_sample WHERE time_ms >= :fromMs AND time_ms <= :toMs ORDER BY time_ms")
	fun getAllBetweenFlow(fromMs: Long, toMs: Long): Flow<List<CellSample>>

	/**
	 * Get samples without coordinates (for enrichment).
	 */
	@Query("SELECT * FROM cell_sample WHERE lat_e7 IS NULL OR lon_e7 IS NULL ORDER BY time_ms LIMIT :limit")
	suspend fun getSamplesWithoutCoordinates(limit: Int): List<CellSample>

	/**
	 * Update coordinates for a sample (enrichment).
	 */
	@Query("UPDATE cell_sample SET lat_e7 = :latE7, lon_e7 = :lonE7, provenance = :provenance WHERE id = :id")
	suspend fun updateCoordinates(id: Long, latE7: Int?, lonE7: Int?, provenance: CoordinateProvenance)

	/**
	 * Count samples without coordinates.
	 */
	@Query("SELECT COUNT(*) FROM cell_sample WHERE lat_e7 IS NULL OR lon_e7 IS NULL")
	suspend fun countWithoutCoordinates(): Int

	/**
	 * Count unique cell towers by MCC+MNC+cell_id.
	 */
	@Query("SELECT COUNT(*) FROM (SELECT 1 FROM cell_sample GROUP BY mcc, mnc, cell_id)")
	fun uniqueCount(): Long

	/**
	 * Count unique cell towers by MCC+MNC+cell_id in a time range.
	 */
	@Query(
		"""
		SELECT COUNT(*) FROM (
			SELECT 1
			FROM cell_sample
			WHERE time_ms >= :fromMs AND time_ms <= :toMs
			GROUP BY mcc, mnc, cell_id
		)
		"""
	)
	fun uniqueCount(fromMs: Long, toMs: Long): Long

	/**
	 * Delete all cell samples.
	 */
	@Query("DELETE FROM cell_sample")
	fun deleteAll()

	/**
	 * Delete samples older than given timestamp.
	 */
	@Query("DELETE FROM cell_sample WHERE time_ms < :beforeMs")
	suspend fun deleteOlderThan(beforeMs: Long): Int
}
