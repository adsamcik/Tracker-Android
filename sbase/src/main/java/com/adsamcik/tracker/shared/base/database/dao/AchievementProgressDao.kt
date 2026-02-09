package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.AchievementProgressEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AchievementProgressDao {
	@Query("SELECT * FROM achievement_progress WHERE achievement_id = :achievementId LIMIT 1")
	suspend fun getById(achievementId: String): AchievementProgressEntity?

	@Query("SELECT * FROM achievement_progress ORDER BY updated_at DESC")
	suspend fun getAll(): List<AchievementProgressEntity>

	@Query("SELECT * FROM achievement_progress ORDER BY updated_at DESC")
	fun getAllFlow(): Flow<List<AchievementProgressEntity>>

	@Query("SELECT * FROM achievement_progress WHERE unlocked_at IS NOT NULL ORDER BY unlocked_at DESC")
	suspend fun getUnlocked(): List<AchievementProgressEntity>

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun upsert(progress: AchievementProgressEntity)

	@Query("DELETE FROM achievement_progress")
	fun deleteAll()
    @Query("DELETE FROM achievement_progress WHERE updated_at < :beforeMs")
    suspend fun deleteOlderThan(beforeMs: Long): Int
}
