package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.PersonalRecordEntity

@Dao
interface PersonalRecordDao {
	@Query("SELECT * FROM personal_record WHERE metric = :metric LIMIT 1")
	fun getByMetric(metric: String): PersonalRecordEntity?

	@Query("SELECT * FROM personal_record ORDER BY updated_at DESC")
	fun getAll(): List<PersonalRecordEntity>

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	fun upsert(record: PersonalRecordEntity)

	@Query("DELETE FROM personal_record")
	fun deleteAll()
}
