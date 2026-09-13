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
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity

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

	/** Bounded immutable applied-plan bindings used to authenticate terminal export settlement. */
	@Query(
		"SELECT * FROM activity_captured_registration_plan " +
			"WHERE registration_generation IN (:registrationGenerations) " +
			"ORDER BY registration_generation, source_instance_id LIMIT :limit",
	)
	suspend fun portableRegistrationPlans(
		registrationGenerations: List<Long>,
		limit: Int,
	): List<ActivityCapturedRegistrationPlanEntity>

	/** Bounded exact desired Activity plans referenced by immutable applied-plan bindings. */
	@Query(
		"SELECT * FROM source_desired_plan WHERE source_kind = :sourceKind " +
			"AND revision IN (:configurationRevisions) ORDER BY revision LIMIT :limit",
	)
	suspend fun portableDesiredPlans(
		sourceKind: Int,
		configurationRevisions: List<Long>,
		limit: Int,
	): List<SourceDesiredPlanEntity>

	/** Bounded provider registrations referenced by terminal Activity completeness rows. */
	@Query(
		"SELECT * FROM provider_registration_generation WHERE source_kind = :sourceKind " +
			"AND registration_generation IN (:registrationGenerations) " +
			"ORDER BY registration_generation LIMIT :limit",
	)
	suspend fun portableProviderRegistrations(
		sourceKind: Int,
		registrationGenerations: List<Long>,
		limit: Int,
	): List<ProviderRegistrationGenerationEntity>

	/** Source-filtered completeness prevents unrelated sources from consuming the overflow probe. */
	@Query(
		"SELECT * FROM source_session_completeness WHERE source_kind = :sourceKind " +
			"AND service_run_id IN (:serviceRunIds) " +
			"ORDER BY service_run_id, source_instance_id, registration_generation LIMIT :limit",
	)
	suspend fun portableCompleteness(
		sourceKind: Int,
		serviceRunIds: List<String>,
		limit: Int,
	): List<SourceSessionCompletenessEntity>

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

	/** All physical registrations that could still deliver a captured callback. */
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
