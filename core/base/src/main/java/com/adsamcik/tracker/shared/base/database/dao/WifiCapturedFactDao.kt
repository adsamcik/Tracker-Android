package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.AcquisitionPlanRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.LifecycleDesiredActionEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCaptureDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactCursorEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactRevisionEntity

data class WifiWalMaintenanceKey(
	@ColumnInfo(name = "admission_ordinal") val admissionOrdinal: Long,
	@ColumnInfo(name = "event_id") val eventId: String,
)

data class WifiWalPayloadSize(
	@ColumnInfo(name = "event_id") val eventId: String,
	@ColumnInfo(name = "payload_byte_count") val payloadByteCount: Long,
)

/** Bounded append-only storage boundary for dormant Wi-Fi captured facts. */
@Dao
interface WifiCapturedFactDao {
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertRevision(entity: WifiCapturedFactRevisionEntity): Long

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertCursor(entity: WifiCapturedFactCursorEntity): Long

	@Query(
		"SELECT * FROM wifi_captured_fact_revision WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion AND logical_fact_id = :logicalFactId " +
			"AND semantic_revision = :semanticRevision LIMIT 1",
	)
	suspend fun revision(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalFactId: String,
		semanticRevision: Long,
	): WifiCapturedFactRevisionEntity?

	@Query(
		"SELECT * FROM wifi_captured_fact_revision WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion AND logical_fact_id = :logicalFactId " +
			"ORDER BY semantic_revision DESC LIMIT :limit",
	)
	suspend fun revisionsForFact(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalFactId: String,
		limit: Int,
	): List<WifiCapturedFactRevisionEntity>

	@Query(
		"SELECT * FROM wifi_captured_fact_cursor WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion AND logical_fact_id = :logicalFactId LIMIT 1",
	)
	suspend fun cursor(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalFactId: String,
	): WifiCapturedFactCursorEntity?

	/** Latest effective predecessor, not the latest appended historical revision. */
	@Query(
		"SELECT fact.* FROM wifi_captured_fact_cursor AS cursor " +
			"INNER JOIN wifi_captured_fact_revision AS fact ON fact.writer_projection_id = cursor.writer_projection_id " +
			"AND fact.writer_projection_version = cursor.writer_projection_version " +
			"AND fact.logical_fact_id = cursor.logical_fact_id " +
			"AND fact.semantic_revision = cursor.latest_semantic_revision " +
			"WHERE fact.writer_projection_id = :writerProjectionId " +
			"AND fact.writer_projection_version = :writerProjectionVersion " +
			"AND fact.logical_tracking_id = :logicalTrackingId AND fact.service_run_id = :serviceRunId " +
			"AND fact.session_segment_id = :sessionSegmentId " +
			"AND fact.collected_data_epoch = :collectedDataEpoch " +
			"AND fact.scope_deletion_generation = :scopeDeletionGeneration " +
			"AND fact.source_admission_ordinal < :beforeSourceAdmissionOrdinal " +
			"ORDER BY fact.source_admission_ordinal DESC LIMIT 1",
	)
	suspend fun latestEffectiveBefore(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalTrackingId: String,
		serviceRunId: String,
		sessionSegmentId: Long,
		collectedDataEpoch: Long,
		scopeDeletionGeneration: Long,
		beforeSourceAdmissionOrdinal: Long,
	): WifiCapturedFactRevisionEntity?

	@Query(
		"UPDATE wifi_captured_fact_cursor SET latest_semantic_revision = :newSemanticRevision, " +
			"latest_mutation_id = :newMutationId, latest_effect_checksum = :newEffectChecksum, " +
			"latest_source_admission_ordinal = :newSourceAdmissionOrdinal, cursor_revision = :newCursorRevision, " +
			"updated_at_ms = :updatedAtMs WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion AND logical_fact_id = :logicalFactId " +
			"AND logical_tracking_id = :logicalTrackingId AND service_run_id = :serviceRunId " +
			"AND session_segment_id = :sessionSegmentId AND writer_owner_generation = :writerOwnerGeneration " +
			"AND collected_data_epoch = :collectedDataEpoch " +
			"AND scope_deletion_generation = :scopeDeletionGeneration " +
			"AND latest_semantic_revision = :expectedSemanticRevision " +
			"AND latest_mutation_id = :expectedMutationId AND latest_effect_checksum = :expectedEffectChecksum " +
			"AND latest_source_admission_ordinal = :expectedSourceAdmissionOrdinal " +
			"AND cursor_revision = :expectedCursorRevision",
	)
	suspend fun advanceCursorExact(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalFactId: String,
		logicalTrackingId: String,
		serviceRunId: String,
		sessionSegmentId: Long,
		writerOwnerGeneration: Long,
		collectedDataEpoch: Long,
		scopeDeletionGeneration: Long,
		expectedSemanticRevision: Long,
		expectedMutationId: String,
		expectedEffectChecksum: String,
		expectedSourceAdmissionOrdinal: Long,
		expectedCursorRevision: Long,
		newSemanticRevision: Long,
		newMutationId: String,
		newEffectChecksum: String,
		newSourceAdmissionOrdinal: Long,
		newCursorRevision: Long,
		updatedAtMs: Long,
	): Int

