package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.adsamcik.tracker.shared.base.database.data.SourceEventRetentionScopeProofEntity

/**
 * Stores and pages payload-free witnesses for source logical-run retention scopes.
 */
@Dao
interface SourceEventRetentionScopeProofDao {
	/**
	 * Inserts [SourceEventRetentionScopeProofEntity] rows. Existing conflicting rows remain
	 * unchanged. An ignored insert returns -1 for that row.
	 */
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertIfAbsent(proof: SourceEventRetentionScopeProofEntity): Long

	/**
	 * Updates existing [SourceEventRetentionScopeProofEntity] rows by primary key. Returns the
	 * affected row count.
	 */
	@Update
	suspend fun update(proof: SourceEventRetentionScopeProofEntity): Int

	/**
	 * Reads `source_event_retention_scope_proof` rows. Query conditions include `source_kind =
	 * :sourceKind`, `logical_run_scope_digest = :scopeDigest`. Returns at most 1 row. Returns null
	 * when no row matches.
	 */
	@Query(
		"SELECT * FROM source_event_retention_scope_proof " +
			"WHERE source_kind = :sourceKind AND logical_run_scope_digest = :scopeDigest LIMIT 1",
	)
	suspend fun get(
		sourceKind: Int,
		scopeDigest: String,
	): SourceEventRetentionScopeProofEntity?

	/**
	 * Reads `source_event_retention_scope_proof` rows. Query conditions include `source_kind =
	 * :sourceKind`, `logical_run_scope_digest > :afterScopeDigest`. Orders by
	 * `logical_run_scope_digest`. Returns at most `limit` rows.
	 */
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

	/**
	 * Deletes `source_event_retention_scope_proof` rows matching the supplied scope. Query conditions
	 * include `source_kind = :sourceKind`, `logical_run_scope_digest IN (:scopeDigests)`. Returns the
	 * affected row count.
	 */
	@Query(
		"DELETE FROM source_event_retention_scope_proof WHERE source_kind = :sourceKind " +
			"AND logical_run_scope_digest IN (:scopeDigests)",
	)
	suspend fun deleteExactSourceScopes(sourceKind: Int, scopeDigests: List<String>): Int

	/**
	 * Counts rows in `source_event_retention_scope_proof`.
	 */
	@Query("SELECT COUNT(*) FROM source_event_retention_scope_proof")
	suspend fun countAll(): Long

	/**
	 * Counts rows in `source_event_retention_scope_proof`. Query conditions include `source_kind =
	 * :sourceKind`.
	 */
	@Query("SELECT COUNT(*) FROM source_event_retention_scope_proof WHERE source_kind = :sourceKind")
	suspend fun countForSource(sourceKind: Int): Long

	/**
	 * Deletes `source_event_retention_scope_proof` rows.
	 */
	@Query("DELETE FROM source_event_retention_scope_proof")
	fun deleteAll()
}
