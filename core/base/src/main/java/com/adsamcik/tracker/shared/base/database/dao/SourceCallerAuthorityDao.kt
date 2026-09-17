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
}