	@Query(
		"SELECT COUNT(*) FROM wifi_captured_fact_revision WHERE aggregate_owner_logical_fact_id = :logicalFactId " +
			"AND aggregate_owner_semantic_revision = :semanticRevision",
	)
	suspend fun dependentCount(logicalFactId: String, semanticRevision: Long): Long

	/**
	 * Complete one-hop fact closure for one portable logical entry.
	 *
	 * Both identity directions and every direct aggregate owner needed by the selected facts are
	 * selected. Unrelated dependents of a selected owner are deliberately outside this read-only
	 * export closure and cannot consume its cap.
	 */
	@Query(
		"""
		WITH selected_fact AS (
		 SELECT DISTINCT logical_fact_id, aggregate_owner_logical_fact_id
		 FROM wifi_captured_fact_revision
		 WHERE logical_tracking_id = :logicalTrackingId OR service_run_id IN (:serviceRunIds)
		), selected_owner AS (
		 SELECT DISTINCT aggregate_owner_logical_fact_id AS logical_fact_id
		 FROM selected_fact WHERE aggregate_owner_logical_fact_id IS NOT NULL
		), closure_id AS (
		 SELECT logical_fact_id FROM selected_fact
		 UNION SELECT logical_fact_id FROM selected_owner
		)
		SELECT fact.* FROM wifi_captured_fact_revision AS fact
		WHERE fact.logical_fact_id IN (SELECT logical_fact_id FROM closure_id)
		   OR fact.logical_tracking_id = :logicalTrackingId
		   OR fact.service_run_id IN (:serviceRunIds)
		ORDER BY fact.logical_fact_id, fact.semantic_revision
		LIMIT :limit
		""",
	)
	suspend fun portableRevisionClosure(
		logicalTrackingId: String,
		serviceRunIds: List<String>,
		limit: Int,
	): List<WifiCapturedFactRevisionEntity>

	/** Cursor side of the exact portable fact closure, including one-sided scope corruption. */
	@Query(
		"""
		WITH selected_fact AS (
		 SELECT DISTINCT logical_fact_id, aggregate_owner_logical_fact_id
		 FROM wifi_captured_fact_revision
		 WHERE logical_tracking_id = :logicalTrackingId OR service_run_id IN (:serviceRunIds)
		), selected_owner AS (
		 SELECT DISTINCT aggregate_owner_logical_fact_id AS logical_fact_id
		 FROM selected_fact WHERE aggregate_owner_logical_fact_id IS NOT NULL
		), closure_id AS (
		 SELECT logical_fact_id FROM selected_fact
		 UNION SELECT logical_fact_id FROM selected_owner
		)
		SELECT cursor.* FROM wifi_captured_fact_cursor AS cursor
		WHERE cursor.logical_fact_id IN (SELECT logical_fact_id FROM closure_id)
		   OR cursor.logical_tracking_id = :logicalTrackingId
		   OR cursor.service_run_id IN (:serviceRunIds)
		ORDER BY cursor.logical_fact_id
		LIMIT :limit
		""",
	)
	suspend fun portableCursorClosure(
		logicalTrackingId: String,
		serviceRunIds: List<String>,
		limit: Int,
	): List<WifiCapturedFactCursorEntity>

	/** Complete selected source-local deletion-generation side. */
	@Query(
		"SELECT * FROM wifi_capture_deletion_generation WHERE " +
			"logical_tracking_id = :logicalTrackingId OR service_run_id IN (:serviceRunIds) " +
			"ORDER BY logical_tracking_id, service_run_id LIMIT :limit",
	)
	suspend fun portableDeletionGenerationClosure(
		logicalTrackingId: String,
		serviceRunIds: List<String>,
		limit: Int,
	): List<WifiCaptureDeletionGenerationEntity>

