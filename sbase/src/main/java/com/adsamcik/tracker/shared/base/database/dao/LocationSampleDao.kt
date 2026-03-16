package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import kotlinx.coroutines.flow.Flow

/**
 * DAO for accessing location_sample table.
 */
@Dao
interface LocationSampleDao : BaseDao<LocationSample> {
	
	/**
	 * Get all location samples within time range, ordered by time.
	 */
	@Query("SELECT * FROM location_sample WHERE time_ms >= :fromMs AND time_ms <= :toMs ORDER BY time_ms")
	suspend fun getAllBetween(fromMs: Long, toMs: Long): List<LocationSample>

	/**
	 * Get the next ordered chunk of location samples within a time range.
	 * Uses (time_ms, id) as a stable cursor to avoid duplicates or gaps.
	 */
	@Query(
		"""
		SELECT *
		FROM location_sample
		WHERE time_ms >= :fromMs
			AND time_ms <= :toMs
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
	): List<LocationSample>

	/**
	 * Get location samples within time range as Flow.
	 */
	@Query("SELECT * FROM location_sample WHERE time_ms >= :fromMs AND time_ms <= :toMs ORDER BY time_ms")
	fun getAllBetweenFlow(fromMs: Long, toMs: Long): Flow<List<LocationSample>>

	/**
	 * Get nearest location sample to a given timestamp (within tolerance).
	 */
	@Query("""
		SELECT * FROM location_sample 
		WHERE time_ms BETWEEN :timeMs - :toleranceMs AND :timeMs + :toleranceMs
		AND lat_e7 IS NOT NULL AND lon_e7 IS NOT NULL
		ORDER BY ABS(time_ms - :timeMs) ASC 
		LIMIT 1
	""")
	suspend fun getNearestWithCoordinates(timeMs: Long, toleranceMs: Long): LocationSample?

	/**
	 * Count samples in time range.
	 */
	@Query("SELECT COUNT(*) FROM location_sample WHERE time_ms >= :fromMs AND time_ms <= :toMs")
	suspend fun countBetween(fromMs: Long, toMs: Long): Int

	/**
	 * Delete all location samples.
	 */
	@Query("DELETE FROM location_sample")
	fun deleteAll()

	/**
	 * Delete samples older than given timestamp.
	 */
	@Query("DELETE FROM location_sample WHERE time_ms < :beforeMs")
	suspend fun deleteOlderThan(beforeMs: Long): Int

	/**
	 * Get count of samples without coordinates.
	 */
	@Query("SELECT COUNT(*) FROM location_sample WHERE lat_e7 IS NULL OR lon_e7 IS NULL")
	suspend fun countWithoutCoordinates(): Int
}
