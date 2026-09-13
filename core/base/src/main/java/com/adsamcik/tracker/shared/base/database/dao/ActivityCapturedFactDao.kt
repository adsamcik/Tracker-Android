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

	@Query("SELECT COUNT(*) FROM activity_captured_registration_plan")
	suspend fun registrationPlanBindingCount(): Long

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
