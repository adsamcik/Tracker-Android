package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.adsamcik.tracker.shared.base.database.data.LocationProjectionObservationEntity
import com.adsamcik.tracker.shared.base.database.data.LocationProjectionPointEntity

data class LocationProjectionRetentionRef(
	@ColumnInfo(name = "event_id") val eventId: String,
)

@Dao
interface LocationProjectionDao {
	@Upsert
	suspend fun upsertObservation(entity: LocationProjectionObservationEntity)

	@Upsert
	suspend fun upsertPoints(entities: List<LocationProjectionPointEntity>)

	@Query(
		"""
		SELECT * FROM location_projection_observation
		WHERE logical_tracking_id = :logicalTrackingId
		ORDER BY elapsed_realtime_nanos ASC, wall_time_ms ASC, event_id ASC
		""",
	)
	suspend fun observations(logicalTrackingId: String): List<LocationProjectionObservationEntity>

	@Query("SELECT * FROM location_projection_point WHERE logical_tracking_id = :logicalTrackingId")
	suspend fun points(logicalTrackingId: String): List<LocationProjectionPointEntity>

	@Query("SELECT * FROM location_projection_point WHERE event_id = :eventId")
	suspend fun point(eventId: String): LocationProjectionPointEntity?

	@Query(
		"""
		SELECT event_id FROM location_projection_observation
		WHERE event_id > :afterEventId
		ORDER BY event_id
		LIMIT :limit
		""",
	)
	suspend fun retentionPage(
		afterEventId: String,
		limit: Int,
	): List<LocationProjectionRetentionRef>

	@Query(
		"""
		SELECT * FROM location_projection_observation
		WHERE logical_tracking_id = :logicalTrackingId
		ORDER BY elapsed_realtime_nanos DESC, wall_time_ms DESC, event_id DESC
		LIMIT 1
		""",
	)
	suspend fun latestObservation(logicalTrackingId: String): LocationProjectionObservationEntity?

	@Query(
		"""
		SELECT observation.* FROM location_projection_observation observation
		INNER JOIN location_projection_point point ON point.event_id = observation.event_id
		WHERE observation.logical_tracking_id = :logicalTrackingId AND point.accepted = 1
		ORDER BY observation.elapsed_realtime_nanos DESC, observation.wall_time_ms DESC, observation.event_id DESC
		LIMIT 1
		""",
	)
	suspend fun latestAcceptedObservation(logicalTrackingId: String): LocationProjectionObservationEntity?

	@Query(
		"""
		SELECT observation.* FROM location_projection_observation observation
		INNER JOIN location_projection_point point ON point.event_id = observation.event_id
		WHERE observation.logical_tracking_id = :logicalTrackingId
		AND point.rejection = 'TELEPORT_UNCONFIRMED'
		ORDER BY observation.elapsed_realtime_nanos DESC, observation.wall_time_ms DESC, observation.event_id DESC
		LIMIT 1
		""",
	)
	suspend fun latestUnconfirmedTeleportObservation(
		logicalTrackingId: String,
	): LocationProjectionObservationEntity?

	@Query("DELETE FROM location_projection_observation WHERE wall_time_ms < :beforeMs")
	suspend fun deleteObservationsOlderThan(beforeMs: Long): Int

	@Query("DELETE FROM location_projection_observation WHERE event_id IN (:eventIds)")
	suspend fun deleteObservationsByEventIds(eventIds: List<String>): Int

	@Query("DELETE FROM location_projection_observation")
	fun deleteAllObservations(): Int
}
