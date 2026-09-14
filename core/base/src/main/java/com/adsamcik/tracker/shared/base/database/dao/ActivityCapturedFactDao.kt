package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedEvidenceEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedFragmentEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedRegistrationPlanEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedWindowCursorEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedWindowRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity

/** Narrow append-only storage boundary for the dormant captured Activity writer. */
@Dao
interface ActivityCapturedFactDao {
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertRegistrationPlanBinding(entity: ActivityCapturedRegistrationPlanEntity): Long

	@Query(
		"SELECT * FROM activity_captured_registration_plan " +
			"WHERE source_instance_id = :sourceInstanceId " +
			"AND registration_generation = :registrationGeneration LIMIT 1",
	)
	suspend fun registrationPlanBinding(
		sourceInstanceId: String,
		registrationGeneration: Long,
	): ActivityCapturedRegistrationPlanEntity?

	/** Exact next immutable authorization boundary in the provider's canonical elapsed-time order. */
	@Query(
		"SELECT effective_elapsed_realtime_nanos FROM source_authorization " +
			"WHERE source_kind = :sourceKind AND registration_generation = :registrationGeneration " +
			"AND effective_boot_id = :bootId " +
			"AND (effective_elapsed_realtime_nanos > :effectiveElapsedRealtimeNanos " +
			"OR (effective_elapsed_realtime_nanos = :effectiveElapsedRealtimeNanos " +
			"AND authorization_revision > :authorizationRevision)) " +
			"ORDER BY effective_elapsed_realtime_nanos, authorization_revision LIMIT 1",
	)
	suspend fun nextAuthorizationBoundary(
		sourceKind: Int,
		registrationGeneration: Long,
		bootId: String,
		effectiveElapsedRealtimeNanos: Long,
		authorizationRevision: Long,
	): Long?

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertRevision(entity: ActivityCapturedWindowRevisionEntity): Long

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertFragments(entities: List<ActivityCapturedFragmentEntity>)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertEvidence(entities: List<ActivityCapturedEvidenceEntity>)

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertCursor(entity: ActivityCapturedWindowCursorEntity): Long

	@Query(
		"SELECT * FROM activity_captured_window_revision " +
			"WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND logical_window_id = :logicalWindowId AND semantic_revision = :semanticRevision " +
			"LIMIT 1",
	)
	suspend fun revision(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalWindowId: String,
		semanticRevision: Long,
	): ActivityCapturedWindowRevisionEntity?

	@Query(
		"SELECT * FROM activity_captured_fragment " +
			"WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND logical_window_id = :logicalWindowId AND semantic_revision = :semanticRevision " +
			"ORDER BY fragment_ordinal",
	)
	suspend fun fragments(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalWindowId: String,
		semanticRevision: Long,
	): List<ActivityCapturedFragmentEntity>

	@Query(
		"SELECT * FROM activity_captured_evidence " +
			"WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND logical_window_id = :logicalWindowId AND semantic_revision = :semanticRevision " +
			"ORDER BY fragment_ordinal, evidence_ordinal",
	)
	suspend fun evidence(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalWindowId: String,
		semanticRevision: Long,
	): List<ActivityCapturedEvidenceEntity>

	@Query(
		"SELECT * FROM activity_captured_window_cursor " +
			"WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND logical_window_id = :logicalWindowId LIMIT 1",
	)
	suspend fun cursor(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalWindowId: String,
	): ActivityCapturedWindowCursorEntity?

	/** Stable bounded revision keyset used by Activity-owned maintenance. */
	@Query(
		"SELECT * FROM activity_captured_window_revision " +
			"WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion AND (" +
			":afterLogicalWindowId IS NULL OR logical_window_id > :afterLogicalWindowId OR " +
			"(logical_window_id = :afterLogicalWindowId " +
			"AND semantic_revision > :afterSemanticRevision)) " +
			"ORDER BY logical_window_id, semantic_revision LIMIT :limit",
	)
	suspend fun maintenanceRevisionPage(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		afterLogicalWindowId: String?,
		afterSemanticRevision: Long?,
		limit: Int,
	): List<ActivityCapturedWindowRevisionEntity>

	/**
	 * Bounded selected-session audit. The union deliberately includes either side of the immutable
	 * logical/run identity so a row that disagrees with its claimed scope cannot be skipped.
	 */
	@Query(
		"SELECT * FROM activity_captured_window_revision WHERE " +
			"(logical_tracking_id = :logicalTrackingId OR service_run_id IN (:serviceRunIds) " +
			"OR session_segment_id IN (:sessionSegmentIds)) AND (" +
			":afterLogicalWindowId IS NULL OR logical_window_id > :afterLogicalWindowId OR " +
			"(logical_window_id = :afterLogicalWindowId " +
			"AND semantic_revision > :afterSemanticRevision)) " +
			"ORDER BY logical_window_id, semantic_revision LIMIT :limit",
	)
	suspend fun selectedRevisionPage(
		logicalTrackingId: String,
		serviceRunIds: List<String>,
		sessionSegmentIds: List<Long>,
		afterLogicalWindowId: String?,
		afterSemanticRevision: Long?,
		limit: Int,
	): List<ActivityCapturedWindowRevisionEntity>

