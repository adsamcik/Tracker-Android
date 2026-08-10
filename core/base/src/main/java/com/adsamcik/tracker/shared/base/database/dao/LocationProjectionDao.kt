package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.adsamcik.tracker.shared.base.database.data.LocationProjectionObservationEntity
import com.adsamcik.tracker.shared.base.database.data.LocationProjectionPointEntity

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

	@Query("DELETE FROM location_projection_observation WHERE wall_time_ms < :beforeMs")
	suspend fun deleteObservationsOlderThan(beforeMs: Long): Int

	@Query("DELETE FROM location_projection_observation")
	fun deleteAllObservations(): Int
}
