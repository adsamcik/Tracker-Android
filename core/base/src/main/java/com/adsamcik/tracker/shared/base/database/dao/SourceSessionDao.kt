package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.adsamcik.tracker.shared.base.database.data.LifecycleDesiredActionEntity
import com.adsamcik.tracker.shared.base.database.data.LifecycleDesiredActionEntity.RawLifecycleDesiredAction
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity.RawSessionManifestSource
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionLifecycleIntentVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.SourceRunRetirementEntity
import com.adsamcik.tracker.shared.base.database.data.SourceRunRetirementEntity.RawSourceRunRetirement

@Dao
interface SourceSessionDao {
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertRunRetirementIntent(entity: SourceRunRetirementEntity): Long

	@Query(
		"SELECT * FROM source_run_retirement WHERE logical_tracking_id = :logicalTrackingId " +
			"AND service_run_id = :serviceRunId AND source_kind = :sourceKind " +
			"ORDER BY registration_generation DESC",
	)
	suspend fun runRetirements(
		logicalTrackingId: String,
		serviceRunId: String,
		sourceKind: Int,
	): List<SourceRunRetirementEntity>

	@Query(
		"SELECT " + RAW_RUN_RETIREMENT_PROJECTION + " FROM source_run_retirement WHERE " +
			"((typeof(logical_tracking_id) = 'text' AND logical_tracking_id = :logicalTrackingId) " +
			"OR typeof(logical_tracking_id) != 'text') AND " +
			"((typeof(service_run_id) = 'text' AND service_run_id = :serviceRunId) " +
			"OR typeof(service_run_id) != 'text') AND " +
			"((typeof(source_kind) = 'integer' AND source_kind = :sourceKind) " +
			"OR typeof(source_kind) != 'integer') " +
			"ORDER BY registration_generation DESC, source_instance_id, action_id, rowid LIMIT :limit",
	)
	suspend fun rawRunRetirements(
		logicalTrackingId: String,
		serviceRunId: String,
		sourceKind: Int,
		limit: Int,
	): List<RawSourceRunRetirement>

	@Query(
		"SELECT * FROM source_run_retirement WHERE logical_tracking_id = :logicalTrackingId " +
			"AND service_run_id = :serviceRunId AND source_kind = :sourceKind " +
			"AND source_instance_id = :sourceInstanceId " +
			"AND registration_generation = :registrationGeneration LIMIT 1",
	)
	suspend fun runRetirement(
		logicalTrackingId: String,
		serviceRunId: String,
		sourceKind: Int,
		sourceInstanceId: String,
		registrationGeneration: Long,
	): SourceRunRetirementEntity?

	@Query(
		"SELECT " + RAW_RUN_RETIREMENT_PROJECTION + " FROM source_run_retirement WHERE " +
			"((typeof(logical_tracking_id) = 'text' AND logical_tracking_id = :logicalTrackingId) " +
			"OR typeof(logical_tracking_id) != 'text') AND " +
			"((typeof(service_run_id) = 'text' AND service_run_id = :serviceRunId) " +
			"OR typeof(service_run_id) != 'text') AND " +
			"((typeof(source_kind) = 'integer' AND source_kind = :sourceKind) " +
			"OR typeof(source_kind) != 'integer') AND " +
			"((typeof(source_instance_id) = 'text' AND source_instance_id = :sourceInstanceId) " +
			"OR typeof(source_instance_id) != 'text') AND " +
			"((typeof(registration_generation) = 'integer' " +
			"AND registration_generation = :registrationGeneration) " +
			"OR typeof(registration_generation) != 'integer') " +
			"ORDER BY registration_generation DESC, source_instance_id, action_id, rowid LIMIT 2",
	)
	suspend fun rawRunRetirement(
		logicalTrackingId: String,
		serviceRunId: String,
		sourceKind: Int,
		sourceInstanceId: String,
		registrationGeneration: Long,
	): List<RawSourceRunRetirement>

