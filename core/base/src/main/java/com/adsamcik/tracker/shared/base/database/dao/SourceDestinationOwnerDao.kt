package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity

@Dao
interface SourceDestinationOwnerDao {
	@Query(
		"SELECT * FROM source_destination_owner " +
			"WHERE source_kind = :sourceKind AND destination = :destination",
	)
	suspend fun get(sourceKind: Int, destination: String): SourceDestinationOwnerEntity?

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertIfAbsent(entity: SourceDestinationOwnerEntity): Long

	@Query(
		"SELECT EXISTS(SELECT 1 FROM source_destination_owner " +
			"WHERE source_kind = :sourceKind AND destination = :destination " +
			"AND owner = :owner AND owner_generation = :ownerGeneration)",
	)
	suspend fun isExactOwner(
		sourceKind: Int,
		destination: String,
		owner: String,
		ownerGeneration: Long,
	): Boolean

	/**
	 * Low-level monotonic owner transition seam.
	 *
	 * This CAS is not a cutover coordinator: queued-command fencing, segment-effective provenance,
	 * rollback writer binding, and post-deletion lane reconstruction must be established around it.
	 * Production callers must therefore own those gates and perform the complete authority change in
	 * the same Room transaction.
	 */
	@Query(
		"UPDATE source_destination_owner SET owner = :newOwner, " +
			"owner_generation = :newOwnerGeneration, updated_at_ms = :updatedAtMs " +
			"WHERE source_kind = :sourceKind AND destination = :destination " +
			"AND owner = :expectedOwner AND owner_generation = :expectedOwnerGeneration " +
			"AND :newOwnerGeneration > owner_generation",
	)
	suspend fun compareAndSetOwner(
		sourceKind: Int,
		destination: String,
		expectedOwner: String,
		expectedOwnerGeneration: Long,
		newOwner: String,
		newOwnerGeneration: Long,
		updatedAtMs: Long,
	): Int

	/**
	 * Reuses only the two known source contracts after an acknowledged full clear.
	 * Call inside the deletion coordinator's transaction after all old rows are gone.
	 * Ordinary same-epoch transitions continue to use the monotonic CAS above.
	 */
	@Query(
		"""
		UPDATE source_destination_owner
		SET owner = CASE source_kind
			WHEN 2 THEN 'ACTIVITY_SESSION_FACTS' WHEN 4 THEN 'PRESSURE_SESSION_FACTS'
			WHEN 5 THEN 'WIFI_SESSION_FACTS' WHEN 6 THEN 'CELL_SESSION_FACTS' END,
			owner_generation = 4, updated_at_ms = :updatedAtMs
		WHERE source_kind = :sourceKind AND destination = :destination
			AND owner = :expectedOwner AND owner_generation = :expectedOwnerGeneration
			AND destination = CASE source_kind
				WHEN 2 THEN 'SESSION_ACTIVITY' WHEN 4 THEN 'SESSION_PRESSURE'
				WHEN 5 THEN 'SESSION_WIFI' WHEN 6 THEN 'SESSION_CELL' END
			AND (
				(owner_generation = 1 AND owner = CASE source_kind
					WHEN 2 THEN 'LEGACY_ACTIVITY_SNAPSHOT' WHEN 4 THEN 'LEGACY_PRESSURE_SAMPLE' END)
				OR (owner_generation IN (2, 4) AND owner = CASE source_kind
					WHEN 2 THEN 'ACTIVITY_SESSION_FACTS' WHEN 4 THEN 'PRESSURE_SESSION_FACTS'
					WHEN 5 THEN 'WIFI_SESSION_FACTS' WHEN 6 THEN 'CELL_SESSION_FACTS' END)
				OR (owner_generation IN (3, 5) AND owner = CASE source_kind
					WHEN 2 THEN 'CONTAINED_ACTIVITY_SESSION_FACTS'
					WHEN 4 THEN 'CONTAINED_PRESSURE_SESSION_FACTS'
					WHEN 5 THEN 'CONTAINED_WIFI_SESSION_FACTS'
					WHEN 6 THEN 'CONTAINED_CELL_SESSION_FACTS' END)
			)
			AND EXISTS (
				SELECT 1 FROM collected_data_deletion_operation operation
				JOIN source_evidence_state evidence ON evidence.id = 1
				WHERE operation.operation_id = :operationId
					AND operation.phase = 'DATABASE_CLEARED'
					AND operation.target_collected_data_epoch = :targetCollectedDataEpoch
					AND evidence.collected_data_epoch = :targetCollectedDataEpoch
					AND operation.retained_from_ms IS evidence.retained_from_ms
					AND operation.retention_work_execution_id IS NULL
			)
			AND NOT EXISTS (SELECT 1 FROM source_product_projection_lane)
			AND NOT EXISTS (SELECT 1 FROM source_event_wal)
		""",
	)
	suspend fun resetKnownCaptureOwnerAfterFullDeletion(
		sourceKind: Int,
		destination: String,
		expectedOwner: String,
		expectedOwnerGeneration: Long,
		operationId: String,
		targetCollectedDataEpoch: Long,
		updatedAtMs: Long,
	): Int
}
