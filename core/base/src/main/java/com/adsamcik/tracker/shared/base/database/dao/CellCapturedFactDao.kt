package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.AcquisitionPlanRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.CellCaptureDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedDeletedRunEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedEntryDeletionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactCursorEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity

data class CellWalMaintenanceKey(
	@ColumnInfo(name = "admission_ordinal") val admissionOrdinal: Long,
	@ColumnInfo(name = "event_id") val eventId: String,
)

/** Narrow source-local persistence boundary for dormant captured Cell facts. */
@Dao
interface CellCapturedFactDao {
	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertEntryDeletionReceipt(receipt: CellCapturedEntryDeletionReceiptEntity)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertDeletedRuns(runs: List<CellCapturedDeletedRunEntity>)

	@Query(
		"SELECT * FROM cell_captured_entry_deletion_receipt " +
			"WHERE logical_tracking_id = :logicalTrackingId LIMIT 1",
	)
	suspend fun entryDeletionReceipt(
		logicalTrackingId: String,
	): CellCapturedEntryDeletionReceiptEntity?

	@Query(
		"SELECT * FROM cell_captured_deleted_run WHERE logical_tracking_id = :logicalTrackingId " +
			"ORDER BY start_time_ms, session_segment_id, service_run_id LIMIT :limit",
	)
	suspend fun deletedRuns(
		logicalTrackingId: String,
		limit: Int,
	): List<CellCapturedDeletedRunEntity>

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertRevision(entity: CellCapturedFactRevisionEntity): Long

	@Query(
		"SELECT * FROM cell_captured_fact_revision WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND logical_fact_id = :logicalFactId AND semantic_revision = :semanticRevision LIMIT 1",
	)
	suspend fun revision(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalFactId: String,
		semanticRevision: Long,
	): CellCapturedFactRevisionEntity?

	@Query(
		"SELECT * FROM cell_captured_fact_revision WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND logical_fact_id = :logicalFactId " +
			"ORDER BY semantic_revision DESC LIMIT :limit",
	)
	suspend fun revisionsForFact(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalFactId: String,
		limit: Int,
	): List<CellCapturedFactRevisionEntity>

	@Query("SELECT COUNT(*) FROM cell_captured_fact_revision")
	suspend fun revisionCount(): Long

	/** Stable bounded keyset over every retained Cell fact revision. */
	@Query(
		"SELECT * FROM cell_captured_fact_revision " +
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
	): List<CellCapturedFactRevisionEntity>

	/**
	 * Complete bounded fact side of one selected portable logical/run closure.
	 *
	 * The source/run pair remains provisional until the fact and its WAL are authenticated. The
	 * registration generation and authenticated lane admission window therefore supply an independent
	 * candidate relation for a fact whose two retained membership scalars were both changed, including
	 * a generation whose completeness row claims zero callbacks. Admission bounds discover candidates
	 * only; they never establish ownership.
	 */
	@Query(
		"""
		WITH requested_run AS (
			SELECT service_run_id, logical_tracking_id
			FROM source_service_run
			WHERE service_run_id IN (:capturedServiceRunIds)
		), requested_generation AS (
			SELECT DISTINCT completeness.registration_generation
			FROM requested_run
			JOIN source_session_completeness AS completeness
			  ON completeness.service_run_id = requested_run.service_run_id
			 AND completeness.logical_tracking_id = requested_run.logical_tracking_id
			WHERE completeness.source_kind = :sourceKind
			  AND completeness.registration_generation > 0
		), candidate_wal AS (
			SELECT wal.admission_ordinal, wal.event_id
			FROM source_event_wal AS wal
			WHERE wal.source_kind = :sourceKind
			  AND (wal.logical_tracking_id = :logicalTrackingId OR wal.service_run_id IN (:serviceRunIds))
			UNION
			SELECT wal.admission_ordinal, wal.event_id
			FROM source_event_wal AS wal
			WHERE wal.source_kind = :sourceKind
			  AND wal.admission_ordinal > :activationFloorOrdinal
			  AND wal.admission_ordinal <= :admissionCeilingOrdinal
			  AND EXISTS (
			    SELECT 1
			    FROM requested_generation
			    WHERE wal.registration_generation = requested_generation.registration_generation
			  )
		), candidate_fact AS (
			SELECT writer_projection_id, writer_projection_version, logical_fact_id
			FROM cell_captured_fact_revision
			WHERE logical_tracking_id = :logicalTrackingId OR service_run_id IN (:serviceRunIds)
			UNION
			SELECT revision.writer_projection_id,
			       revision.writer_projection_version,
			       revision.logical_fact_id
			FROM cell_captured_fact_revision AS revision
			JOIN candidate_wal
			  ON candidate_wal.event_id = revision.source_event_id
			  OR candidate_wal.admission_ordinal = revision.source_admission_ordinal
		)
		SELECT revision.*
		FROM cell_captured_fact_revision AS revision
		JOIN candidate_fact
		  ON candidate_fact.writer_projection_id = revision.writer_projection_id
		 AND candidate_fact.writer_projection_version = revision.writer_projection_version
		 AND candidate_fact.logical_fact_id = revision.logical_fact_id
		ORDER BY revision.logical_fact_id, revision.semantic_revision
		LIMIT :limit
		""",
	)
	suspend fun portableRevisionClosure(
		sourceKind: Int,
		logicalTrackingId: String,
		serviceRunIds: List<String>,
		capturedServiceRunIds: List<String>,
		activationFloorOrdinal: Long,
		admissionCeilingOrdinal: Long,
		limit: Int,
	): List<CellCapturedFactRevisionEntity>