	@Update
	suspend fun updateRunRetirement(entity: SourceRunRetirementEntity): Int
	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertSession(entity: LogicalTrackingSessionEntity)

	@Update
	suspend fun updateSession(entity: LogicalTrackingSessionEntity): Int

	@Query("SELECT * FROM logical_tracking_session WHERE logical_tracking_id = :logicalTrackingId")
	suspend fun session(logicalTrackingId: String): LogicalTrackingSessionEntity?

	/** Bounded exact logical identities used to group replacement-run presentation rows. */
	@Query("SELECT * FROM logical_tracking_session WHERE logical_tracking_id IN (:logicalTrackingIds)")
	suspend fun sessions(logicalTrackingIds: List<String>): List<LogicalTrackingSessionEntity>

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

	/**
	 * Global cutover boundary. A writer-generation transition advances the rollout revision for the
	 * whole coordinator, so even a non-Steps live session must finish before that transition.
	 * Terminal migrated rows may use the released-v27 `CLOSED` state.
	 */
	@Query(
		"SELECT EXISTS(SELECT 1 FROM logical_tracking_session session WHERE " +
			"session.state NOT IN ('FINALIZED', 'CLOSED', 'FAILED') " +
			"OR session.current_service_run_id IS NOT NULL " +
			"OR (session.state IN ('FINALIZED', 'CLOSED') " +
			"AND session.current_intent_revision IS NOT NULL AND NOT EXISTS (" +
			"SELECT 1 FROM session_lifecycle_intent_version intent " +
			"WHERE intent.logical_tracking_id = session.logical_tracking_id " +
			"AND intent.intent_revision = session.current_intent_revision " +
			"AND intent.desired_state = 'FINALIZED')))"
	)
	suspend fun hasLifecycleBoundaryBlocker(): Boolean

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
			"ORDER BY manifest_revision ASC LIMIT :limit",
	)
	/** Deterministic bounded manifest-revision read for one exact physical service run. */
	suspend fun manifestsForServiceRun(
		serviceRunId: String,
		limit: Int = Int.MAX_VALUE,
	): List<SessionManifestVersionEntity>

	@Query(
		"SELECT * FROM session_manifest_version WHERE service_run_id = :serviceRunId " +
			"AND manifest_revision = :manifestRevision",
	)
	suspend fun manifestByServiceRunRevision(
		serviceRunId: String,
		manifestRevision: Long,
	): SessionManifestVersionEntity?

	@Query(
		"SELECT * FROM session_manifest_version WHERE service_run_id = :serviceRunId " +
			"AND manifest_revision IN (:manifestRevisions) ORDER BY manifest_revision ASC",
	)
	suspend fun manifestsForServiceRunRevisions(
		serviceRunId: String,
		manifestRevisions: List<Long>,
	): List<SessionManifestVersionEntity>

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
		"SELECT " + RAW_MANIFEST_SOURCE_PROJECTION + " FROM session_manifest_source WHERE " +
			"((typeof(logical_tracking_id) = 'text' AND logical_tracking_id = :logicalTrackingId) " +
			"OR typeof(logical_tracking_id) != 'text') AND " +
			"((typeof(manifest_revision) = 'integer' AND manifest_revision = :manifestRevision) " +
			"OR typeof(manifest_revision) != 'integer') " +
			"ORDER BY purpose, source_kind, rowid LIMIT :limit",
	)
	suspend fun rawManifestSources(
		logicalTrackingId: String,
		manifestRevision: Long,
		limit: Int,
	): List<RawSessionManifestSource>

	@Query(
		"SELECT " + RAW_MANIFEST_SOURCE_PROJECTION + " FROM session_manifest_source " +
			"WHERE logical_tracking_id = :logicalTrackingId " +
			"AND manifest_revision IN (:manifestRevisions) " +
			"ORDER BY manifest_revision, purpose, source_kind, rowid LIMIT :limit",
	)
	suspend fun rawManifestSourcesForRevisions(
		logicalTrackingId: String,
		manifestRevisions: List<Long>,
		limit: Int,
	): List<RawSessionManifestSource>

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
		"SELECT * FROM session_lifecycle_intent_version " +
			"WHERE logical_tracking_id = :logicalTrackingId " +
			"AND manifest_revision = :manifestRevision ORDER BY intent_revision ASC LIMIT :limit",
	)
	suspend fun lifecycleIntentsForManifestBounded(
		logicalTrackingId: String,
		manifestRevision: Long,
		limit: Int,
	): List<SessionLifecycleIntentVersionEntity>

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

	/** Bounded exact historical source-start settlements for one manifest/source binding. */
	@Query(
		"SELECT * FROM lifecycle_desired_action WHERE logical_tracking_id = :logicalTrackingId " +
			"AND service_run_id = :serviceRunId AND manifest_revision = :manifestRevision " +
			"AND source_kind = :sourceKind AND action_family = 'SOURCE_RUNTIME' " +
			"AND desired_state = 'STARTED' ORDER BY action_revision ASC LIMIT :limit",
	)
	suspend fun sourceStartActionsForManifestBounded(
		logicalTrackingId: String,
		serviceRunId: String,
		manifestRevision: Long,
		sourceKind: Int,
		limit: Int,
	): List<LifecycleDesiredActionEntity>

	@Query(
		"SELECT * FROM lifecycle_desired_action WHERE logical_tracking_id = :logicalTrackingId " +
			"ORDER BY action_revision ASC",
	)
	suspend fun lifecycleActions(logicalTrackingId: String): List<LifecycleDesiredActionEntity>

	@Query(
		"SELECT * FROM lifecycle_desired_action WHERE logical_tracking_id = :logicalTrackingId " +
			"AND service_run_id = :serviceRunId ORDER BY action_revision ASC LIMIT :limit",
	)
	suspend fun lifecycleActionsForServiceRunBounded(
		logicalTrackingId: String,
		serviceRunId: String,
		limit: Int,
	): List<LifecycleDesiredActionEntity>

	@Query(
		"SELECT " + RAW_LIFECYCLE_ACTION_PROJECTION + " FROM lifecycle_desired_action WHERE " +
			"((typeof(logical_tracking_id) = 'text' AND logical_tracking_id = :logicalTrackingId) " +
			"OR typeof(logical_tracking_id) != 'text' " +
			"OR (typeof(logical_tracking_id) = 'text' AND trim(logical_tracking_id) = '')) AND " +
			"((typeof(service_run_id) = 'text' AND service_run_id = :serviceRunId) " +
			"OR typeof(service_run_id) != 'text' " +
			"OR (typeof(service_run_id) = 'text' AND trim(service_run_id) = '')) " +
			"ORDER BY action_revision, action_id, rowid LIMIT :limit",
	)
	suspend fun rawLifecycleActionsForServiceRunBounded(
		logicalTrackingId: String,
		serviceRunId: String,
		limit: Int,
	): List<RawLifecycleDesiredAction>

	@Query(
		"SELECT * FROM lifecycle_desired_action WHERE status IN " +
			"('PENDING', 'APPLYING', 'CLEANUP_REQUIRED', 'TEMPORARILY_ILLEGAL') " +
			"ORDER BY requested_at_ms, action_revision",
	)
	suspend fun pendingLifecycleActions(): List<LifecycleDesiredActionEntity>

	/**
	 * Includes prepared and accepted starts that the legacy pending-action query does not expose,
	 * while ignoring a historical start after a later action for the same run/source settled it.
	 */
	@Query(
		"SELECT EXISTS(SELECT 1 FROM lifecycle_desired_action candidate WHERE " +
			"candidate.status NOT IN ('TERMINAL_FAILURE', 'STOP_ACCEPTED', 'SUPERSEDED') " +
			"AND NOT EXISTS(SELECT 1 FROM lifecycle_desired_action newer WHERE " +
			"newer.logical_tracking_id = candidate.logical_tracking_id " +
			"AND newer.service_run_id = candidate.service_run_id " +
			"AND newer.action_family = candidate.action_family " +
			"AND newer.source_kind IS candidate.source_kind " +
			"AND newer.action_revision > candidate.action_revision))",
	)
	suspend fun hasNonterminalLatestLifecycleAction(): Boolean

	/**
	 * True when one exact run still has a nonterminal latest action-family/source claim.
	 *
	 * Latest selection spans every persisted status. Filtering attempts, desired state, or status
	 * before choosing the latest row could let an older accepted start outrank its newer settlement.
	 */
	@Query(
		"SELECT EXISTS(SELECT 1 FROM lifecycle_desired_action candidate WHERE " +
			"candidate.logical_tracking_id = :logicalTrackingId " +
			"AND candidate.service_run_id = :serviceRunId " +
			"AND candidate.status NOT IN ('TERMINAL_FAILURE', 'STOP_ACCEPTED', 'SUPERSEDED') " +
			"AND NOT EXISTS(SELECT 1 FROM lifecycle_desired_action newer WHERE " +
			"newer.logical_tracking_id = candidate.logical_tracking_id " +
			"AND newer.service_run_id = candidate.service_run_id " +
			"AND newer.action_family = candidate.action_family " +
			"AND newer.source_kind IS candidate.source_kind " +
			"AND newer.action_revision > candidate.action_revision))",
	)
	suspend fun hasNonterminalLatestLifecycleAction(
		logicalTrackingId: String,
		serviceRunId: String,
	): Boolean

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

	/**
	 * Binds the source-neutral presentation segment without changing the source-lifecycle revision.
	 * The surrounding Room transaction validates that this is still the logical session's current
	 * active run. A nullable unique index provides the reverse one-run-per-segment fence.
	 */
	@Query(
		"UPDATE source_service_run SET session_segment_id = :sessionSegmentId " +
			"WHERE service_run_id = :serviceRunId AND logical_tracking_id = :logicalTrackingId " +
			"AND state = 'ACTIVE' AND completed_at_ms IS NULL AND session_segment_id IS NULL " +
			"AND presentation_acknowledgement = 'PENDING'",
	)
	suspend fun bindSessionSegmentExact(
		logicalTrackingId: String,
		serviceRunId: String,
		sessionSegmentId: Long,
	): Int

	/** Advances only the exact terminal run/segment pair after every presentation writer stopped. */
	@Query(
		"UPDATE source_service_run SET presentation_acknowledgement = 'QUIESCED', " +
			"presentation_acknowledged_at_ms = :acknowledgedAtMs " +
			"WHERE service_run_id = :serviceRunId AND logical_tracking_id = :logicalTrackingId " +
			"AND session_segment_id = :sessionSegmentId AND state IN ('FINALIZED', 'FAILED') " +
			"AND completed_at_ms IS NOT NULL AND presentation_acknowledgement = 'PENDING'",
	)
	suspend fun acknowledgePresentationQuiescedExact(
		logicalTrackingId: String,
		serviceRunId: String,
		sessionSegmentId: Long,
		acknowledgedAtMs: Long,
	): Int

	@Query("SELECT * FROM source_service_run WHERE start_delivery_token = :deliveryToken")
	suspend fun serviceRunByDeliveryToken(deliveryToken: String): SourceServiceRunEntity?

	@Query(
		"SELECT * FROM source_service_run WHERE logical_tracking_id = :logicalTrackingId " +
			"AND completed_at_ms IS NULL AND state NOT IN ('FINALIZED', 'CLOSED', 'FAILED') " +
			"ORDER BY started_at_ms ASC",
	)
	suspend fun incompleteServiceRuns(logicalTrackingId: String): List<SourceServiceRunEntity>

	/** Detects orphan/inconsistent runs as well as ordinary live service runs. */
	@Query(
		"SELECT EXISTS(SELECT 1 FROM source_service_run WHERE completed_at_ms IS NULL " +
			"OR state NOT IN ('FINALIZED', 'CLOSED', 'FAILED'))",
	)
	suspend fun hasIncompleteServiceRun(): Boolean

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