	/** Complete selected Wi-Fi WAL scope; dependency keys are loaded separately in fixed batches. */
	@Query(
		"SELECT admission_ordinal, event_id FROM source_event_wal WHERE source_kind = :sourceKind " +
			"AND (logical_tracking_id = :logicalTrackingId OR service_run_id IN (:serviceRunIds)) " +
			"ORDER BY admission_ordinal LIMIT :limit",
	)
	suspend fun portableWalClosureKeys(
		sourceKind: Int,
		logicalTrackingId: String,
		serviceRunIds: List<String>,
		limit: Int,
	): List<WifiWalMaintenanceKey>

	/** Fixed-batch reverse lookup for WAL rows referenced by authenticated fact dependencies. */
	@Query(
		"SELECT admission_ordinal, event_id FROM source_event_wal WHERE source_kind = :sourceKind " +
			"AND event_id IN (:sourceEventIds) ORDER BY admission_ordinal",
	)
	suspend fun portableWalKeysForEvents(
		sourceKind: Int,
		sourceEventIds: List<String>,
	): List<WifiWalMaintenanceKey>

	/** Bounded batched payload-size preflight before the portable reader materializes WAL blobs. */
	@Query(
		"SELECT event_id, length(payload) AS payload_byte_count FROM source_event_wal " +
			"WHERE event_id IN (:eventIds) ORDER BY admission_ordinal",
	)
	suspend fun portableWalPayloadSizes(eventIds: List<String>): List<WifiWalPayloadSize>

	/** Bounded batched full-row load after [portableWalPayloadSizes] has accepted every blob. */
	@Query("SELECT * FROM source_event_wal WHERE event_id IN (:eventIds) ORDER BY admission_ordinal")
	suspend fun portableWalRows(eventIds: List<String>): List<SourceEventWalEntity>

	@Query(
		"SELECT * FROM wifi_capture_deletion_generation WHERE logical_tracking_id = :logicalTrackingId " +
			"AND service_run_id = :serviceRunId LIMIT 1",
	)
	suspend fun deletionGeneration(
		logicalTrackingId: String,
		serviceRunId: String,
	): WifiCaptureDeletionGenerationEntity?

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertDeletionGeneration(entity: WifiCaptureDeletionGenerationEntity)

	@Query(
		"UPDATE wifi_capture_deletion_generation SET generation = :newGeneration, " +
			"updated_at_ms = :updatedAtMs WHERE logical_tracking_id = :logicalTrackingId " +
			"AND service_run_id = :serviceRunId AND collected_data_epoch = :collectedDataEpoch " +
			"AND generation = :expectedGeneration AND :newGeneration = :expectedGeneration + 1",
	)
	suspend fun advanceDeletionGenerationExact(
		logicalTrackingId: String,
		serviceRunId: String,
		collectedDataEpoch: Long,
		expectedGeneration: Long,
		newGeneration: Long,
		updatedAtMs: Long,
	): Int

