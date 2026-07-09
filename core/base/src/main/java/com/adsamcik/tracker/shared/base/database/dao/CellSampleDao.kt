package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.CellSample
import com.adsamcik.tracker.shared.base.database.data.CellSampleTotals
import com.adsamcik.tracker.shared.base.database.data.CoordinateProvenance
import com.adsamcik.tracker.shared.base.database.data.NetworkTypeSignalRow
import com.adsamcik.tracker.shared.base.database.data.TopCellTowerRow
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
	 * Whole-table totals: sample count and distinct tower count for the signal report.
	 */
	@Query(
		"""
		SELECT COUNT(*) AS total_samples,
			COUNT(DISTINCT mcc || ':' || mnc || ':' || cell_id) AS distinct_towers
		FROM cell_sample
		"""
	)
	suspend fun getSignalReportTotals(): CellSampleTotals

	/**
	 * Per radio-technology aggregates for the signal report, ordered by sample count.
	 * The average excludes implausible ASU sentinels (e.g. GSM's "unknown" 99, or 255/MAX)
	 * by restricting to the 1..97 ASU range shared by every technology.
	 */
	@Query(
		"""
		SELECT network_type AS network_type,
			COUNT(*) AS sample_count,
			COALESCE(AVG(CASE WHEN signal_strength BETWEEN 1 AND 97 THEN signal_strength END), 0) AS avg_asu,
			COUNT(DISTINCT mcc || ':' || mnc || ':' || cell_id) AS distinct_cells
		FROM cell_sample
		GROUP BY network_type
		ORDER BY sample_count DESC
		"""
	)
	suspend fun getNetworkTypeSignalRows(): List<NetworkTypeSignalRow>

	/**
	 * The [limit] most-frequently-observed towers, keyed by (mcc, mnc, cell_id).
	 */
	@Query(
		"""
		SELECT cell_id AS cell_id, mcc, mnc, network_type AS network_type,
			COUNT(*) AS sample_count,
			COALESCE(AVG(CASE WHEN signal_strength BETWEEN 1 AND 97 THEN signal_strength END), 0) AS avg_asu
		FROM cell_sample
		GROUP BY mcc, mnc, cell_id
		ORDER BY sample_count DESC
		LIMIT :limit
		"""
	)
	suspend fun getTopCellTowers(limit: Int): List<TopCellTowerRow>

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
