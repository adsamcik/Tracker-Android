package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.LocationObservationDecision

@Dao
interface LocationObservationDecisionDao {
	/** Replays are no-ops; source-event and source-signal identities are both unique. */
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insert(decisions: List<LocationObservationDecision>): List<Long>

	@Query(
		"""
		SELECT * FROM location_observation_decision
		WHERE observation_source_event_id = :sourceEventId
		LIMIT 1
		""",
	)
	suspend fun getBySourceEventId(sourceEventId: String): LocationObservationDecision?

	@Query(
		"""
		SELECT * FROM location_observation_decision
		WHERE decided_at_ms >= :fromMs AND decided_at_ms <= :toMs
		ORDER BY decided_at_ms ASC, id ASC
		""",
	)
	suspend fun getBetween(fromMs: Long, toMs: Long): List<LocationObservationDecision>

	@Query("DELETE FROM location_observation_decision WHERE decided_at_ms < :beforeMs")
	suspend fun deleteOlderThan(beforeMs: Long): Int

	/**
	 * Removes terminal decisions whose immutable raw observation was removed by
	 * retention. Decision time can be newer than the provider-fix time after
	 * delayed replay, so a time-only predicate is insufficient.
	 */
	@Query(
		"""
		DELETE FROM location_observation_decision
		WHERE observation_source_event_id NOT IN (
			SELECT source_event_id
			FROM location_observation
			WHERE source_event_id IS NOT NULL
		)
		""",
	)
	suspend fun deleteWithoutObservation(): Int

	@Query("DELETE FROM location_observation_decision")
	fun deleteAll()
}