	/**
	 * Discovers Wi-Fi logical entries from durable cursor, captured-WAL admission, or completeness
	 * carriers. None of these carriers is itself source qualification; the product reader validates
	 * the complete logical group and current fact heads before exposing a value.
	 */
	@Query(
		"""
		WITH carrier_member AS (
		  SELECT DISTINCT segment.*
		  FROM wifi_captured_fact_cursor AS fact_cursor
		  JOIN source_service_run AS run
		    ON run.service_run_id = fact_cursor.service_run_id
		   AND run.logical_tracking_id = fact_cursor.logical_tracking_id
		   AND run.session_segment_id = fact_cursor.session_segment_id
		  JOIN session_segment AS segment
		    ON segment.id = run.session_segment_id
		   AND segment.service_run_id = run.service_run_id
		   AND segment.logical_tracking_id = run.logical_tracking_id
		  WHERE fact_cursor.writer_projection_id = :writerProjectionId
		    AND fact_cursor.writer_projection_version = :writerProjectionVersion
		  UNION
		  SELECT DISTINCT segment.*
		  FROM source_event_wal AS wal
		  JOIN source_evidence_state AS evidence ON evidence.id = 1
		   AND wal.captured_collected_data_epoch = evidence.collected_data_epoch
		   AND wal.admission_ordinal > evidence.deleted_source_event_high_water_ordinal
		  JOIN source_service_run AS run
		    ON run.service_run_id = wal.service_run_id
		   AND run.logical_tracking_id = wal.logical_tracking_id
		  JOIN session_segment AS segment
		    ON segment.id = run.session_segment_id
		   AND segment.service_run_id = run.service_run_id
		   AND segment.logical_tracking_id = run.logical_tracking_id
		  WHERE wal.source_kind = :sourceKind
		    AND wal.session_manifest_revision IS NOT NULL
		    AND wal.capture_consent_epoch IS NOT NULL
		    AND (wal.authorization_purpose_eligibility_mask & :capturePurposeMask) != 0
		  UNION
		  SELECT DISTINCT segment.*
		  FROM source_session_completeness AS completeness
		  JOIN source_service_run AS run
		    ON run.service_run_id = completeness.service_run_id
		   AND run.logical_tracking_id = completeness.logical_tracking_id
		  JOIN session_segment AS segment
		    ON segment.id = run.session_segment_id
		   AND segment.service_run_id = run.service_run_id
		   AND segment.logical_tracking_id = run.logical_tracking_id
		  WHERE completeness.source_kind = :sourceKind
		), logical_seed AS (
		  SELECT member.*
		  FROM carrier_member AS member
		  WHERE NOT EXISTS (
		    SELECT 1 FROM carrier_member AS newer
		    WHERE newer.logical_tracking_id = member.logical_tracking_id
		      AND (newer.start_time_ms > member.start_time_ms OR
		        (newer.start_time_ms = member.start_time_ms AND newer.id > member.id))
		  )
		), ranked_seed AS (
		  SELECT seed.*,
		    (SELECT MAX(member_segment.start_time_ms)
		     FROM source_service_run AS member_run
		     JOIN session_segment AS member_segment
		       ON member_segment.id = member_run.session_segment_id
		      AND member_segment.service_run_id = member_run.service_run_id
		      AND member_segment.logical_tracking_id = member_run.logical_tracking_id
		     WHERE member_run.logical_tracking_id = seed.logical_tracking_id
		    ) AS logical_recency_start_ms,
		    (SELECT MAX(member_segment.id)
		     FROM source_service_run AS member_run
		     JOIN session_segment AS member_segment
		       ON member_segment.id = member_run.session_segment_id
		      AND member_segment.service_run_id = member_run.service_run_id
		      AND member_segment.logical_tracking_id = member_run.logical_tracking_id
		     WHERE member_run.logical_tracking_id = seed.logical_tracking_id
		       AND member_segment.start_time_ms = (
		         SELECT MAX(latest_segment.start_time_ms)
		         FROM source_service_run AS latest_run
		         JOIN session_segment AS latest_segment
		           ON latest_segment.id = latest_run.session_segment_id
		          AND latest_segment.service_run_id = latest_run.service_run_id
		          AND latest_segment.logical_tracking_id = latest_run.logical_tracking_id
		         WHERE latest_run.logical_tracking_id = seed.logical_tracking_id
		       )
		    ) AS logical_recency_segment_id
		  FROM logical_seed AS seed
		)
		SELECT * FROM ranked_seed
		WHERE :beforeStartTimeMs IS NULL
		   OR logical_recency_start_ms < :beforeStartTimeMs
		   OR (logical_recency_start_ms = :beforeStartTimeMs
		       AND logical_recency_segment_id < COALESCE(:beforeSegmentId, 9223372036854775807))
		ORDER BY logical_recency_start_ms DESC, logical_recency_segment_id DESC
		LIMIT :limit
		""",
	)
	suspend fun logicalHistoryCandidatePage(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		sourceKind: Int,
		capturePurposeMask: Long,
		limit: Int,
		beforeStartTimeMs: Long?,
		beforeSegmentId: Long?,
	): List<WifiLogicalHistoryCandidate>

	@Query(
		"SELECT * FROM wifi_captured_fact_cursor WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion AND " +
			"(service_run_id IN (:serviceRunIds) OR logical_tracking_id IN (:logicalTrackingIds)) " +
			"ORDER BY logical_fact_id LIMIT :limit",
	)
	suspend fun historyCursorsForScopes(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		serviceRunIds: List<String>,
		logicalTrackingIds: List<String>,
		limit: Int,
	): List<WifiCapturedFactCursorEntity>