	/** Stable bounded keyset used to prove one current head per retained Cell lineage. */
	@Query(
		"SELECT * FROM cell_captured_fact_cursor " +
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
	): List<CellCapturedFactCursorEntity>

	/** Complete bounded cursor side for the same independently discovered portable fact closure. */
	@Query(
		"""
		WITH requested_run AS (
			SELECT service_run_id, logical_tracking_id
			FROM source_service_run
			WHERE service_run_id IN (:capturedServiceRunIds)
		), requested_generation AS (
			SELECT DISTINCT completeness.registration_generation
			FROM requested_run
			JOIN source_session_completeness AS completeness
			  ON completeness.service_run_id = requested_run.service_run_id
			 AND completeness.logical_tracking_id = requested_run.logical_tracking_id
			WHERE completeness.source_kind = :sourceKind
			  AND completeness.registration_generation > 0
		), candidate_wal AS (
			SELECT wal.admission_ordinal, wal.event_id
			FROM source_event_wal AS wal
			WHERE wal.source_kind = :sourceKind
			  AND (wal.logical_tracking_id = :logicalTrackingId OR wal.service_run_id IN (:serviceRunIds))
			UNION
			SELECT wal.admission_ordinal, wal.event_id
			FROM source_event_wal AS wal
			WHERE wal.source_kind = :sourceKind
			  AND wal.admission_ordinal > :activationFloorOrdinal
			  AND wal.admission_ordinal <= :admissionCeilingOrdinal
			  AND EXISTS (
			    SELECT 1
			    FROM requested_generation
			    WHERE wal.registration_generation = requested_generation.registration_generation
			  )
		), candidate_fact AS (
			SELECT writer_projection_id, writer_projection_version, logical_fact_id
			FROM cell_captured_fact_revision
			WHERE logical_tracking_id = :logicalTrackingId OR service_run_id IN (:serviceRunIds)
			UNION
			SELECT revision.writer_projection_id,
			       revision.writer_projection_version,
			       revision.logical_fact_id
			FROM cell_captured_fact_revision AS revision
			JOIN candidate_wal
			  ON candidate_wal.event_id = revision.source_event_id
			  OR candidate_wal.admission_ordinal = revision.source_admission_ordinal
		)
		SELECT fact_cursor.*
		FROM cell_captured_fact_cursor AS fact_cursor
		WHERE fact_cursor.logical_tracking_id = :logicalTrackingId
		   OR fact_cursor.service_run_id IN (:serviceRunIds)
		   OR EXISTS (
		     SELECT 1
		     FROM candidate_fact
		     WHERE candidate_fact.writer_projection_id = fact_cursor.writer_projection_id
		       AND candidate_fact.writer_projection_version = fact_cursor.writer_projection_version
		       AND candidate_fact.logical_fact_id = fact_cursor.logical_fact_id
		   )
		ORDER BY fact_cursor.logical_fact_id
		LIMIT :limit
		""",
	)
	suspend fun portableCursorClosure(
		sourceKind: Int,
		logicalTrackingId: String,
		serviceRunIds: List<String>,
		capturedServiceRunIds: List<String>,
		activationFloorOrdinal: Long,
		admissionCeilingOrdinal: Long,
		limit: Int,
	): List<CellCapturedFactCursorEntity>

