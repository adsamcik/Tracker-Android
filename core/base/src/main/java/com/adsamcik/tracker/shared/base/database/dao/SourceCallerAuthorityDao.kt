package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.adsamcik.tracker.shared.base.database.data.SourceCallerAcceptedAuthorityEntity

@Dao
interface SourceCallerAuthorityDao {
	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insert(rows: List<SourceCallerAcceptedAuthorityEntity>): List<Long>

	@Query(
		"SELECT * FROM source_caller_accepted_authority WHERE reference = :reference " +
			"ORDER BY source_kind, purpose",
	)
	suspend fun rows(reference: String): List<SourceCallerAcceptedAuthorityEntity>

	@Update
	suspend fun update(rows: List<SourceCallerAcceptedAuthorityEntity>): Int

	@Query("DELETE FROM source_caller_accepted_authority WHERE reference = :reference")
	suspend fun delete(reference: String): Int

	@Query("DELETE FROM source_caller_accepted_authority")
	fun deleteAll(): Int

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

	@Query("DELETE FROM source_caller_accepted_authority WHERE reference IN (:references)")
	suspend fun deleteReferences(references: Collection<String>): Int
}
