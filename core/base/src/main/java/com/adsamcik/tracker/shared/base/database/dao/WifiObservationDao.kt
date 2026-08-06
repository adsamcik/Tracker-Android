package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.CoordinateProvenance
import com.adsamcik.tracker.shared.base.database.data.WifiObservation
import com.adsamcik.tracker.shared.base.database.data.WifiObservationBrowseRow
import com.adsamcik.tracker.shared.base.database.data.WifiObservationScanSummary
import kotlinx.coroutines.flow.Flow

/**
 * DAO for accessing wifi_observation table.
 */
@Dao
interface WifiObservationDao : BaseDao<WifiObservation> {
	
	/**
	 * Get Wi-Fi observations within time range as Flow.
	 */
	@Query("SELECT * FROM wifi_observation WHERE time_ms >= :fromMs AND time_ms <= :toMs ORDER BY time_ms")
	fun getAllBetweenFlow(fromMs: Long, toMs: Long): Flow<List<WifiObservation>>

	@Query(
		"""
		SELECT * FROM wifi_observation
		WHERE time_ms >= :fromMs AND time_ms <= :toMs
			AND (
				:afterTimeMs IS NULL
				OR time_ms > :afterTimeMs
				OR (time_ms = :afterTimeMs AND id > COALESCE(:afterId, 0))
			)
		ORDER BY time_ms ASC, id ASC
		LIMIT :limit
		"""
	)
	suspend fun getChunkBetweenOrdered(
		fromMs: Long,
		toMs: Long,
		afterTimeMs: Long?,
		afterId: Long?,
		limit: Int,
	): List<WifiObservation>

	/**
	 * Get observations without coordinates (for enrichment).
	 */
	@Query("SELECT * FROM wifi_observation WHERE lat_e7 IS NULL OR lon_e7 IS NULL ORDER BY time_ms LIMIT :limit")
	suspend fun getObservationsWithoutCoordinates(limit: Int): List<WifiObservation>

	/**
	 * Update coordinates for an observation (enrichment).
	 */
	@Query("UPDATE wifi_observation SET lat_e7 = :latE7, lon_e7 = :lonE7, provenance = :provenance WHERE id = :id")
	suspend fun updateCoordinates(id: Long, latE7: Int?, lonE7: Int?, provenance: CoordinateProvenance)

	/**
	 * Get observations for a specific BSSID within time range.
	 */
	@Query("SELECT * FROM wifi_observation WHERE bssid = :bssid AND time_ms >= :fromMs AND time_ms <= :toMs ORDER BY time_ms")
	suspend fun getForBssid(bssid: String, fromMs: Long, toMs: Long): List<WifiObservation>

	@Query(
		"""
		SELECT
			bssid,
			ssid,
			capabilities,
			frequency,
			MIN(time_ms) AS first_seen_ms,
			MAX(time_ms) AS last_seen_ms
		FROM wifi_observation
		WHERE (:bssid IS NULL OR bssid LIKE '%' || :bssid || '%' ESCAPE '\')
			AND (:ssid IS NULL OR ssid LIKE '%' || :ssid || '%' ESCAPE '\')
			AND (:capabilities IS NULL OR capabilities LIKE '%' || :capabilities || '%' ESCAPE '\')
			AND (:frequencyPrefix IS NULL OR CAST(frequency AS TEXT) LIKE :frequencyPrefix || '%' ESCAPE '\')
		GROUP BY bssid, ssid, capabilities, frequency
		ORDER BY bssid, ssid, frequency
		LIMIT :limit
		"""
	)
	suspend fun getBrowseItems(
		bssid: String?,
		ssid: String?,
		capabilities: String?,
		frequencyPrefix: String?,
		limit: Int,
	): List<WifiObservationBrowseRow>

	/**
	 * Count observations without coordinates.
	 */
	@Query("SELECT COUNT(*) FROM wifi_observation WHERE lat_e7 IS NULL OR lon_e7 IS NULL")
	suspend fun countWithoutCoordinates(): Int

	/**
	 * Count distinct Wi-Fi networks (BSSID) seen in the database.
	 */
	@Query("SELECT COUNT(DISTINCT bssid) FROM wifi_observation")
	fun countDistinctBssid(): Long

	/**
	 * Count distinct Wi-Fi networks (BSSID) seen in the given time range.
	 */
	@Query("SELECT COUNT(DISTINCT bssid) FROM wifi_observation WHERE time_ms >= :fromMs AND time_ms <= :toMs")
	fun countDistinctBssid(fromMs: Long, toMs: Long): Long

	@Query(
		"""
		SELECT COUNT(*) AS total_observations,
			COUNT(DISTINCT CASE
				WHEN source_signal_id IS NULL
					OR source_signal_id LIKE 'legacy:wifi_observation:%'
					THEN 'legacy-time:' || time_ms
				ELSE source_signal_id
			END) AS distinct_scan_times
		FROM wifi_observation
		"""
	)
	suspend fun getScanSummary(): WifiObservationScanSummary

	/**
	 * Delete all Wi-Fi observations.
	 */
	@Query("DELETE FROM wifi_observation")
	fun deleteAll()

	/**
	 * Delete observations older than given timestamp.
	 */
	@Query("DELETE FROM wifi_observation WHERE time_ms < :beforeMs")
	suspend fun deleteOlderThan(beforeMs: Long): Int
}