	/** Stable bounded cursor keyset used to prove one current head per retained lineage. */
	@Query(
		"SELECT * FROM activity_captured_window_cursor " +
			"WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND (:afterLogicalWindowId IS NULL OR logical_window_id > :afterLogicalWindowId) " +
			"ORDER BY logical_window_id LIMIT :limit",
	)
	suspend fun maintenanceCursorPage(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		afterLogicalWindowId: String?,
		limit: Int,
	): List<ActivityCapturedWindowCursorEntity>

	@Query(
		"SELECT * FROM activity_captured_window_cursor WHERE " +
			"(logical_tracking_id = :logicalTrackingId OR service_run_id IN (:serviceRunIds) " +
			"OR session_segment_id IN (:sessionSegmentIds)) " +
			"AND (:afterLogicalWindowId IS NULL OR logical_window_id > :afterLogicalWindowId) " +
			"ORDER BY logical_window_id LIMIT :limit",
	)
	suspend fun selectedCursorPage(
		logicalTrackingId: String,
		serviceRunIds: List<String>,
		sessionSegmentIds: List<Long>,
		afterLogicalWindowId: String?,
		limit: Int,
	): List<ActivityCapturedWindowCursorEntity>

	@Query(
		"SELECT COUNT(*) FROM activity_captured_window_revision WHERE " +
			"logical_tracking_id = :logicalTrackingId OR service_run_id IN (:serviceRunIds) " +
			"OR session_segment_id IN (:sessionSegmentIds)",
	)
	suspend fun selectedRevisionCount(
		logicalTrackingId: String,
		serviceRunIds: List<String>,
		sessionSegmentIds: List<Long>,
	): Long

	@Query(
		"SELECT COUNT(*) FROM activity_captured_window_cursor WHERE " +
			"logical_tracking_id = :logicalTrackingId OR service_run_id IN (:serviceRunIds) " +
			"OR session_segment_id IN (:sessionSegmentIds)",
	)
	suspend fun selectedCursorCount(
		logicalTrackingId: String,
		serviceRunIds: List<String>,
		sessionSegmentIds: List<Long>,
	): Long

	@Query(
		"SELECT COUNT(*) FROM activity_captured_fragment AS fragment " +
			"INNER JOIN activity_captured_window_revision AS revision " +
			"ON revision.writer_projection_id = fragment.writer_projection_id " +
			"AND revision.writer_projection_version = fragment.writer_projection_version " +
			"AND revision.logical_window_id = fragment.logical_window_id " +
			"AND revision.semantic_revision = fragment.semantic_revision " +
			"WHERE revision.logical_tracking_id = :logicalTrackingId " +
			"OR revision.service_run_id IN (:serviceRunIds) " +
			"OR revision.session_segment_id IN (:sessionSegmentIds)",
	)
	suspend fun selectedFragmentCount(
		logicalTrackingId: String,
		serviceRunIds: List<String>,
		sessionSegmentIds: List<Long>,
	): Long

	@Query(
		"SELECT COUNT(*) FROM activity_captured_evidence AS evidence " +
			"INNER JOIN activity_captured_window_revision AS revision " +
			"ON revision.writer_projection_id = evidence.writer_projection_id " +
			"AND revision.writer_projection_version = evidence.writer_projection_version " +
			"AND revision.logical_window_id = evidence.logical_window_id " +
			"AND revision.semantic_revision = evidence.semantic_revision " +
			"WHERE revision.logical_tracking_id = :logicalTrackingId " +
			"OR revision.service_run_id IN (:serviceRunIds) " +
			"OR revision.session_segment_id IN (:sessionSegmentIds)",
	)
	suspend fun selectedEvidenceCount(
		logicalTrackingId: String,
		serviceRunIds: List<String>,
		sessionSegmentIds: List<Long>,
	): Long

	/** Includes unsupported and orphaned child rows that still claim a selected window identity. */
	@Query(
		"SELECT COUNT(*) FROM activity_captured_fragment " +
			"WHERE logical_window_id IN (:logicalWindowIds)",
	)
	suspend fun payloadFragmentCountForWindows(logicalWindowIds: List<String>): Long