private const val RAW_RUN_RETIREMENT_PROJECTION =
	"typeof(logical_tracking_id) || '|' || typeof(service_run_id) || '|' || " +
		"typeof(source_kind) || '|' || typeof(source_instance_id) || '|' || " +
		"typeof(registration_generation) || '|' || typeof(action_id) || '|' || " +
		"typeof(attempt_count) || '|' || typeof(lease_generation) || '|' || " +
		"typeof(cutoff_elapsed_realtime_nanos) || '|' || typeof(cutoff_wall_time_ms) || '|' || " +
		"typeof(state) || '|' || typeof(applied_revision) || '|' || " +
		"typeof(callback_entry_barrier_sequence) || '|' || typeof(last_source_sequence) || '|' || " +
		"typeof(last_admission_ordinal) || '|' || typeof(failed_admission_count) || '|' || " +
		"typeof(unresolved_sequence_start) || '|' || typeof(unresolved_sequence_end) || '|' || " +
		"typeof(registration_removal_outcome) || '|' || typeof(provider_flush_outcome) || '|' || " +
		"typeof(provider_coverage) || '|' || typeof(app_drain_complete) || '|' || " +
		"typeof(stop_status) || '|' || typeof(updated_at_ms) AS storage_class_signature, " +
		"CASE WHEN typeof(logical_tracking_id) = 'text' THEN logical_tracking_id END " +
		"AS logical_tracking_id, " +
		"CASE WHEN typeof(service_run_id) = 'text' THEN service_run_id END AS service_run_id, " +
		"CASE WHEN typeof(source_kind) = 'integer' THEN source_kind END AS source_kind, " +
		"CASE WHEN typeof(source_instance_id) = 'text' THEN source_instance_id END " +
		"AS source_instance_id, " +
		"CASE WHEN typeof(registration_generation) = 'integer' THEN registration_generation END " +
		"AS registration_generation, " +
		"CASE WHEN typeof(action_id) = 'text' THEN action_id END AS action_id, " +
		"CASE WHEN typeof(attempt_count) = 'integer' THEN attempt_count END AS attempt_count, " +
		"CASE WHEN typeof(lease_generation) = 'integer' THEN lease_generation END " +
		"AS lease_generation, " +
		"CASE WHEN typeof(cutoff_elapsed_realtime_nanos) = 'integer' " +
		"THEN cutoff_elapsed_realtime_nanos END AS cutoff_elapsed_realtime_nanos, " +
		"CASE WHEN typeof(cutoff_wall_time_ms) = 'integer' THEN cutoff_wall_time_ms END " +
		"AS cutoff_wall_time_ms, " +
		"CASE WHEN typeof(state) = 'text' THEN state END AS state, " +
		"CASE WHEN typeof(applied_revision) IN ('integer', 'null') THEN applied_revision END " +
		"AS applied_revision, " +
		"CASE WHEN typeof(callback_entry_barrier_sequence) IN ('integer', 'null') " +
		"THEN callback_entry_barrier_sequence END AS callback_entry_barrier_sequence, " +
		"CASE WHEN typeof(last_source_sequence) IN ('integer', 'null') THEN last_source_sequence END " +
		"AS last_source_sequence, " +
		"CASE WHEN typeof(last_admission_ordinal) IN ('integer', 'null') " +
		"THEN last_admission_ordinal END AS last_admission_ordinal, " +
		"CASE WHEN typeof(failed_admission_count) IN ('integer', 'null') " +
		"THEN failed_admission_count END AS failed_admission_count, " +
		"CASE WHEN typeof(unresolved_sequence_start) IN ('integer', 'null') " +
		"THEN unresolved_sequence_start END AS unresolved_sequence_start, " +
		"CASE WHEN typeof(unresolved_sequence_end) IN ('integer', 'null') " +
		"THEN unresolved_sequence_end END AS unresolved_sequence_end, " +
		"CASE WHEN typeof(registration_removal_outcome) IN ('text', 'null') " +
		"THEN registration_removal_outcome END AS registration_removal_outcome, " +
		"CASE WHEN typeof(provider_flush_outcome) IN ('text', 'null') " +
		"THEN provider_flush_outcome END AS provider_flush_outcome, " +
		"CASE WHEN typeof(provider_coverage) IN ('text', 'null') THEN provider_coverage END " +
		"AS provider_coverage, " +
		"CASE WHEN typeof(app_drain_complete) IN ('integer', 'null') THEN app_drain_complete END " +
		"AS app_drain_complete, " +
		"CASE WHEN typeof(stop_status) IN ('text', 'null') THEN stop_status END AS stop_status, " +
		"CASE WHEN typeof(updated_at_ms) = 'integer' THEN updated_at_ms END AS updated_at_ms"

