package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity

/** Exact-key access to durable, payload-free source deletion fences. */
@Dao
interface SourceDeletionFenceDao {
	/**
	 * Installs the first permanent authority for an exact scope.
	 *
	 * Production source-local deletion must use this monotonic operation and then read the winner.
	 * Replacing an existing row could lower its generation or epoch and reopen an ABA window.
	 * Returns -1 when the exact scope was already fenced.
	 */
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertIfAbsent(fence: SourceDeletionFenceEntity): Long

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
