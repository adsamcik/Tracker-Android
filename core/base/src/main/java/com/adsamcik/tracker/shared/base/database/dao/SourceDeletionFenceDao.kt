package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity

/** Exact-key access to durable, payload-free source deletion fences. */
@Dao
interface SourceDeletionFenceDao {
	/** Inserts a new scope fence or replaces the exact existing scope authority. */
	@Upsert
	suspend fun upsert(fence: SourceDeletionFenceEntity)

	/** Returns the exact source, purpose, scope-kind, and digest fence when present. */
	@Query(
		"SELECT * FROM source_deletion_fence WHERE source_kind = :sourceKind " +
			"AND purpose = :purpose AND scope_kind = :scopeKind " +
			"AND scope_identity_digest = :scopeIdentityDigest LIMIT 1",
	)
	suspend fun get(
		sourceKind: Int,
		purpose: String,
		scopeKind: String,
		scopeIdentityDigest: String,
	): SourceDeletionFenceEntity?

	/** Reports whether the exact source/purpose scope is fenced. */
	@Query(
		"SELECT EXISTS(SELECT 1 FROM source_deletion_fence WHERE source_kind = :sourceKind " +
			"AND purpose = :purpose AND scope_kind = :scopeKind " +
			"AND scope_identity_digest = :scopeIdentityDigest)",
	)
	suspend fun contains(
		sourceKind: Int,
		purpose: String,
		scopeKind: String,
		scopeIdentityDigest: String,
	): Boolean

	/** Counts retained fences for verification and full-deletion assertions. */
	@Query("SELECT COUNT(*) FROM source_deletion_fence")
	suspend fun countAll(): Long

	/** Removes every fence only as part of the complete collected-data deletion transaction. */
	@Query("DELETE FROM source_deletion_fence")
	fun deleteAll()
}
