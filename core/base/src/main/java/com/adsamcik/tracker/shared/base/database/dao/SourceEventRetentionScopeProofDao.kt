package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.adsamcik.tracker.shared.base.database.data.SourceEventRetentionScopeProofEntity

@Dao
interface SourceEventRetentionScopeProofDao {
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertIfAbsent(proof: SourceEventRetentionScopeProofEntity): Long

	@Update
	suspend fun update(proof: SourceEventRetentionScopeProofEntity): Int

	@Query(
		"SELECT * FROM source_event_retention_scope_proof " +
			"WHERE source_kind = :sourceKind AND logical_run_scope_digest = :scopeDigest LIMIT 1",
	)
	suspend fun get(
		sourceKind: Int,
		scopeDigest: String,
	): SourceEventRetentionScopeProofEntity?

	@Query(
		"SELECT * FROM source_event_retention_scope_proof WHERE source_kind = :sourceKind " +
			"AND (:afterScopeDigest IS NULL OR logical_run_scope_digest > :afterScopeDigest) " +
			"ORDER BY logical_run_scope_digest LIMIT :limit",
	)
	suspend fun sourceErasePage(
		sourceKind: Int,
		afterScopeDigest: String?,
		limit: Int,
	): List<SourceEventRetentionScopeProofEntity>

	@Query(
		"DELETE FROM source_event_retention_scope_proof WHERE source_kind = :sourceKind " +
			"AND logical_run_scope_digest IN (:scopeDigests)",
	)
	suspend fun deleteExactSourceScopes(sourceKind: Int, scopeDigests: List<String>): Int

	@Query("SELECT COUNT(*) FROM source_event_retention_scope_proof")
	suspend fun countAll(): Long

	@Query("SELECT COUNT(*) FROM source_event_retention_scope_proof WHERE source_kind = :sourceKind")
	suspend fun countForSource(sourceKind: Int): Long

	@Query("DELETE FROM source_event_retention_scope_proof")
	fun deleteAll()
}