	/** Complete cursor-carried correction lineages plus their finite direct aggregate owners. */
	@Query(
		"""
		SELECT * FROM wifi_captured_fact_revision AS fact
		WHERE (
		 fact.logical_fact_id IN (
		  SELECT fact_cursor.logical_fact_id
		  FROM wifi_captured_fact_cursor AS fact_cursor
		  WHERE fact_cursor.service_run_id IN (:serviceRunIds)
		     OR fact_cursor.logical_tracking_id IN (:logicalTrackingIds)
		 )
		 OR fact.logical_fact_id IN (
		  SELECT dependent.aggregate_owner_logical_fact_id
		  FROM wifi_captured_fact_revision AS dependent
		  JOIN wifi_captured_fact_cursor AS dependent_cursor
		    ON dependent_cursor.writer_projection_id = dependent.writer_projection_id
		   AND dependent_cursor.writer_projection_version = dependent.writer_projection_version
		   AND dependent_cursor.logical_fact_id = dependent.logical_fact_id
		   AND dependent_cursor.latest_semantic_revision = dependent.semantic_revision
		   AND dependent_cursor.latest_mutation_id = dependent.mutation_id
		   AND dependent_cursor.latest_effect_checksum = dependent.effect_checksum
		   AND dependent_cursor.latest_source_admission_ordinal = dependent.source_admission_ordinal
		  WHERE dependent.aggregate_owner_logical_fact_id IS NOT NULL
		    AND (dependent_cursor.service_run_id IN (:serviceRunIds)
		      OR dependent_cursor.logical_tracking_id IN (:logicalTrackingIds))
		 )
		)
		AND (
		  :afterWriterProjectionId IS NULL
		  OR writer_projection_id > :afterWriterProjectionId
		  OR (writer_projection_id = :afterWriterProjectionId AND (
		    writer_projection_version > COALESCE(:afterWriterProjectionVersion, -1)
		    OR (writer_projection_version = COALESCE(:afterWriterProjectionVersion, -1) AND (
		      logical_fact_id > COALESCE(:afterLogicalFactId, '')
		      OR (logical_fact_id = COALESCE(:afterLogicalFactId, '') AND
		          semantic_revision > COALESCE(:afterSemanticRevision, -1))
		    ))
		  ))
		)
		ORDER BY writer_projection_id, writer_projection_version, logical_fact_id, semantic_revision
		LIMIT :limit
		""",
	)
	suspend fun historyRevisionPage(
		serviceRunIds: List<String>,
		logicalTrackingIds: List<String>,
		limit: Int,
		afterWriterProjectionId: String?,
		afterWriterProjectionVersion: Int?,
		afterLogicalFactId: String?,
		afterSemanticRevision: Long?,
	): List<WifiCapturedFactRevisionEntity>

	@Query(
		"SELECT * FROM wifi_captured_fact_cursor WHERE logical_fact_id IN (:logicalFactIds) " +
			"ORDER BY writer_projection_id, writer_projection_version, logical_fact_id LIMIT :limit",
	)
	suspend fun historyCursors(
		logicalFactIds: List<String>,
		limit: Int,
	): List<WifiCapturedFactCursorEntity>

	@Query(
		"SELECT generation.* FROM wifi_capture_deletion_generation AS generation " +
			"JOIN source_service_run AS run ON run.logical_tracking_id = generation.logical_tracking_id " +
			"AND run.service_run_id = generation.service_run_id " +
			"WHERE generation.logical_tracking_id IN (:logicalTrackingIds) " +
			"AND generation.service_run_id IN (:serviceRunIds) " +
			"ORDER BY generation.logical_tracking_id, generation.service_run_id LIMIT :limit",
	)
	suspend fun historyDeletionGenerations(
		logicalTrackingIds: List<String>,
		serviceRunIds: List<String>,
		limit: Int,
	): List<WifiCaptureDeletionGenerationEntity>

	@Query("SELECT * FROM acquisition_plan_revision WHERE revision IN (:revisions) ORDER BY revision LIMIT :limit")
	suspend fun historyAcquisitionPlanRevisions(
		revisions: List<Long>,
		limit: Int,
	): List<AcquisitionPlanRevisionEntity>

	@Query(
		"SELECT * FROM source_desired_plan WHERE source_kind = :sourceKind " +
			"AND revision IN (:revisions) ORDER BY revision LIMIT :limit",
	)
	suspend fun historyDesiredPlans(
		sourceKind: Int,
		revisions: List<Long>,
		limit: Int,
	): List<SourceDesiredPlanEntity>

	@Query(
		"SELECT DISTINCT policy.* FROM source_policy AS policy " +
			"JOIN session_manifest_version AS manifest " +
			"ON manifest.source_policy_revision = policy.policy_revision " +
			"WHERE policy.source_kind = :sourceKind AND manifest.service_run_id IN (:serviceRunIds) " +
			"ORDER BY policy.policy_revision LIMIT :limit",
	)
	suspend fun historyPolicies(
		sourceKind: Int,
		serviceRunIds: List<String>,
		limit: Int,
	): List<SourcePolicyEntity>

	@Query(
		"SELECT * FROM source_consent_epoch WHERE source_kind = :sourceKind AND purpose = :purpose " +
			"AND epoch IN (:epochs) ORDER BY epoch LIMIT :limit",
	)
	suspend fun historyConsentEpochs(
		sourceKind: Int,
		purpose: String,
		epochs: List<Long>,
		limit: Int,
	): List<SourceConsentEpochEntity>