	/** Includes unsupported and orphaned evidence that still claims a selected window identity. */
	@Query(
		"SELECT COUNT(*) FROM activity_captured_evidence " +
			"WHERE logical_window_id IN (:logicalWindowIds)",
	)
	suspend fun payloadEvidenceCountForWindows(logicalWindowIds: List<String>): Long

	/** SQLite-enforced fragment cap; callers request one overflow row. */
	@Query(
		"SELECT * FROM activity_captured_fragment " +
			"WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND logical_window_id = :logicalWindowId AND semantic_revision = :semanticRevision " +
			"ORDER BY fragment_ordinal LIMIT :limit",
	)
	suspend fun maintenanceFragments(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalWindowId: String,
		semanticRevision: Long,
		limit: Int,
	): List<ActivityCapturedFragmentEntity>

	/** SQLite-enforced evidence cap; callers request one overflow row. */
	@Query(
		"SELECT * FROM activity_captured_evidence " +
			"WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND logical_window_id = :logicalWindowId AND semantic_revision = :semanticRevision " +
			"ORDER BY fragment_ordinal, evidence_ordinal LIMIT :limit",
	)
	suspend fun maintenanceEvidence(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalWindowId: String,
		semanticRevision: Long,
		limit: Int,
	): List<ActivityCapturedEvidenceEntity>

	/** Stable bounded plan-binding keyset for complete captured-source deletion audits. */
	@Query(
		"SELECT * FROM activity_captured_registration_plan WHERE " +
			":afterSourceInstanceId IS NULL OR source_instance_id > :afterSourceInstanceId OR " +
			"(source_instance_id = :afterSourceInstanceId " +
			"AND registration_generation > :afterRegistrationGeneration) " +
			"ORDER BY source_instance_id, registration_generation LIMIT :limit",
	)
	suspend fun maintenanceRegistrationPlanPage(
		afterSourceInstanceId: String?,
		afterRegistrationGeneration: Long?,
		limit: Int,
	): List<ActivityCapturedRegistrationPlanEntity>

	/** Nonterminal captured demands include prepared rows that could later become active. */
	@Query(
		"SELECT * FROM source_demand WHERE source_kind = :sourceKind " +
			"AND purpose = :purpose AND status IN ('ACTIVE', 'RETIRING', 'BLOCKED') " +
			"ORDER BY demand_id LIMIT :limit",
	)
	suspend fun capturedActivityDemandsForDeletion(
		sourceKind: Int,
		purpose: String,
		limit: Int,
	): List<SourceDemandEntity>

	@Query(
		"SELECT * FROM source_demand WHERE source_kind = :sourceKind AND purpose = :purpose " +
			"AND status IN ('ACTIVE', 'RETIRING', 'BLOCKED') " +
			"AND (logical_tracking_id = :logicalTrackingId OR service_run_id IN (:serviceRunIds)) " +
			"ORDER BY demand_id LIMIT :limit",
	)
	suspend fun selectedCapturedActivityDemands(
		sourceKind: Int,
		purpose: String,
		logicalTrackingId: String,
		serviceRunIds: List<String>,
		limit: Int,
	): List<SourceDemandEntity>

	/** All physical registrations that could still deliver a captured callback. */
	/** Latest immutable authorization revision identity without materializing its members. */
	@Query(
		"SELECT * FROM provider_registration_generation WHERE source_kind = :sourceKind " +
			"AND status IN ('RESERVED', 'ACTIVE', 'RETIRING') " +
			"ORDER BY registration_generation LIMIT :limit",
	)
	suspend fun activityRegistrationsForDeletion(
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
		"SELECT MAX(authorization_revision) FROM source_authorization " +
			"WHERE source_kind = :sourceKind AND registration_generation = :registrationGeneration",
	)
	suspend fun latestAuthorizationRevision(
		sourceKind: Int,
		registrationGeneration: Long,
	): Long?

	@Query(
		"SELECT * FROM source_desired_plan WHERE revision = :revision " +
			"ORDER BY source_kind LIMIT :limit",
	)
	suspend fun maintenanceDesiredPlans(
		revision: Long,
		limit: Int,
	): List<SourceDesiredPlanEntity>

	@Query(
		"SELECT COUNT(*) FROM activity_captured_window_revision WHERE " +
			"writer_projection_id != :writerProjectionId " +
			"OR writer_projection_version != :writerProjectionVersion",
	)
	suspend fun unsupportedRevisionCount(
		writerProjectionId: String,
		writerProjectionVersion: Int,
	): Long

	@Query(
		"SELECT COUNT(*) FROM activity_captured_fragment WHERE " +
			"writer_projection_id != :writerProjectionId " +
			"OR writer_projection_version != :writerProjectionVersion",
	)
	suspend fun unsupportedFragmentCount(
		writerProjectionId: String,
		writerProjectionVersion: Int,
	): Long