private const val RAW_MANIFEST_SOURCE_PROJECTION =
	"typeof(logical_tracking_id) || '|' || typeof(manifest_revision) || '|' || " +
		"typeof(source_kind) || '|' || typeof(purpose) || '|' || typeof(consent_epoch) || '|' || " +
		"typeof(persistence_eligible) || '|' || typeof(qos_code) || '|' || " +
		"typeof(output_destination) || '|' || typeof(writer_owner) || '|' || " +
		"typeof(writer_owner_generation) || '|' || typeof(writer_projection_id) || '|' || " +
		"typeof(writer_projection_version) || '|' || typeof(writer_binding_generation) " +
		"AS storage_class_signature, " +
		"CASE WHEN typeof(logical_tracking_id) = 'text' THEN logical_tracking_id END " +
		"AS logical_tracking_id, " +
		"CASE WHEN typeof(manifest_revision) = 'integer' THEN manifest_revision END " +
		"AS manifest_revision, " +
		"CASE WHEN typeof(source_kind) = 'integer' THEN source_kind END AS source_kind, " +
		"CASE WHEN typeof(purpose) = 'text' THEN purpose END AS purpose, " +
		"CASE WHEN typeof(consent_epoch) = 'integer' THEN consent_epoch END AS consent_epoch, " +
		"CASE WHEN typeof(persistence_eligible) = 'integer' THEN persistence_eligible END " +
		"AS persistence_eligible, " +
		"CASE WHEN typeof(qos_code) = 'integer' THEN qos_code END AS qos_code, " +
		"CASE WHEN typeof(output_destination) IN ('text', 'null') THEN output_destination END " +
		"AS output_destination, " +
		"CASE WHEN typeof(writer_owner) IN ('text', 'null') THEN writer_owner END AS writer_owner, " +
		"CASE WHEN typeof(writer_owner_generation) IN ('integer', 'null') " +
		"THEN writer_owner_generation END AS writer_owner_generation, " +
		"CASE WHEN typeof(writer_projection_id) IN ('text', 'null') THEN writer_projection_id END " +
		"AS writer_projection_id, " +
		"CASE WHEN typeof(writer_projection_version) IN ('integer', 'null') " +
		"THEN writer_projection_version END AS writer_projection_version, " +
		"CASE WHEN typeof(writer_binding_generation) IN ('integer', 'null') " +
		"THEN writer_binding_generation END AS writer_binding_generation"

