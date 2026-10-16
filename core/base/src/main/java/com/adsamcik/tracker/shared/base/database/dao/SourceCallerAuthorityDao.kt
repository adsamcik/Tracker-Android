package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.adsamcik.tracker.shared.base.database.data.SourceCallerAcceptedAuthorityEntity

/**
 * Persists accepted source-purpose caller references and reads or removes their exact saved
 * authority rows.
 */
@Dao
interface SourceCallerAuthorityDao {
	/**
	 * Inserts [SourceCallerAcceptedAuthorityEntity] rows. A conflicting key aborts the insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insert(rows: List<SourceCallerAcceptedAuthorityEntity>): List<Long>

	/**
	 * Reads `source_caller_accepted_authority` rows. Query conditions include `reference =
	 * :reference`. Orders by `source_kind, purpose`.
	 */
	@Query(
		"SELECT * FROM source_caller_accepted_authority WHERE reference = :reference " +
			"ORDER BY source_kind, purpose",
	)
	suspend fun rows(reference: String): List<SourceCallerAcceptedAuthorityEntity>

	/**
	 * Reads `source_caller_accepted_authority` rows. Query conditions include `reference =
	 * :reference`. Orders by `source_kind, purpose`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM source_caller_accepted_authority WHERE reference = :reference " +
			"ORDER BY source_kind, purpose LIMIT :limit",
	)
	suspend fun rowsBounded(
		reference: String,
		limit: Int,
	): List<SourceCallerAcceptedAuthorityEntity>

	/**
	 * Updates existing [SourceCallerAcceptedAuthorityEntity] rows by primary key. Returns the
	 * affected row count.
	 */
	@Update
	suspend fun update(rows: List<SourceCallerAcceptedAuthorityEntity>): Int

	/**
	 * Deletes `source_caller_accepted_authority` rows matching the supplied scope. Query conditions
	 * include `reference = :reference`. Returns the affected row count.
	 */
	@Query("DELETE FROM source_caller_accepted_authority WHERE reference = :reference")
	suspend fun delete(reference: String): Int

	/**
	 * Deletes `source_caller_accepted_authority` rows. Returns the affected row count.
	 */
	@Query("DELETE FROM source_caller_accepted_authority")
	fun deleteAll(): Int

	/**
	 * Reads `source_caller_accepted_authority` rows. Orders by `MAX , reference`. Returns at most
	 * `limit` rows.
	 */
	@Query(
		"""
		SELECT reference
		FROM source_caller_accepted_authority
		GROUP BY reference
		HAVING MIN(status) = 'RETIRED'
			AND MAX(status) = 'RETIRED'
			AND MAX(retired_at_ms) <= :retiredBeforeOrAtMs
		ORDER BY MAX(retired_at_ms), reference
		LIMIT :limit
		""",
	)
	suspend fun retiredReferencesForPrune(
		retiredBeforeOrAtMs: Long,
		limit: Int,
	): List<String>

	/**
	 * Deletes `source_caller_accepted_authority` rows matching the supplied scope. Query conditions
	 * include `reference IN (:references)`.
	 */
	@Query("DELETE FROM source_caller_accepted_authority WHERE reference IN (:references)")
	suspend fun deleteReferences(references: Collection<String>): Int
}
