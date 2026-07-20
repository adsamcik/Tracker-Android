package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.LocationObservation

data class LocationObservationRef(
	val id: Long,
	val fixTimeMs: Long,
)

@Dao
interface LocationObservationDao : BaseDao<LocationObservation> {
	@Query("SELECT MIN(fix_time_ms) FROM location_observation")
	suspend fun minFixTimeMs(): Long?
	@Query("SELECT COUNT(*) FROM location_observation")
	suspend fun countAll(): Long

	@Query(
		"""
		SELECT * FROM location_observation
		WHERE fix_time_ms >= :fromMs AND fix_time_ms < :toMs
		ORDER BY fix_time_ms ASC, id ASC
		""",
	)
	suspend fun getBetween(fromMs: Long, toMs: Long): List<LocationObservation>

	/**
	 * Provider fixes that are allowed to seed observation-supported presence.
	 *
	 * Migrated rows already came from the accepted-sample table. Live ingress rows must still match
	 * an accepted [location_sample] exactly: DELIVERED_VALID only records trigger-level validation,
	 * while a later pipeline stage may reject an outlier before it becomes an accepted sample.
	 */
	@Query(
		"""
		SELECT observation.*
		FROM location_observation AS observation
		WHERE observation.fix_time_ms >= :fromMs
		  AND observation.fix_time_ms < :toMs
		  AND (
			observation.ingress_disposition = 'MIGRATED_ACCEPTED'
			OR (
				observation.ingress_disposition = 'DELIVERED_VALID'
				AND EXISTS (
					SELECT 1
					FROM location_sample AS accepted
					WHERE accepted.time_ms = observation.fix_time_ms
					  AND accepted.elapsed_realtime_nanos = observation.fix_elapsed_realtime_nanos
					  AND accepted.lat_e7 = observation.lat_e7
					  AND accepted.lon_e7 = observation.lon_e7
					  AND accepted.batch_index = observation.batch_index
					  AND accepted.batch_size = observation.batch_size
				)
			)
		  )
		ORDER BY observation.fix_time_ms ASC, observation.id ASC
		""",
	)
	suspend fun getObservedPresenceSupportBetween(fromMs: Long, toMs: Long): List<LocationObservation>

	@Query("SELECT COALESCE(MAX(id), 0) FROM location_observation")
	suspend fun maxId(): Long

	/** Stable, bounded snapshot page for diagnostic/research exports. */
	@Query(
		"""
		SELECT * FROM location_observation
		WHERE id > :afterId AND id <= :throughId
		  AND fix_time_ms >= :fromMs AND fix_time_ms <= :toMsInclusive
		ORDER BY id ASC
		LIMIT :limit
		""",
	)
	suspend fun getExportChunk(
		fromMs: Long,
		toMsInclusive: Long,
		afterId: Long,
		throughId: Long,
		limit: Int,
	): List<LocationObservation>

	/**
	 * Observations that arrived after a checkpoint snapshot but belong behind its compacted horizon.
	 * [throughId] makes the scan a stable snapshot: later inserts remain above the next watermark.
	 */
	@Query(
		"""
		SELECT id, fix_time_ms AS fixTimeMs
		FROM location_observation
		WHERE id > :afterId AND id <= :throughId AND fix_time_ms < :beforeFixTimeMs
		ORDER BY id ASC
		""",
	)
	suspend fun getLateArrivals(
		afterId: Long,
		throughId: Long,
		beforeFixTimeMs: Long,
	): List<LocationObservationRef>

	@Query("DELETE FROM location_observation WHERE fix_time_ms < :beforeMs")
	suspend fun deleteOlderThan(beforeMs: Long): Int

	/** Deletes only evidence covered by both the temporal checkpoint and its observation watermark. */
	@Query("DELETE FROM location_observation WHERE fix_time_ms < :beforeMs AND id <= :throughId")
	suspend fun deleteOlderThanThroughId(beforeMs: Long, throughId: Long): Int

	@Query("DELETE FROM location_observation")
	fun deleteAll()
}
