package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.SourceRuntimeStateEntity

@Dao
interface SourceRuntimeStateDao {
	@Query("SELECT * FROM source_runtime_state WHERE source_kind = :sourceKind AND owner_scope = :ownerScope")
	suspend fun get(sourceKind: Int, ownerScope: String): SourceRuntimeStateEntity?

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun save(entity: SourceRuntimeStateEntity)

	@Query("DELETE FROM source_runtime_state")
	fun deleteAll()
}