private const val RAW_LIFECYCLE_ACTION_PROJECTION =
	"typeof(action_id) || '|' || typeof(logical_tracking_id) || '|' || " +
		"typeof(service_run_id) || '|' || typeof(manifest_revision) || '|' || " +
		"typeof(action_revision) || '|' || typeof(action_family) || '|' || " +
		"typeof(source_kind) || '|' || typeof(desired_state) || '|' || " +
		"typeof(desired_plan_revision) || '|' || typeof(source_policy_revision) || '|' || " +
		"typeof(consent_epoch) || '|' || typeof(start_origin) || '|' || typeof(boot_id) || '|' || " +
		"typeof(lease_generation) || '|' || typeof(requested_at_ms) || '|' || " +
		"typeof(requested_elapsed_realtime_nanos) || '|' || typeof(status) || '|' || " +
		"typeof(attempt_count) || '|' || typeof(acknowledged_at_ms) || '|' || " +
		"typeof(acknowledged_elapsed_realtime_nanos) || '|' || typeof(failure_code) || '|' || " +
		"typeof(retry_trigger) || '|' || typeof(source_instance_id) || '|' || " +
		"typeof(registration_generation) AS storage_class_signature, " +
		"CASE WHEN typeof(action_id) = 'text' THEN action_id END AS action_id, " +
		"CASE WHEN typeof(logical_tracking_id) = 'text' THEN logical_tracking_id END " +
		"AS logical_tracking_id, " +
		"CASE WHEN typeof(service_run_id) = 'text' THEN service_run_id END AS service_run_id, " +
		"CASE WHEN typeof(manifest_revision) = 'integer' THEN manifest_revision END " +
		"AS manifest_revision, " +
		"CASE WHEN typeof(action_revision) = 'integer' THEN action_revision END " +
		"AS action_revision, " +
		"CASE WHEN typeof(action_family) = 'text' THEN action_family END AS action_family, " +
		"CASE WHEN typeof(source_kind) IN ('integer', 'null') THEN source_kind END AS source_kind, " +
		"CASE WHEN typeof(desired_state) = 'text' THEN desired_state END AS desired_state, " +
		"CASE WHEN typeof(desired_plan_revision) = 'integer' THEN desired_plan_revision END " +
		"AS desired_plan_revision, " +
		"CASE WHEN typeof(source_policy_revision) = 'integer' THEN source_policy_revision END " +
		"AS source_policy_revision, " +
		"CASE WHEN typeof(consent_epoch) IN ('integer', 'null') THEN consent_epoch END " +
		"AS consent_epoch, " +
		"CASE WHEN typeof(start_origin) = 'text' THEN start_origin END AS start_origin, " +
		"CASE WHEN typeof(boot_id) = 'text' THEN boot_id END AS boot_id, " +
		"CASE WHEN typeof(lease_generation) = 'integer' THEN lease_generation END " +
		"AS lease_generation, " +
		"CASE WHEN typeof(requested_at_ms) = 'integer' THEN requested_at_ms END " +
		"AS requested_at_ms, " +
		"CASE WHEN typeof(requested_elapsed_realtime_nanos) = 'integer' " +
		"THEN requested_elapsed_realtime_nanos END AS requested_elapsed_realtime_nanos, " +
		"CASE WHEN typeof(status) = 'text' THEN status END AS status, " +
		"CASE WHEN typeof(attempt_count) = 'integer' THEN attempt_count END AS attempt_count, " +
		"CASE WHEN typeof(acknowledged_at_ms) IN ('integer', 'null') " +
		"THEN acknowledged_at_ms END AS acknowledged_at_ms, " +
		"CASE WHEN typeof(acknowledged_elapsed_realtime_nanos) IN ('integer', 'null') " +
		"THEN acknowledged_elapsed_realtime_nanos END AS acknowledged_elapsed_realtime_nanos, " +
		"CASE WHEN typeof(failure_code) IN ('text', 'null') THEN failure_code END " +
		"AS failure_code, " +
		"CASE WHEN typeof(retry_trigger) IN ('text', 'null') THEN retry_trigger END " +
		"AS retry_trigger, " +
		"CASE WHEN typeof(source_instance_id) IN ('text', 'null') THEN source_instance_id END " +
		"AS source_instance_id, " +
		"CASE WHEN typeof(registration_generation) IN ('integer', 'null') " +
		"THEN registration_generation END AS registration_generation"