	@Query(
		"SELECT * FROM provider_registration_generation WHERE source_kind = :sourceKind " +
			"AND registration_generation IN (:registrationGenerations) " +
			"ORDER BY registration_generation LIMIT :limit",
	)
	suspend fun historyProviderRegistrations(
		sourceKind: Int,
		registrationGenerations: List<Long>,
		limit: Int,
	): List<ProviderRegistrationGenerationEntity>

	@Query(
		"SELECT * FROM source_authorization WHERE source_kind = :sourceKind " +
			"AND registration_generation IN (:registrationGenerations) " +
			"ORDER BY registration_generation, effective_elapsed_realtime_nanos, " +
			"authorization_revision, member_id LIMIT :limit",
	)
	suspend fun historyAuthorizations(
		sourceKind: Int,
		registrationGenerations: List<Long>,
		limit: Int,
	): List<SourceAuthorizationEntity>

	@Query("SELECT * FROM source_demand WHERE demand_id IN (:demandIds) ORDER BY demand_id LIMIT :limit")
	suspend fun historyDemands(
		demandIds: List<String>,
		limit: Int,
	): List<SourceDemandEntity>

	@Query(
		"SELECT * FROM lifecycle_desired_action WHERE source_kind = :sourceKind " +
			"AND service_run_id IN (:serviceRunIds) AND action_family = 'SOURCE_RUNTIME' " +
			"AND desired_state = 'STARTED' ORDER BY action_revision LIMIT :limit",
	)
	suspend fun historyStartActions(
		sourceKind: Int,
		serviceRunIds: List<String>,
		limit: Int,
	): List<LifecycleDesiredActionEntity>

	@Query(
		"SELECT * FROM source_event_wal WHERE source_kind = :sourceKind " +
			"AND service_run_id IN (:serviceRunIds) AND session_manifest_revision IS NOT NULL " +
			"AND capture_consent_epoch IS NOT NULL " +
			"AND (authorization_purpose_eligibility_mask & :capturePurposeMask) != 0 " +
			"ORDER BY service_run_id, admission_ordinal LIMIT :limit",
	)
	suspend fun historyAdmissionWal(
		sourceKind: Int,
		serviceRunIds: List<String>,
		capturePurposeMask: Long,
		limit: Int,
	): List<SourceEventWalEntity>

	@Query(
		"SELECT * FROM source_deletion_fence WHERE source_kind = :sourceKind " +
			"AND purpose = :purpose AND scope_kind = :scopeKind " +
			"AND scope_identity_digest IN (:scopeIdentityDigests) " +
			"ORDER BY scope_identity_digest LIMIT :limit",
	)
	suspend fun historyDeletionFences(
		sourceKind: Int,
		purpose: String,
		scopeKind: String,
		scopeIdentityDigests: List<String>,
		limit: Int,
	): List<SourceDeletionFenceEntity>

	@Query(
		"""
		SELECT lane.*
		FROM source_product_projection_lane AS lane
		WHERE lane.source_kind = :sourceKind
		  AND EXISTS (
			SELECT 1
			FROM session_manifest_source AS source
			JOIN session_manifest_version AS manifest
			  ON manifest.logical_tracking_id = source.logical_tracking_id
			 AND manifest.manifest_revision = source.manifest_revision
			WHERE manifest.service_run_id IN (:serviceRunIds)
			  AND source.source_kind = :sourceKind
			  AND source.purpose = :capturePurpose
			  AND source.persistence_eligible = 1
			  AND source.writer_projection_id = lane.projection_id
			  AND source.writer_projection_version = lane.projection_version
			  AND source.writer_binding_generation = lane.binding_generation
		  )
		ORDER BY lane.binding_generation, lane.projection_id, lane.projection_version
		LIMIT :limit
		""",
	)
	suspend fun historyProductLanes(
		sourceKind: Int,
		capturePurpose: String,
		serviceRunIds: List<String>,
		limit: Int,
	): List<SourceProductProjectionLaneEntity>

	/** Stable bounded keyset over every retained Wi-Fi fact revision. */
	@Query(
		"SELECT * FROM wifi_captured_fact_revision " +
			"WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion AND (" +
			":afterLogicalFactId IS NULL OR logical_fact_id > :afterLogicalFactId OR " +
			"(logical_fact_id = :afterLogicalFactId AND semantic_revision > :afterSemanticRevision)) " +
			"ORDER BY logical_fact_id, semantic_revision LIMIT :limit",
	)
	suspend fun maintenanceRevisionPage(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		afterLogicalFactId: String?,
		afterSemanticRevision: Long?,
		limit: Int,
	): List<WifiCapturedFactRevisionEntity>

