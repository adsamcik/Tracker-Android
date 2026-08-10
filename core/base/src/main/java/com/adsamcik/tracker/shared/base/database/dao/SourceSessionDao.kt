package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventSessionBindingEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity

@Dao
interface SourceSessionDao {
	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertSession(entity: LogicalTrackingSessionEntity)

	@Update
	suspend fun updateSession(entity: LogicalTrackingSessionEntity): Int

	@Query("SELECT * FROM logical_tracking_session WHERE logical_tracking_id = :logicalTrackingId")
	suspend fun session(logicalTrackingId: String): LogicalTrackingSessionEntity?

	@Query(
		"SELECT * FROM logical_tracking_session WHERE state NOT IN ('CLOSED', 'FAILED') " +
			"ORDER BY started_at_ms DESC LIMIT 1",
	)
	suspend fun activeSession(): LogicalTrackingSessionEntity?

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertServiceRun(entity: SourceServiceRunEntity)

	@Update
	suspend fun updateServiceRun(entity: SourceServiceRunEntity): Int

	@Query("SELECT * FROM source_service_run WHERE service_run_id = :serviceRunId")
	suspend fun serviceRun(serviceRunId: String): SourceServiceRunEntity?

	@Query(
		"SELECT * FROM source_service_run WHERE logical_tracking_id = :logicalTrackingId " +
			"ORDER BY started_at_ms DESC LIMIT 1",
	)
	suspend fun latestServiceRun(logicalTrackingId: String): SourceServiceRunEntity?

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun bindEvent(entity: SourceEventSessionBindingEntity)

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun saveCompleteness(entity: SourceSessionCompletenessEntity)

	@Query("SELECT * FROM source_session_completeness WHERE logical_tracking_id = :logicalTrackingId")
	suspend fun completeness(logicalTrackingId: String): List<SourceSessionCompletenessEntity>

	@Query("DELETE FROM source_session_completeness")
	fun deleteAllCompleteness()

	@Query("DELETE FROM source_event_session_binding")
	fun deleteAllBindings()

	@Query("DELETE FROM source_service_run")
	fun deleteAllServiceRuns()

	@Query("DELETE FROM logical_tracking_session")
	fun deleteAllSessions()
}
