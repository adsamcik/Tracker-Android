package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.model.AltitudeDatum
import kotlinx.coroutines.flow.Flow

/** Minimal ordered altitude projection for datum-safe aggregate calculations. */
data class OrderedAltitudeSampleRow(
	@ColumnInfo(name = "alt_m")
	val altitudeM: Float,
	@ColumnInfo(name = "alt_datum")
	val altitudeDatum: AltitudeDatum,
	@ColumnInfo(name = "clock_domain_id")
	val clockDomainId: String?,
)

/**
 * DAO for accessing location_sample table.
 */
@Dao
interface LocationSampleDao : BaseDao<LocationSample> {

	@Query("SELECT COUNT(*) FROM location_sample")
	suspend fun countAll(): Long

	/**
	 * Ordered identified-altitude evidence for gain/loss aggregation. Callers must reset the baseline
	 * at datum or clock-domain boundaries rather than treating this as an untyped MSL list.
	 */
	@Query(
		"SELECT alt_m, alt_datum, clock_domain_id FROM location_sample " +
			"WHERE alt_m IS NOT NULL ORDER BY time_ms ASC, id ASC",
	)
	suspend fun getAltitudeSamplesOrdered(): List<OrderedAltitudeSampleRow>
	
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

	@Query(
		"""
		SELECT * FROM location_sample
		WHERE time_ms >= :fromMs AND time_ms <= :toMs
		ORDER BY time_ms DESC, id DESC
		LIMIT 1
		"""
	)
	suspend fun getLatestBetween(fromMs: Long, toMs: Long): LocationSample?

	/**
	 * Get the next ordered chunk within a viewport. Handles antimeridian-crossing bounds
	 * (`eastE7 < westE7`) while retaining the same stable keyset cursor as the unbounded query.
	 */
	@Query(
		"""
		SELECT *
		FROM location_sample
		WHERE time_ms >= :fromMs
			AND time_ms <= :toMs
			AND lat_e7 IS NOT NULL
			AND lon_e7 IS NOT NULL
			AND lat_e7 <= :northE7
			AND lat_e7 >= :southE7
			AND (
				(:eastE7 >= :westE7 AND lon_e7 BETWEEN :westE7 AND :eastE7)
				OR (:eastE7 < :westE7 AND (lon_e7 >= :westE7 OR lon_e7 <= :eastE7))
			)
			AND (
				:afterTimeMs IS NULL
				OR time_ms > :afterTimeMs
				OR (time_ms = :afterTimeMs AND id > COALESCE(:afterId, 0))
			)
		ORDER BY time_ms ASC, id ASC
		LIMIT :limit
		"""
	)
	suspend fun getChunkBetweenOrderedInBounds(
		fromMs: Long,
		toMs: Long,
		northE7: Int,
		eastE7: Int,
		southE7: Int,
		westE7: Int,
		afterTimeMs: Long?,
		afterId: Long?,
		limit: Int,
	): List<LocationSample>

	@Query(
		"""
		SELECT * FROM location_sample
		WHERE time_ms >= :fromMs
			AND time_ms <= :toMs
			AND lat_e7 IS NOT NULL
			AND lon_e7 IS NOT NULL
			AND lat_e7 <= :northE7
			AND lat_e7 >= :southE7
			AND (
				(:eastE7 >= :westE7 AND lon_e7 BETWEEN :westE7 AND :eastE7)
				OR (:eastE7 < :westE7 AND (lon_e7 >= :westE7 OR lon_e7 <= :eastE7))
			)
		ORDER BY time_ms DESC, id DESC
		LIMIT 1
		"""
	)
	suspend fun getLatestBetweenInBounds(
		fromMs: Long,
		toMs: Long,
		northE7: Int,
		eastE7: Int,
		southE7: Int,
		westE7: Int,
	): LocationSample?

	/**
	 * Observe a bounded window of location samples within a time range.
	 */
	@Query(
		"""
		SELECT *
		FROM location_sample
		WHERE time_ms >= :fromMs AND time_ms <= :toMs
		ORDER BY time_ms ASC, id ASC
		LIMIT :limit
		"""
	)
	fun getAllBetweenFlowLimited(fromMs: Long, toMs: Long, limit: Int): Flow<List<LocationSample>>

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
