package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.adsamcik.tracker.shared.base.database.data.AchievementProgressEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AchievementProgressDao {
	@Query("SELECT * FROM achievement_progress WHERE metric_key = :metricKey LIMIT 1")
	suspend fun getByMetric(metricKey: String): AchievementProgressEntity?
	@Query("SELECT * FROM achievement_progress ORDER BY updated_at DESC")
	suspend fun getAll(): List<AchievementProgressEntity>
	@Query("SELECT * FROM achievement_progress ORDER BY updated_at DESC")
	fun getAllFlow(): Flow<List<AchievementProgressEntity>>
	@Query("SELECT * FROM achievement_progress WHERE last_tier_index >= 0 ORDER BY updated_at DESC LIMIT :limit")
	fun getRecentProgressFlow(limit: Int): Flow<List<AchievementProgressEntity>>
	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun upsert(entity: AchievementProgressEntity)
	@Transaction
	suspend fun upsertAll(entities: List<AchievementProgressEntity>) { entities.forEach { upsert(it) } }
	@Query("DELETE FROM achievement_progress")
	fun deleteAll()
	@Query("DELETE FROM achievement_progress WHERE updated_at < :beforeMs AND last_tier_index < 0")
	suspend fun deleteOlderThan(beforeMs: Long): Int
}