	@Query(
		"SELECT COUNT(*) FROM activity_captured_evidence WHERE " +
			"writer_projection_id != :writerProjectionId " +
			"OR writer_projection_version != :writerProjectionVersion",
	)
	suspend fun unsupportedEvidenceCount(
		writerProjectionId: String,
		writerProjectionVersion: Int,
	): Long

	@Query(
		"SELECT COUNT(*) FROM activity_captured_window_cursor WHERE " +
			"writer_projection_id != :writerProjectionId " +
			"OR writer_projection_version != :writerProjectionVersion",
	)
	suspend fun unsupportedCursorCount(
		writerProjectionId: String,
		writerProjectionVersion: Int,
	): Long

	/** Earliest possible band wall time across both uncertain boundaries and the full lineage. */
	@Query(
		"SELECT MIN(CASE " +
			"WHEN start_wall_time_ms <= start_wall_time_uncertainty_ms " +
				"OR end_wall_time_ms <= end_wall_time_uncertainty_ms THEN 0 " +
			"WHEN start_wall_time_ms - start_wall_time_uncertainty_ms <= " +
				"end_wall_time_ms - end_wall_time_uncertainty_ms " +
				"THEN start_wall_time_ms - start_wall_time_uncertainty_ms " +
			"ELSE end_wall_time_ms - end_wall_time_uncertainty_ms END) " +
			"FROM activity_captured_fragment " +
			"WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND logical_window_id = :logicalWindowId AND fragment_kind = 'BAND'",
	)
	suspend fun earliestPossibleBandWallTimeMs(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalWindowId: String,
	): Long?

	@Query(
		"UPDATE activity_captured_window_cursor SET " +
			"latest_semantic_revision = :newSemanticRevision, " +
			"latest_mutation_id = :newMutationId, " +
			"latest_effect_checksum = :newEffectChecksum, cursor_revision = :newCursorRevision, " +
			"updated_at_ms = :updatedAtMs " +
			"WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND logical_window_id = :logicalWindowId " +
			"AND writer_owner_generation = :writerOwnerGeneration " +
			"AND latest_semantic_revision = :expectedSemanticRevision " +
			"AND latest_mutation_id = :expectedMutationId " +
			"AND latest_effect_checksum = :expectedEffectChecksum " +
			"AND cursor_revision = :expectedCursorRevision " +
			"AND collected_data_epoch = :collectedDataEpoch " +
			"AND :newSemanticRevision = latest_semantic_revision + 1 " +
			"AND :newCursorRevision = cursor_revision + 1",
	)
	@Suppress("LongParameterList")
	suspend fun advanceCursorExact(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalWindowId: String,
		writerOwnerGeneration: Long,
		collectedDataEpoch: Long,
		expectedSemanticRevision: Long,
		expectedMutationId: String,
		expectedEffectChecksum: String,
		expectedCursorRevision: Long,
		newSemanticRevision: Long,
		newMutationId: String,
		newEffectChecksum: String,
		newCursorRevision: Long,
		updatedAtMs: Long,
	): Int

	@Query("SELECT COUNT(*) FROM activity_captured_window_revision")
	suspend fun revisionCount(): Long

	@Query("SELECT COUNT(*) FROM activity_captured_fragment")
	suspend fun fragmentCount(): Long

	@Query("SELECT COUNT(*) FROM activity_captured_evidence")
	suspend fun evidenceCount(): Long

	@Query("SELECT COUNT(*) FROM activity_captured_window_cursor")
	suspend fun cursorCount(): Long

	@Query("SELECT COUNT(*) FROM activity_captured_registration_plan")
	suspend fun registrationPlanBindingCount(): Long

	@Query(
		"DELETE FROM activity_captured_window_cursor " +
			"WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND logical_window_id IN (:logicalWindowIds)",
	)
	suspend fun deleteExactCursors(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalWindowIds: List<String>,
	): Int

	@Query(
		"DELETE FROM activity_captured_window_revision " +
			"WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND logical_window_id IN (:logicalWindowIds)",
	)
	suspend fun deleteExactRevisionLineages(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalWindowIds: List<String>,
	): Int

	@Query("DELETE FROM activity_captured_evidence")
	fun deleteAllEvidence()

	@Query("DELETE FROM activity_captured_fragment")
	fun deleteAllFragments()

	@Query("DELETE FROM activity_captured_window_cursor")
	fun deleteAllCursors()

	@Query("DELETE FROM activity_captured_window_revision")
	fun deleteAllRevisions()

	@Query("DELETE FROM activity_captured_registration_plan")
	fun deleteAllRegistrationPlanBindings()
}