	/** Stable bounded keyset over source-local no-resurrection generations. */
	@Query(
		"SELECT * FROM cell_capture_deletion_generation WHERE " +
			":afterLogicalTrackingId IS NULL OR logical_tracking_id > :afterLogicalTrackingId OR " +
			"(logical_tracking_id = :afterLogicalTrackingId AND service_run_id > :afterServiceRunId) " +
			"ORDER BY logical_tracking_id, service_run_id LIMIT :limit",
	)
	suspend fun maintenanceDeletionGenerationPage(
		afterLogicalTrackingId: String?,
		afterServiceRunId: String?,
		limit: Int,
	): List<CellCaptureDeletionGenerationEntity>

	/** Complete bounded deletion-generation side of one selected portable logical/run closure. */
	@Query(
		"SELECT * FROM cell_capture_deletion_generation WHERE " +
			"(logical_tracking_id = :logicalTrackingId OR service_run_id IN (:serviceRunIds)) " +
			"ORDER BY logical_tracking_id, service_run_id LIMIT :limit",
	)
	suspend fun portableDeletionGenerationClosure(
		logicalTrackingId: String,
		serviceRunIds: List<String>,
		limit: Int,
	): List<CellCaptureDeletionGenerationEntity>

	@Query(
		"SELECT COUNT(*) FROM cell_captured_fact_revision WHERE " +
			"writer_projection_id != :writerProjectionId " +
			"OR writer_projection_version != :writerProjectionVersion",
	)
	suspend fun unsupportedRevisionCount(
		writerProjectionId: String,
		writerProjectionVersion: Int,
	): Long

	@Query(
		"SELECT COUNT(*) FROM cell_captured_fact_cursor WHERE " +
			"writer_projection_id != :writerProjectionId " +
			"OR writer_projection_version != :writerProjectionVersion",
	)
	suspend fun unsupportedCursorCount(
		writerProjectionId: String,
		writerProjectionVersion: Int,
	): Long

	/** Only direct session capture is retired by captured-Cell consent deletion. */
	@Query(
		"SELECT * FROM source_demand WHERE source_kind = :sourceKind " +
			"AND purpose = 'SESSION_CAPTURE' AND status IN ('ACTIVE', 'RETIRING', 'BLOCKED') " +
			"ORDER BY demand_id LIMIT :limit",
	)
	suspend fun directCellCaptureDemandsForDeletion(
		sourceKind: Int,
		limit: Int,
	): List<SourceDemandEntity>

	@Query(
		"SELECT * FROM source_demand WHERE source_kind = :sourceKind " +
			"AND purpose = 'SESSION_CAPTURE' " +
			"AND (logical_tracking_id = :logicalTrackingId OR service_run_id IN (:serviceRunIds)) " +
			"AND status IN ('ACTIVE', 'RETIRING', 'BLOCKED') ORDER BY demand_id LIMIT :limit",
	)
	suspend fun selectedCellCaptureDemandsForDeletion(
		sourceKind: Int,
		logicalTrackingId: String,
		serviceRunIds: List<String>,
		limit: Int,
	): List<SourceDemandEntity>

	/** Exact bounded demand vector from which the current Cell authorization is derived. */
	@Query(
		"SELECT * FROM source_demand WHERE source_kind = :sourceKind AND status = 'ACTIVE' " +
			"ORDER BY purpose, consumer_id, demand_id LIMIT :limit",
	)
	suspend fun activeCellDemandsForDeletion(
		sourceKind: Int,
		limit: Int,
	): List<SourceDemandEntity>