	/** Stable bounded keyset used to prove one current head per retained Wi-Fi lineage. */
	@Query(
		"SELECT * FROM wifi_captured_fact_cursor " +
			"WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND (:afterLogicalFactId IS NULL OR logical_fact_id > :afterLogicalFactId) " +
			"ORDER BY logical_fact_id LIMIT :limit",
	)
	suspend fun maintenanceCursorPage(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		afterLogicalFactId: String?,
		limit: Int,
	): List<WifiCapturedFactCursorEntity>

	/** Stable bounded keyset over source-local no-resurrection generations. */
	@Query(
		"SELECT * FROM wifi_capture_deletion_generation WHERE " +
			":afterLogicalTrackingId IS NULL OR logical_tracking_id > :afterLogicalTrackingId OR " +
			"(logical_tracking_id = :afterLogicalTrackingId AND service_run_id > :afterServiceRunId) " +
			"ORDER BY logical_tracking_id, service_run_id LIMIT :limit",
	)
	suspend fun maintenanceDeletionGenerationPage(
		afterLogicalTrackingId: String?,
		afterServiceRunId: String?,
		limit: Int,
	): List<WifiCaptureDeletionGenerationEntity>

	@Query(
		"SELECT COUNT(*) FROM wifi_captured_fact_revision WHERE " +
			"writer_projection_id != :writerProjectionId " +
			"OR writer_projection_version != :writerProjectionVersion",
	)
	suspend fun unsupportedRevisionCount(
		writerProjectionId: String,
		writerProjectionVersion: Int,
	): Long

	@Query(
		"SELECT COUNT(*) FROM wifi_captured_fact_cursor WHERE " +
			"writer_projection_id != :writerProjectionId " +
			"OR writer_projection_version != :writerProjectionVersion",
	)
	suspend fun unsupportedCursorCount(
		writerProjectionId: String,
		writerProjectionVersion: Int,
	): Long

	/** Only direct session capture is retired by captured-Wi-Fi consent deletion. */
	@Query(
		"SELECT * FROM source_demand WHERE source_kind = :sourceKind " +
			"AND purpose = :capturePurpose AND status IN ('ACTIVE', 'RETIRING', 'BLOCKED') " +
			"ORDER BY demand_id LIMIT :limit",
	)
	suspend fun captureDemandsForDeletion(
		sourceKind: Int,
		capturePurpose: String,
		limit: Int,
	): List<SourceDemandEntity>

	/** Exact bounded demand vector from which the current Wi-Fi authorization is derived. */
	@Query(
		"SELECT * FROM source_demand WHERE source_kind = :sourceKind AND status = 'ACTIVE' " +
			"ORDER BY purpose, consumer_id, demand_id LIMIT :limit",
	)
	suspend fun activeDemandsForDeletion(
		sourceKind: Int,
		limit: Int,
	): List<SourceDemandEntity>

	@Query(
		"UPDATE source_demand SET status = 'RETIRED', " +
			"retire_boot_id = COALESCE(retire_boot_id, :bootId), " +
			"retire_elapsed_realtime_nanos = COALESCE(retire_elapsed_realtime_nanos, :elapsedRealtimeNanos), " +
			"retired_at_ms = COALESCE(retired_at_ms, :wallTimeMs) " +
			"WHERE source_kind = :sourceKind AND purpose = :capturePurpose " +
			"AND status IN ('RETIRING', 'BLOCKED')",
	)
	suspend fun retireCaptureDemandsForDeletion(
		sourceKind: Int,
		capturePurpose: String,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
	): Int

	/** Every nonterminal Wi-Fi registration represents active or pending callback work. */
	@Query(
		"SELECT * FROM provider_registration_generation WHERE source_kind = :sourceKind " +
			"AND status IN ('RESERVED', 'ACTIVE', 'RETIRING') " +
			"ORDER BY registration_generation LIMIT :limit",
	)
	suspend fun registrationsForDeletion(
		sourceKind: Int,
		limit: Int,
	): List<ProviderRegistrationGenerationEntity>

	@Query(
		"SELECT * FROM source_authorization WHERE source_kind = :sourceKind " +
			"AND registration_generation = :registrationGeneration " +
			"AND authorization_revision = :authorizationRevision ORDER BY member_id LIMIT :limit",
	)
	suspend fun maintenanceAuthorizationMembers(
		sourceKind: Int,
		registrationGeneration: Long,
		authorizationRevision: Long,
		limit: Int,
	): List<SourceAuthorizationEntity>

