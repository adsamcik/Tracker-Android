package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.PersonalRecordEntity

@Dao
interface PersonalRecordDao {
	@Query("SELECT * FROM personal_record WHERE metric = :metric LIMIT 1")
	suspend fun getByMetric(metric: String): PersonalRecordEntity?

	@Query("SELECT * FROM personal_record ORDER BY updated_at DESC LIMIT 500")
	suspend fun getAll(): List<PersonalRecordEntity>

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun upsert(record: PersonalRecordEntity)

	@Query("DELETE FROM personal_record")
	fun deleteAll()

	/**
	 * Personal records are lifetime user accomplishments — a "best ever" by definition
	 * cannot be retention-aged. This query intentionally never matches (`WHERE 1 = 0`)
	 * so calls from the retention worker are safe no-ops. Use [deleteAll] for an
	 * explicit user-initiated reset.
	 */
	@Query("DELETE FROM personal_record WHERE 1 = 0 AND :beforeMs = :beforeMs")
	suspend fun deleteOlderThan(beforeMs: Long): Int
}
