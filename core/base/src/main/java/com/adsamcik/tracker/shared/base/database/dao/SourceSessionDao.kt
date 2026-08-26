package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.adsamcik.tracker.shared.base.database.data.LifecycleDesiredActionEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionLifecycleIntentVersionEntity
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
		"SELECT * FROM logical_tracking_session WHERE state NOT IN ('FINALIZED', 'CLOSED', 'FAILED') " +
			"ORDER BY started_at_ms DESC LIMIT 1",
	)
	suspend fun activeSession(): LogicalTrackingSessionEntity?

	@Query(
		"SELECT * FROM logical_tracking_session WHERE state NOT IN ('FINALIZED', 'CLOSED', 'FAILED') " +
			"ORDER BY started_at_ms ASC",
	)
	suspend fun incompleteSessions(): List<LogicalTrackingSessionEntity>

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertManifest(entity: SessionManifestVersionEntity)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertManifestSources(entities: List<SessionManifestSourceEntity>)

	@Query(
		"SELECT * FROM session_manifest_version WHERE logical_tracking_id = :logicalTrackingId " +
			"ORDER BY manifest_revision ASC",
	)
	suspend fun manifests(logicalTrackingId: String): List<SessionManifestVersionEntity>

	@Query(
		"SELECT * FROM session_manifest_version WHERE service_run_id = :serviceRunId " +
			"ORDER BY manifest_revision ASC",
	)
	suspend fun manifestsForServiceRun(serviceRunId: String): List<SessionManifestVersionEntity>

	@Query(
		"SELECT * FROM session_manifest_version WHERE service_run_id = :serviceRunId " +
			"AND manifest_revision = :manifestRevision",
	)
	suspend fun manifestByServiceRunRevision(
		serviceRunId: String,
		manifestRevision: Long,
	): SessionManifestVersionEntity?

	@Query(
		"SELECT * FROM session_manifest_version WHERE logical_tracking_id = :logicalTrackingId " +
			"AND manifest_revision = :manifestRevision",
	)
	suspend fun manifest(logicalTrackingId: String, manifestRevision: Long): SessionManifestVersionEntity?

	@Query(
		"SELECT * FROM session_manifest_version WHERE logical_tracking_id = :logicalTrackingId " +
			"AND manifest_revision > :manifestRevision ORDER BY manifest_revision ASC LIMIT 1",
	)
	suspend fun nextManifest(
		logicalTrackingId: String,
		manifestRevision: Long,
	): SessionManifestVersionEntity?

	@Query(
		"SELECT * FROM session_manifest_source WHERE logical_tracking_id = :logicalTrackingId " +
			"AND manifest_revision = :manifestRevision ORDER BY purpose, source_kind",
	)
	suspend fun manifestSources(
		logicalTrackingId: String,
		manifestRevision: Long,
	): List<SessionManifestSourceEntity>

	@Query(
		"SELECT * FROM session_manifest_source WHERE logical_tracking_id = :logicalTrackingId " +
			"AND manifest_revision = :manifestRevision AND source_kind = :sourceKind " +
			"AND purpose = :purpose",
	)
	suspend fun manifestSource(
		logicalTrackingId: String,
		manifestRevision: Long,
		sourceKind: Int,
		purpose: String,
	): SessionManifestSourceEntity?

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertLifecycleIntent(entity: SessionLifecycleIntentVersionEntity)

	@Query(
		"SELECT * FROM session_lifecycle_intent_version WHERE logical_tracking_id = :logicalTrackingId " +
			"ORDER BY intent_revision ASC",
	)
	suspend fun lifecycleIntents(logicalTrackingId: String): List<SessionLifecycleIntentVersionEntity>

	@Query(
		"SELECT * FROM session_lifecycle_intent_version WHERE logical_tracking_id = :logicalTrackingId " +
			"AND intent_revision = :intentRevision",
	)
	suspend fun lifecycleIntent(
		logicalTrackingId: String,
		intentRevision: Long,
	): SessionLifecycleIntentVersionEntity?

	@Query(
		"SELECT * FROM session_lifecycle_intent_version WHERE trigger_id = :triggerId " +
			"AND desired_state = 'ACTIVE' ORDER BY requested_elapsed_realtime_nanos ASC LIMIT 1",
	)
	suspend fun lifecycleIntentByTriggerId(triggerId: String): SessionLifecycleIntentVersionEntity?

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertLifecycleActions(entities: List<LifecycleDesiredActionEntity>)

	@Update
	suspend fun updateLifecycleAction(entity: LifecycleDesiredActionEntity): Int

	@Query("SELECT * FROM lifecycle_desired_action WHERE action_id = :actionId")
	suspend fun lifecycleAction(actionId: String): LifecycleDesiredActionEntity?

	@Query(
		"SELECT * FROM lifecycle_desired_action WHERE logical_tracking_id = :logicalTrackingId " +
			"ORDER BY action_revision ASC",
	)
	suspend fun lifecycleActions(logicalTrackingId: String): List<LifecycleDesiredActionEntity>

	@Query(
		"SELECT * FROM lifecycle_desired_action WHERE status IN " +
			"('PENDING', 'APPLYING', 'CLEANUP_REQUIRED', 'TEMPORARILY_ILLEGAL') " +
			"ORDER BY requested_at_ms, action_revision",
	)
	suspend fun pendingLifecycleActions(): List<LifecycleDesiredActionEntity>

	@Query(
		"SELECT COALESCE(MAX(action_revision), 0) FROM lifecycle_desired_action " +
			"WHERE logical_tracking_id = :logicalTrackingId",
	)
	suspend fun maximumActionRevision(logicalTrackingId: String): Long

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertServiceRun(entity: SourceServiceRunEntity)

	@Update
	suspend fun updateServiceRun(entity: SourceServiceRunEntity): Int

	@Query("SELECT * FROM source_service_run WHERE service_run_id = :serviceRunId")
	suspend fun serviceRun(serviceRunId: String): SourceServiceRunEntity?

	@Query("SELECT * FROM source_service_run WHERE start_delivery_token = :deliveryToken")
	suspend fun serviceRunByDeliveryToken(deliveryToken: String): SourceServiceRunEntity?

	@Query(
		"SELECT * FROM source_service_run WHERE logical_tracking_id = :logicalTrackingId " +
			"AND completed_at_ms IS NULL AND state NOT IN ('FINALIZED', 'CLOSED', 'FAILED') " +
			"ORDER BY started_at_ms ASC",
	)
	suspend fun incompleteServiceRuns(logicalTrackingId: String): List<SourceServiceRunEntity>

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun saveCompleteness(entity: SourceSessionCompletenessEntity)

	@Query(
		"SELECT * FROM source_session_completeness WHERE logical_tracking_id = :logicalTrackingId " +
			"ORDER BY service_run_id, source_kind, source_instance_id, registration_generation",
	)
	suspend fun completeness(logicalTrackingId: String): List<SourceSessionCompletenessEntity>

	@Query(
		"SELECT * FROM source_session_completeness WHERE logical_tracking_id = :logicalTrackingId " +
			"AND service_run_id = :serviceRunId " +
			"ORDER BY source_kind, source_instance_id, registration_generation",
	)
	suspend fun completenessForServiceRun(
		logicalTrackingId: String,
		serviceRunId: String,
	): List<SourceSessionCompletenessEntity>

	@Query(
		"SELECT * FROM source_session_completeness WHERE logical_tracking_id = :logicalTrackingId " +
			"AND service_run_id = '__LEGACY_V27_UNATTRIBUTED__' " +
			"ORDER BY source_kind, source_instance_id, registration_generation",
	)
	suspend fun legacyUnattributedCompleteness(
		logicalTrackingId: String,
	): List<SourceSessionCompletenessEntity>

	@Query("DELETE FROM source_session_completeness")
	fun deleteAllCompleteness()

	@Query("DELETE FROM lifecycle_desired_action")
	fun deleteAllLifecycleActions()

	@Query("DELETE FROM session_manifest_source")
	fun deleteAllManifestSources()

	@Query("DELETE FROM session_lifecycle_intent_version")
	fun deleteAllLifecycleIntents()

	@Query("DELETE FROM session_manifest_version")
	fun deleteAllManifests()

	@Query("DELETE FROM source_service_run")
	fun deleteAllServiceRuns()

	@Query("DELETE FROM logical_tracking_session")
	fun deleteAllSessions()
}