	@Query(
		"UPDATE source_demand SET status = 'RETIRED', " +
			"retire_boot_id = COALESCE(retire_boot_id, :bootId), " +
			"retire_elapsed_realtime_nanos = COALESCE(retire_elapsed_realtime_nanos, :elapsedRealtimeNanos), " +
			"retired_at_ms = COALESCE(retired_at_ms, :wallTimeMs) " +
			"WHERE source_kind = :sourceKind AND purpose = 'SESSION_CAPTURE' " +
			"AND status IN ('RETIRING', 'BLOCKED')",
	)
	suspend fun retireCellCaptureDemandsForDeletion(
		sourceKind: Int,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
	): Int

	/** Every nonterminal Cell registration represents active or pending callback work. */
	@Query(
		"SELECT * FROM provider_registration_generation WHERE source_kind = :sourceKind " +
			"AND status IN ('RESERVED', 'ACTIVE', 'RETIRING') " +
			"ORDER BY registration_generation LIMIT :limit",
	)
	suspend fun cellRegistrationsForDeletion(
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
			"AND authorization_revision = (SELECT MIN(authorization_revision) " +
			"FROM source_authorization WHERE source_kind = :sourceKind " +
			"AND registration_generation = :registrationGeneration " +
			"AND authorization_revision > :authorizationRevision) ORDER BY member_id LIMIT :limit",
	)
	suspend fun maintenanceNextAuthorizationMembers(
		sourceKind: Int,
		registrationGeneration: Long,
		authorizationRevision: Long,
		limit: Int,
	): List<SourceAuthorizationEntity>

	@Query(
		"SELECT * FROM source_desired_plan WHERE revision = :revision " +
			"ORDER BY source_kind LIMIT :limit",
	)
	suspend fun maintenanceDesiredPlans(
		revision: Long,
		limit: Int,
	): List<SourceDesiredPlanEntity>

	@Query(
		"SELECT fact.* FROM cell_captured_fact_revision AS fact " +
			"INNER JOIN cell_captured_fact_cursor AS fact_cursor ON " +
			"fact_cursor.writer_projection_id = fact.writer_projection_id AND " +
			"fact_cursor.writer_projection_version = fact.writer_projection_version AND " +
			"fact_cursor.logical_fact_id = fact.logical_fact_id AND " +
			"fact_cursor.latest_semantic_revision = fact.semantic_revision AND " +
			"fact_cursor.latest_mutation_id = fact.mutation_id AND " +
			"fact_cursor.latest_effect_checksum = fact.effect_checksum AND " +
			"fact_cursor.latest_source_admission_ordinal = fact.source_admission_ordinal " +
			"WHERE fact.writer_projection_id = :writerProjectionId AND " +
			"fact.writer_projection_version = :writerProjectionVersion AND " +
			"fact.logical_tracking_id = :logicalTrackingId AND " +
			"fact.service_run_id = :serviceRunId AND " +
			"fact.session_segment_id = :sessionSegmentId AND " +
			"fact.collected_data_epoch = :collectedDataEpoch AND " +
			"fact.scope_deletion_generation = :scopeDeletionGeneration AND " +
			"fact.source_admission_ordinal < :beforeSourceAdmissionOrdinal " +
			"ORDER BY fact.source_admission_ordinal DESC LIMIT 1",
	)
	@Suppress("LongParameterList")
	suspend fun latestEffectiveBefore(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalTrackingId: String,
		serviceRunId: String,
		sessionSegmentId: Long,
		collectedDataEpoch: Long,
		scopeDeletionGeneration: Long,
		beforeSourceAdmissionOrdinal: Long,
	): CellCapturedFactRevisionEntity?

	@Query(
		"SELECT * FROM cell_captured_fact_cursor WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND logical_fact_id = :logicalFactId LIMIT 1",
	)
	suspend fun cursor(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalFactId: String,
	): CellCapturedFactCursorEntity?

