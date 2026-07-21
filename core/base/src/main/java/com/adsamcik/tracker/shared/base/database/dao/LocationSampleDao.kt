package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import kotlinx.coroutines.flow.Flow

/**
 * Compact projection of a [LocationSample] joined to its driving session segment.
 * Used by the vehicle speed compliance map layer; carries only the columns needed
 * for plotting and bucket classification.
 */
data class VehicleSpeedSampleRow(
	@ColumnInfo(name = "time_ms")
	val timeMs: Long,
	@ColumnInfo(name = "id")
	val id: Long,
	@ColumnInfo(name = "lat_e7")
	val latE7: Int,
	@ColumnInfo(name = "lon_e7")
	val lonE7: Int,
	@ColumnInfo(name = "speed_mps")
	val speedMps: Float,
	@ColumnInfo(name = "h_acc_m")
	val hAccM: Float?,
)

/**
 * DAO for accessing location_sample table.
 */
@Dao
interface LocationSampleDao : BaseDao<LocationSample> {

	@Query("SELECT COUNT(*) FROM location_sample")
	suspend fun countAll(): Long

	/** Ordered (by time) non-null fused MSL altitudes, for total-ascent computation. */
	@Query("SELECT alt_m FROM location_sample WHERE alt_m IS NOT NULL ORDER BY time_ms ASC, id ASC")
	suspend fun getAltitudesOrdered(): List<Float>
	
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
	 * Get the next ordered chunk of location samples within a time range that fall
	 * inside a driving session segment, filtered to samples with coordinates and
	 * a known, trustworthy speed. Used by the vehicle speed compliance map layer.
	 *
	 * A single low-accuracy or coarse fix can report an implausible instantaneous
	 * speed even though the numeric value itself is unremarkable, which would
	 * otherwise let one bad fix mis-colour a whole road-matched stretch as
	 * speeding/way-under. `quality` and `speed_accuracy_mps` gate this out the
	 * same way `LocationSample.hasTrustworthySpeed()` does for trip statistics
	 * (feature:statistics TripDetailPresenterViewModel).
	 *
	 * Uses (time_ms, id) as a stable cursor (identical to [getChunkBetweenOrdered])
	 * and INNER JOINs `session_segment` so the planner can use the segment time
	 * range and primary_activity indexes.
	 */
	@Query(
		"""
		SELECT ls.time_ms AS time_ms,
		       ls.id AS id,
		       ls.lat_e7 AS lat_e7,
		       ls.lon_e7 AS lon_e7,
		       ls.speed_mps AS speed_mps,
		       ls.h_acc_m AS h_acc_m
		FROM location_sample ls
		INNER JOIN session_segment ss
			ON ls.time_ms BETWEEN ss.start_time_ms AND ss.end_time_ms
		WHERE ss.primary_activity IN (:drivingActivities)
			AND ls.lat_e7 IS NOT NULL
			AND ls.lon_e7 IS NOT NULL
			AND ls.speed_mps IS NOT NULL
			AND ls.quality NOT IN ('LOW', 'COARSE')
			AND (ls.speed_accuracy_mps IS NULL OR ls.speed_accuracy_mps <= 3.0)
			AND ls.time_ms >= :fromMs
			AND ls.time_ms <= :toMs
			AND (
				:afterTimeMs IS NULL
				OR ls.time_ms > :afterTimeMs
				OR (ls.time_ms = :afterTimeMs AND ls.id > COALESCE(:afterId, 0))
			)
		ORDER BY ls.time_ms ASC, ls.id ASC
		LIMIT :limit
		"""
	)
	suspend fun getDrivingChunkBetweenOrdered(
		fromMs: Long,
		toMs: Long,
		drivingActivities: List<Int>,
		afterTimeMs: Long?,
		afterId: Long?,
		limit: Int,
	): List<VehicleSpeedSampleRow>

	@Query(
		"""
		SELECT ls.time_ms AS time_ms,
		       ls.id AS id,
		       ls.lat_e7 AS lat_e7,
		       ls.lon_e7 AS lon_e7,
		       ls.speed_mps AS speed_mps,
		       ls.h_acc_m AS h_acc_m
		FROM location_sample ls
		INNER JOIN session_segment ss
			ON ls.time_ms BETWEEN ss.start_time_ms AND ss.end_time_ms
		WHERE ss.primary_activity IN (:drivingActivities)
			AND ls.lat_e7 IS NOT NULL
			AND ls.lon_e7 IS NOT NULL
			AND ls.speed_mps IS NOT NULL
			AND ls.quality NOT IN ('LOW', 'COARSE')
			AND (ls.speed_accuracy_mps IS NULL OR ls.speed_accuracy_mps <= 3.0)
			AND ls.time_ms >= :fromMs
			AND ls.time_ms <= :toMs
		ORDER BY ls.time_ms DESC, ls.id DESC
		LIMIT 1
		"""
	)
	suspend fun getLatestDrivingBetween(
		fromMs: Long,
		toMs: Long,
		drivingActivities: List<Int>,
	): VehicleSpeedSampleRow?

	/**
	 * Get location samples within time range as Flow.
	 */
	@Deprecated(
		message = "Unbounded location flows can allocate very large lists. Use getAllBetweenFlowLimited for UI flows or getChunkBetweenOrdered for exports.",
	)
	@Query("SELECT * FROM location_sample WHERE time_ms >= :fromMs AND time_ms <= :toMs ORDER BY time_ms")
	fun getAllBetweenFlow(fromMs: Long, toMs: Long): Flow<List<LocationSample>>

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