	@Query(
		"SELECT COALESCE(MAX(authorization_revision), 0) FROM source_authorization " +
			"WHERE source_kind = :sourceKind AND registration_generation = :registrationGeneration",
	)
	suspend fun maximumRegistrationAuthorizationRevision(
		sourceKind: Int,
		registrationGeneration: Long,
	): Long

	@Query(
		"SELECT * FROM source_authorization WHERE source_kind = :sourceKind " +
			"AND registration_generation = :registrationGeneration AND effective_boot_id = :bootId " +
			"AND authorization_revision = (SELECT authorization_revision FROM source_authorization " +
			"WHERE source_kind = :sourceKind AND registration_generation = :registrationGeneration " +
			"AND effective_boot_id = :bootId " +
			"AND effective_elapsed_realtime_nanos <= :observedElapsedRealtimeNanos " +
			"ORDER BY effective_elapsed_realtime_nanos DESC, authorization_revision DESC LIMIT 1) " +
			"ORDER BY member_id LIMIT :limit",
	)
	suspend fun maintenanceAuthorizationAt(
		sourceKind: Int,
		registrationGeneration: Long,
		bootId: String,
		observedElapsedRealtimeNanos: Long,
		limit: Int,
	): List<SourceAuthorizationEntity>

	@Query(
		"SELECT * FROM source_authorization WHERE source_kind = :sourceKind " +
			"AND registration_generation = :registrationGeneration " +
			"AND authorization_revision = (SELECT authorization_revision FROM source_authorization " +
			"WHERE source_kind = :sourceKind AND registration_generation = :registrationGeneration " +
			"AND authorization_revision > :authorizationRevision " +
			"ORDER BY effective_elapsed_realtime_nanos, authorization_revision LIMIT 1) " +
			"ORDER BY member_id LIMIT :limit",
	)
	suspend fun maintenanceNextAuthorizationMembers(
		sourceKind: Int,
		registrationGeneration: Long,
		authorizationRevision: Long,
		limit: Int,
	): List<SourceAuthorizationEntity>

	@Query("SELECT COUNT(*) FROM source_event_wal WHERE source_kind = :sourceKind")
	suspend fun maintenanceWalCount(sourceKind: Int): Long

	@Query(
		"SELECT admission_ordinal, event_id FROM source_event_wal WHERE source_kind = :sourceKind " +
			"AND admission_ordinal > :afterAdmissionOrdinal " +
			"ORDER BY admission_ordinal LIMIT :limit",
	)
	suspend fun maintenanceWalKeys(
		sourceKind: Int,
		afterAdmissionOrdinal: Long,
		limit: Int,
	): List<WifiWalMaintenanceKey>

	/** Payload size guard used before maintenance loads one exact retained Wi-Fi WAL. */
	@Query("SELECT length(payload) FROM source_event_wal WHERE event_id = :eventId LIMIT 1")
	suspend fun maintenanceWalPayloadByteCount(eventId: String): Long?

	@Query(
		"DELETE FROM wifi_captured_fact_cursor WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND logical_fact_id IN (:logicalFactIds)",
	)
	suspend fun deleteExactCursors(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalFactIds: List<String>,
	): Int

	@Query(
		"DELETE FROM wifi_captured_fact_revision WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND logical_fact_id IN (:logicalFactIds)",
	)
	suspend fun deleteExactRevisionLineages(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalFactIds: List<String>,
	): Int

	@Query("SELECT COUNT(*) FROM wifi_captured_fact_revision")
	suspend fun revisionCount(): Long

	@Query("SELECT COUNT(*) FROM wifi_captured_fact_cursor")
	suspend fun cursorCount(): Long

	@Query("SELECT COUNT(*) FROM wifi_capture_deletion_generation")
	suspend fun deletionGenerationCount(): Long

	@Query("DELETE FROM wifi_captured_fact_cursor")
	fun deleteAllCursors()

	@Query("DELETE FROM wifi_captured_fact_revision")
	fun deleteAllRevisions()

	@Query("DELETE FROM wifi_capture_deletion_generation")
	fun deleteAllDeletionGenerations()
}

data class WifiLogicalHistoryCandidate(
	@Embedded val segment: SessionSegment,
	@ColumnInfo(name = "logical_recency_start_ms") val logicalRecencyStartMs: Long,
	@ColumnInfo(name = "logical_recency_segment_id") val logicalRecencySegmentId: Long,
)