	@Query("SELECT COUNT(*) FROM cell_captured_fact_cursor")
	suspend fun cursorCount(): Long

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
	): List<CellWalMaintenanceKey>

	/**
	 * Complete bounded WAL side of one selected portable logical/run closure. The second UNION arm
	 * is independent of both WAL membership scalars and remains source-filtered before LIMIT.
	 */
	@Query(
		"""
		WITH requested_run AS (
			SELECT service_run_id, logical_tracking_id
			FROM source_service_run
			WHERE service_run_id IN (:capturedServiceRunIds)
		), requested_generation AS (
			SELECT DISTINCT completeness.registration_generation
			FROM requested_run
			JOIN source_session_completeness AS completeness
			  ON completeness.service_run_id = requested_run.service_run_id
			 AND completeness.logical_tracking_id = requested_run.logical_tracking_id
			WHERE completeness.source_kind = :sourceKind
			  AND completeness.registration_generation > 0
		), candidate_wal AS (
			SELECT wal.admission_ordinal, wal.event_id
			FROM source_event_wal AS wal
			WHERE wal.source_kind = :sourceKind
			  AND (wal.logical_tracking_id = :logicalTrackingId OR wal.service_run_id IN (:serviceRunIds))
			UNION
			SELECT wal.admission_ordinal, wal.event_id
			FROM source_event_wal AS wal
			WHERE wal.source_kind = :sourceKind
			  AND wal.admission_ordinal > :activationFloorOrdinal
			  AND wal.admission_ordinal <= :admissionCeilingOrdinal
			  AND EXISTS (
			    SELECT 1
			    FROM requested_generation
			    WHERE wal.registration_generation = requested_generation.registration_generation
			  )
		)
		SELECT admission_ordinal, event_id
		FROM candidate_wal
		ORDER BY admission_ordinal
		LIMIT :limit
		""",
	)
	suspend fun portableWalClosureKeys(
		sourceKind: Int,
		logicalTrackingId: String,
		serviceRunIds: List<String>,
		capturedServiceRunIds: List<String>,
		activationFloorOrdinal: Long,
		admissionCeilingOrdinal: Long,
		limit: Int,
	): List<CellWalMaintenanceKey>

	/** Payload size guard used before maintenance loads and hashes one exact retained Cell WAL. */
	@Query("SELECT length(payload) FROM source_event_wal WHERE event_id = :eventId LIMIT 1")
	suspend fun maintenanceWalPayloadByteCount(eventId: String): Long?

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertCursor(entity: CellCapturedFactCursorEntity): Long

	@Query(
		"UPDATE cell_captured_fact_cursor SET " +
			"latest_semantic_revision = :newSemanticRevision, latest_mutation_id = :newMutationId, " +
			"latest_effect_checksum = :newEffectChecksum, " +
			"latest_source_admission_ordinal = :newSourceAdmissionOrdinal, " +
			"cursor_revision = :newCursorRevision, updated_at_ms = :updatedAtMs " +
			"WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND logical_fact_id = :logicalFactId AND logical_tracking_id = :logicalTrackingId " +
			"AND service_run_id = :serviceRunId AND session_segment_id = :sessionSegmentId " +
			"AND writer_owner_generation = :writerOwnerGeneration " +
			"AND collected_data_epoch = :collectedDataEpoch " +
			"AND scope_deletion_generation = :scopeDeletionGeneration " +
			"AND latest_semantic_revision = :expectedSemanticRevision " +
			"AND latest_mutation_id = :expectedMutationId " +
			"AND latest_effect_checksum = :expectedEffectChecksum " +
			"AND latest_source_admission_ordinal = :expectedSourceAdmissionOrdinal " +
			"AND cursor_revision = :expectedCursorRevision " +
			"AND :newSemanticRevision = :expectedSemanticRevision + 1 " +
			"AND :newSourceAdmissionOrdinal >= :expectedSourceAdmissionOrdinal " +
			"AND :newCursorRevision = :expectedCursorRevision + 1",
	)
	@Suppress("LongParameterList")
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
		"SELECT * FROM cell_capture_deletion_generation " +
			"WHERE logical_tracking_id = :logicalTrackingId AND service_run_id = :serviceRunId LIMIT 1",
	)
	suspend fun deletionGeneration(
		logicalTrackingId: String,
		serviceRunId: String,
	): CellCaptureDeletionGenerationEntity?

	@Query("SELECT COUNT(*) FROM cell_capture_deletion_generation")
	suspend fun deletionGenerationCount(): Long

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertDeletionGeneration(entity: CellCaptureDeletionGenerationEntity)

	@Query(
		"UPDATE cell_capture_deletion_generation SET generation = :newGeneration, " +
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

	@Query(
		"DELETE FROM cell_captured_fact_cursor WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND logical_fact_id IN (:logicalFactIds)",
	)
	suspend fun deleteExactCursors(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalFactIds: List<String>,
	): Int

	@Query(
		"DELETE FROM cell_captured_fact_revision WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND logical_fact_id IN (:logicalFactIds)",
	)
	suspend fun deleteExactRevisionLineages(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalFactIds: List<String>,
	): Int

	/**
	 * Discovers one cursor-carried segment per logical Cell entry. Cursor scope is the stable carrier;
	 * the current fact head is authenticated later and cannot hide a missing or corrupt lineage.
	 * Presentation sample_count, legacy radio rows, and Location are deliberately absent.
	 */
	@Query(
		"""
		WITH cursor_member AS (
		  SELECT DISTINCT segment.*
		  FROM cell_captured_fact_cursor AS fact_cursor
		  INNER JOIN source_service_run AS run
		    ON run.service_run_id = fact_cursor.service_run_id
		   AND run.logical_tracking_id = fact_cursor.logical_tracking_id
		   AND run.session_segment_id = fact_cursor.session_segment_id
		  INNER JOIN session_segment AS segment
		    ON segment.id = fact_cursor.session_segment_id
		   AND segment.service_run_id = fact_cursor.service_run_id
		   AND segment.logical_tracking_id = fact_cursor.logical_tracking_id
		  WHERE fact_cursor.writer_projection_id = :writerProjectionId
		    AND fact_cursor.writer_projection_version = :writerProjectionVersion
		), intent_member AS (
		  SELECT DISTINCT segment.*
		  FROM source_service_run AS run
		  INNER JOIN session_segment AS segment
		    ON segment.id = run.session_segment_id
		   AND segment.service_run_id = run.service_run_id
		   AND segment.logical_tracking_id = run.logical_tracking_id
		  INNER JOIN session_manifest_version AS manifest
		    ON manifest.service_run_id = run.service_run_id
		   AND manifest.logical_tracking_id = run.logical_tracking_id
		  INNER JOIN session_manifest_source AS source
		    ON source.logical_tracking_id = manifest.logical_tracking_id
		   AND source.manifest_revision = manifest.manifest_revision
		  WHERE source.source_kind = :cellSourceKind
		    AND source.purpose = 'SESSION_CAPTURE'
		    AND source.persistence_eligible = 1
		), candidate_member AS (
		  SELECT * FROM cursor_member
		  UNION
		  SELECT * FROM intent_member
		), logical_seed AS (
		  SELECT member.*
		  FROM candidate_member AS member
		  WHERE NOT EXISTS (
		    SELECT 1 FROM candidate_member AS newer
		    WHERE newer.logical_tracking_id = member.logical_tracking_id
		      AND (newer.start_time_ms > member.start_time_ms OR
		        (newer.start_time_ms = member.start_time_ms AND newer.id > member.id))
		  )
		), ranked_seed AS (
		  SELECT seed.*,
		    (SELECT MAX(member_segment.start_time_ms)
		     FROM source_service_run AS member_run
		     INNER JOIN session_segment AS member_segment
		       ON member_segment.id = member_run.session_segment_id
		      AND member_segment.service_run_id = member_run.service_run_id
		      AND member_segment.logical_tracking_id = member_run.logical_tracking_id
		     WHERE member_run.logical_tracking_id = seed.logical_tracking_id
		    ) AS logical_recency_start_ms,
		    (SELECT MAX(member_segment.id)
		     FROM source_service_run AS member_run
		     INNER JOIN session_segment AS member_segment
		       ON member_segment.id = member_run.session_segment_id
		      AND member_segment.service_run_id = member_run.service_run_id
		      AND member_segment.logical_tracking_id = member_run.logical_tracking_id
		     WHERE member_run.logical_tracking_id = seed.logical_tracking_id
		       AND member_segment.start_time_ms = (
		         SELECT MAX(latest_segment.start_time_ms)
		         FROM source_service_run AS latest_run
		         INNER JOIN session_segment AS latest_segment
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
		limit: Int,
		beforeStartTimeMs: Long?,
		beforeSegmentId: Long?,
	): List<CellLogicalHistoryCandidate>

	/**
	 * Complete keyset candidate relation for logical Cell entries with any exact member segment
	 * overlapping one half-open wall-time range. Product authentication still happens afterwards.
	 */
	@Query(
		"""
		WITH cursor_member AS (
		  SELECT DISTINCT segment.*
		  FROM cell_captured_fact_cursor AS fact_cursor
		  INNER JOIN source_service_run AS run
		    ON run.service_run_id = fact_cursor.service_run_id
		   AND run.logical_tracking_id = fact_cursor.logical_tracking_id
		   AND run.session_segment_id = fact_cursor.session_segment_id
		  INNER JOIN session_segment AS segment
		    ON segment.id = fact_cursor.session_segment_id
		   AND segment.service_run_id = fact_cursor.service_run_id
		   AND segment.logical_tracking_id = fact_cursor.logical_tracking_id
		  WHERE fact_cursor.writer_projection_id = :writerProjectionId
		    AND fact_cursor.writer_projection_version = :writerProjectionVersion
		), logical_seed AS (
		  SELECT member.*
		  FROM cursor_member AS member
		  WHERE NOT EXISTS (
		    SELECT 1 FROM cursor_member AS newer
		    WHERE newer.logical_tracking_id = member.logical_tracking_id
		      AND (newer.start_time_ms > member.start_time_ms OR
		        (newer.start_time_ms = member.start_time_ms AND newer.id > member.id))
		  )
		), ranked_seed AS (
		  SELECT seed.*,
		    (SELECT MAX(member_segment.start_time_ms)
		     FROM source_service_run AS member_run
		     INNER JOIN session_segment AS member_segment
		       ON member_segment.id = member_run.session_segment_id
		      AND member_segment.service_run_id = member_run.service_run_id
		      AND member_segment.logical_tracking_id = member_run.logical_tracking_id
		     WHERE member_run.logical_tracking_id = seed.logical_tracking_id
		    ) AS logical_recency_start_ms,
		    (SELECT MAX(member_segment.id)
		     FROM source_service_run AS member_run
		     INNER JOIN session_segment AS member_segment
		       ON member_segment.id = member_run.session_segment_id
		      AND member_segment.service_run_id = member_run.service_run_id
		      AND member_segment.logical_tracking_id = member_run.logical_tracking_id
		     WHERE member_run.logical_tracking_id = seed.logical_tracking_id
		       AND member_segment.start_time_ms = (
		         SELECT MAX(latest_segment.start_time_ms)
		         FROM source_service_run AS latest_run
		         INNER JOIN session_segment AS latest_segment
		           ON latest_segment.id = latest_run.session_segment_id
		          AND latest_segment.service_run_id = latest_run.service_run_id
		          AND latest_segment.logical_tracking_id = latest_run.logical_tracking_id
		         WHERE latest_run.logical_tracking_id = seed.logical_tracking_id
		       )
		    ) AS logical_recency_segment_id
		  FROM logical_seed AS seed
		)
		SELECT * FROM ranked_seed AS candidate
		WHERE EXISTS (
		  SELECT 1
		  FROM source_service_run AS range_run
		  INNER JOIN session_segment AS range_segment
		    ON range_segment.id = range_run.session_segment_id
		   AND range_segment.service_run_id = range_run.service_run_id
		   AND range_segment.logical_tracking_id = range_run.logical_tracking_id
		  WHERE range_run.logical_tracking_id = candidate.logical_tracking_id
		    AND range_segment.start_time_ms < :toExclusiveMs
		    AND range_segment.end_time_ms > :fromInclusiveMs
		)
		  AND (
		    :beforeStartTimeMs IS NULL
		    OR logical_recency_start_ms < :beforeStartTimeMs
		    OR (
		      logical_recency_start_ms = :beforeStartTimeMs
		      AND logical_recency_segment_id <
		        COALESCE(:beforeSegmentId, 9223372036854775807)
		    )
		  )
		ORDER BY logical_recency_start_ms DESC, logical_recency_segment_id DESC
		LIMIT :limit
		""",
	)
	suspend fun logicalHistoryCandidatePageInWallRange(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		cellSourceKind: Int,
		fromInclusiveMs: Long,
		toExclusiveMs: Long,
		limit: Int,
		beforeStartTimeMs: Long?,
		beforeSegmentId: Long?,
	): List<CellLogicalHistoryCandidate>

	/** Cursor carriers are loaded independently of their claimed current revision head. */
	@Query(
		"SELECT * FROM cell_captured_fact_cursor WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion AND " +
			"(service_run_id IN (:serviceRunIds) OR logical_tracking_id IN (:logicalTrackingIds)) " +
			"ORDER BY writer_projection_id, writer_projection_version, logical_fact_id LIMIT :limit",
	)
	suspend fun historyCursorsForScopes(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		serviceRunIds: List<String>,
		logicalTrackingIds: List<String>,
		limit: Int,
	): List<CellCapturedFactCursorEntity>

	/** Pages complete cursor-carried correction lineages and their finite direct aggregate owners. */
	@Query(
		"""
		SELECT * FROM cell_captured_fact_revision AS fact
		WHERE (
		 fact.logical_fact_id IN (
		  SELECT fact_cursor.logical_fact_id
		  FROM cell_captured_fact_cursor AS fact_cursor
		  WHERE fact_cursor.service_run_id IN (:serviceRunIds)
		     OR fact_cursor.logical_tracking_id IN (:logicalTrackingIds)
		 )
		 OR fact.logical_fact_id IN (
		  SELECT dependent.aggregate_owner_logical_fact_id
		  FROM cell_captured_fact_revision AS dependent
		  INNER JOIN cell_captured_fact_cursor AS dependent_cursor
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
	): List<CellCapturedFactRevisionEntity>

	@Query(
		"SELECT * FROM cell_captured_fact_cursor WHERE logical_fact_id IN (:logicalFactIds) " +
			"ORDER BY writer_projection_id, writer_projection_version, logical_fact_id LIMIT :limit",
	)
	suspend fun historyCursors(
		logicalFactIds: List<String>,
		limit: Int,
	): List<CellCapturedFactCursorEntity>

	@Query(
		"SELECT generation.* FROM cell_capture_deletion_generation AS generation " +
			"INNER JOIN source_service_run AS run ON " +
			"run.logical_tracking_id = generation.logical_tracking_id AND " +
			"run.service_run_id = generation.service_run_id " +
			"WHERE generation.logical_tracking_id IN (:logicalTrackingIds) " +
			"AND generation.service_run_id IN (:serviceRunIds) " +
			"ORDER BY generation.logical_tracking_id, generation.service_run_id LIMIT :limit",
	)
	suspend fun historyDeletionGenerations(
		logicalTrackingIds: List<String>,
		serviceRunIds: List<String>,
		limit: Int,
	): List<CellCaptureDeletionGenerationEntity>

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

	@Query("DELETE FROM cell_captured_fact_cursor")
	fun deleteAllCursors()

	@Query("DELETE FROM cell_captured_fact_revision")
	fun deleteAllRevisions()

	@Query("DELETE FROM cell_capture_deletion_generation")
	fun deleteAllDeletionGenerations()
}

data class CellLogicalHistoryCandidate(
	@Embedded val segment: SessionSegment,
	@ColumnInfo(name = "logical_recency_start_ms") val logicalRecencyStartMs: Long,
	@ColumnInfo(name = "logical_recency_segment_id") val logicalRecencySegmentId: Long,
)
