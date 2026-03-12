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

	/**
	 * Insert a new progress row. If achievement_id already exists, the insert is ignored
	 * (returns -1). This avoids the PK-churn bug from @Insert(REPLACE) + autoGenerate.
	 */
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertIfNew(entity: AchievementProgressEntity): Long

	/**
	 * Update progress for an existing achievement row.
	 * Only sets unlocked_at if it was previously null (compare-and-set).
	 */
	@Query("""
		UPDATE achievement_progress
		SET current_value = :currentValue,
			target_value = :targetValue,
			tier = CASE WHEN :tier IS NOT NULL THEN :tier ELSE tier END,
			unlocked_at = CASE WHEN unlocked_at IS NULL AND :unlockedAt IS NOT NULL THEN :unlockedAt ELSE unlocked_at END,
			updated_at = :updatedAt
		WHERE achievement_id = :achievementId
	""")
	suspend fun updateProgress(
		achievementId: String,
		currentValue: Long,
		targetValue: Long,
		tier: Int?,
		unlockedAt: Long?,
		updatedAt: Long,
	)

	/**
	 * Atomic upsert: insert-if-new then update. Safe with autoGenerate PK.
	 */
	suspend fun upsert(entity: AchievementProgressEntity) {
		insertIfNew(entity)
		updateProgress(
			achievementId = entity.achievementId,
			currentValue = entity.currentValue,
			targetValue = entity.targetValue,
			tier = entity.tier,
			unlockedAt = entity.unlockedAt,
			updatedAt = entity.updatedAt,
		)
	}

	/**
	 * Mark an achievement as notified (user has seen the unlock popup).
	 */
	@Query("UPDATE achievement_progress SET notified_at = :notifiedAt WHERE achievement_id = :achievementId")
	suspend fun markNotified(achievementId: String, notifiedAt: Long)

	@Query("DELETE FROM achievement_progress")
	fun deleteAll()

	@Query("DELETE FROM achievement_progress WHERE updated_at < :beforeMs")
	suspend fun deleteOlderThan(beforeMs: Long): Int
}
