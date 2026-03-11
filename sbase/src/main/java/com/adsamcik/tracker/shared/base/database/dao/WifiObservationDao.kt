package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.WifiObservation
import com.adsamcik.tracker.shared.base.database.data.CoordinateProvenance
import kotlinx.coroutines.flow.Flow

/**
 * DAO for accessing wifi_observation table.
 */
@Dao
interface WifiObservationDao : BaseDao<WifiObservation> {
	
	/**
	 * Get all Wi-Fi observations within time range, ordered by time.
	 */
	@Query("SELECT * FROM wifi_observation WHERE time_ms >= :fromMs AND time_ms <= :toMs ORDER BY time_ms")
	suspend fun getAllBetween(fromMs: Long, toMs: Long): List<WifiObservation>

	/**
	 * Get Wi-Fi observations within time range as Flow.
	 */
	@Query("SELECT * FROM wifi_observation WHERE time_ms >= :fromMs AND time_ms <= :toMs ORDER BY time_ms")
	fun getAllBetweenFlow(fromMs: Long, toMs: Long): Flow<List<WifiObservation>>

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
