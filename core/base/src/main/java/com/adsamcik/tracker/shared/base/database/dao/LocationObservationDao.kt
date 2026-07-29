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

	@Query("SELECT EXISTS(SELECT 1 FROM location_observation WHERE source_event_id = :sourceEventId)")
	suspend fun existsBySourceEventId(sourceEventId: String): Boolean

	@Query(
		"""
		SELECT * FROM location_observation
		WHERE fix_time_ms >= :fromMs AND fix_time_ms < :toMs
		ORDER BY fix_time_ms ASC, id ASC
		""",
	)
	suspend fun getBetween(fromMs: Long, toMs: Long): List<LocationObservation>

	@Query(
		"""
		SELECT * FROM location_observation
		WHERE clock_domain_id = :clockDomainId
		  AND fix_elapsed_realtime_nanos >= :fromElapsedRealtimeNanos
		  AND fix_elapsed_realtime_nanos <= :toElapsedRealtimeNanos
		ORDER BY fix_elapsed_realtime_nanos ASC, batch_index ASC, id ASC
		""",
	)
	suspend fun getInClockDomain(
		clockDomainId: String,
		fromElapsedRealtimeNanos: Long,
		toElapsedRealtimeNanos: Long,
	): List<LocationObservation>

	/**
	 * Provider fixes explicitly accepted by the curated pipeline.
	 *
	 * This deliberately joins the immutable provider-fix identity to an append-only decision. A
	 * same-valued timestamp/coordinate match is not evidence of acceptance: a batched callback can
	 * contain distinct fixes with identical values, and a later filter can reject only one of them.
	 */
	@Query(
		"""
		SELECT observation.*
		FROM location_observation AS observation
		INNER JOIN location_observation_decision AS decision
			ON decision.observation_source_event_id = observation.source_event_id
			AND decision.decision = 'ACCEPTED'
		WHERE observation.fix_time_ms >= :fromMs
		  AND observation.fix_time_ms < :toMs
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
