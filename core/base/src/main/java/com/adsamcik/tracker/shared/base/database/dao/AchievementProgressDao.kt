package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import com.adsamcik.tracker.shared.base.database.data.AchievementProgressEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AchievementProgressDao {
	@Query("SELECT * FROM achievement_progress WHERE metric_key = :metricKey LIMIT 1")
	suspend fun getByMetric(metricKey: String): AchievementProgressEntity?

	@Query(
		"SELECT * FROM achievement_progress " +
			"WHERE authority_kind = '${AchievementProgressEntity.AUTHORITY_QUALIFIED_STEPS_V1}' " +
			"AND metric_key IN (" +
			"'${AchievementProgressEntity.METRIC_GOAL_STREAK_DAYS}', " +
			"'${AchievementProgressEntity.METRIC_PERFECT_WEEKS}')",
	)
	suspend fun getQualifiedStepsGoalRows(): List<AchievementProgressEntity>

	@Query(
		"UPDATE achievement_progress SET " +
			"authority_revision = MAX(authority_revision, :sourceEvidenceRevision), " +
			"authority_state = '${AchievementProgressEntity.AUTHORITY_STATE_MATERIALIZING}', " +
			"updated_at = MAX(updated_at, :updatedAtMs) " +
			"WHERE authority_kind = '${AchievementProgressEntity.AUTHORITY_QUALIFIED_STEPS_V1}' " +
			"AND metric_key IN (" +
			"'${AchievementProgressEntity.METRIC_GOAL_STREAK_DAYS}', " +
			"'${AchievementProgressEntity.METRIC_PERFECT_WEEKS}') " +
			"AND (authority_state != '${AchievementProgressEntity.AUTHORITY_STATE_MATERIALIZING}' " +
			"OR authority_revision < :sourceEvidenceRevision)",
	)
	suspend fun markQualifiedStepsMaterializing(
		sourceEvidenceRevision: Long,
		updatedAtMs: Long,
	): Int

	@Query(
		"""
		INSERT INTO achievement_progress(
			metric_key, last_tier_index, last_value, updated_at, last_unlocked_at,
			authority_kind, authority_revision, authority_digest, authority_state,
			qualified_notification_claimed_tier_index
		) VALUES (
			:metricKey, :lastTierIndex, :lastValue, :updatedAt, :lastUnlockedAt,
			:authorityKind, :authorityRevision, :authorityDigest, :authorityState,
			:qualifiedNotificationClaimedTierIndex
		)
		ON CONFLICT(metric_key) DO UPDATE SET
			last_tier_index = excluded.last_tier_index,
			last_value = excluded.last_value,
			updated_at = excluded.updated_at,
			last_unlocked_at = excluded.last_unlocked_at,
			authority_kind = excluded.authority_kind,
			authority_revision = excluded.authority_revision,
			authority_digest = excluded.authority_digest,
			authority_state = excluded.authority_state,
			qualified_notification_claimed_tier_index =
				excluded.qualified_notification_claimed_tier_index
		""",
	)
	suspend fun replaceQualifiedUnchecked(
		metricKey: String,
		lastTierIndex: Int,
		lastValue: Double,
		updatedAt: Long,
		lastUnlockedAt: Long?,
		authorityKind: String,
		authorityRevision: Long,
		authorityDigest: String,
		authorityState: String,
		qualifiedNotificationClaimedTierIndex: Int?,
	)

	@Transaction
	suspend fun replaceQualified(
		metricKey: String,
		lastTierIndex: Int,
		lastValue: Double,
		updatedAt: Long,
		lastUnlockedAt: Long? = null,
		authorityKind: String,
		authorityRevision: Long,
		authorityDigest: String,
		authorityState: String,
		qualifiedNotificationClaimedTierIndex: Int? = null,
	) {
		require(authorityKind == AchievementProgressEntity.AUTHORITY_QUALIFIED_STEPS_V1)
		require(authorityRevision >= 0L)
		require(
			authorityState == AchievementProgressEntity.AUTHORITY_STATE_READY ||
				authorityState == AchievementProgressEntity.AUTHORITY_STATE_MATERIALIZING ||
				authorityState == AchievementProgressEntity.AUTHORITY_STATE_UNVERIFIABLE,
		)
		require(lastTierIndex >= -1 && lastValue.isFinite() && lastValue >= 0.0 && updatedAt >= 0L)
		val validated = AchievementProgressEntity(
			metricKey = metricKey,
			lastTierIndex = lastTierIndex,
			lastValue = lastValue,
			updatedAt = updatedAt,
			lastUnlockedAt = lastUnlockedAt,
			authorityKind = authorityKind,
			authorityRevision = authorityRevision,
			authorityDigest = authorityDigest,
			authorityState = authorityState,
			qualifiedNotificationClaimedTierIndex = qualifiedNotificationClaimedTierIndex,
		)
		replaceQualifiedUnchecked(
			validated.metricKey,
			validated.lastTierIndex,
			validated.lastValue,
			validated.updatedAt,
			validated.lastUnlockedAt,
			requireNotNull(validated.authorityKind),
			requireNotNull(validated.authorityRevision),
			requireNotNull(validated.authorityDigest),
			requireNotNull(validated.authorityState),
			validated.qualifiedNotificationClaimedTierIndex,
		)
	}
	@Query("SELECT * FROM achievement_progress ORDER BY updated_at DESC")
	suspend fun getAll(): List<AchievementProgressEntity>
	@Query("SELECT * FROM achievement_progress ORDER BY updated_at DESC")
	fun getAllFlow(): Flow<List<AchievementProgressEntity>>
	@Query("SELECT * FROM achievement_progress WHERE last_tier_index >= 0 ORDER BY updated_at DESC LIMIT :limit")
	fun getRecentProgressFlow(limit: Int): Flow<List<AchievementProgressEntity>>
	@Query(
		"""
		INSERT INTO achievement_progress(
			metric_key, last_tier_index, last_value, updated_at, last_unlocked_at
		)
		VALUES (
			:metricKey, :lastTierIndex, :lastValue, :updatedAt,
			CASE WHEN :lastTierIndex >= 0 THEN COALESCE(:lastUnlockedAt, :updatedAt) ELSE NULL END
		)
		ON CONFLICT(metric_key) DO UPDATE SET
			last_unlocked_at = CASE
				WHEN excluded.last_tier_index > achievement_progress.last_tier_index
					THEN COALESCE(excluded.last_unlocked_at, excluded.updated_at)
				ELSE achievement_progress.last_unlocked_at
			END,
			last_tier_index = MAX(achievement_progress.last_tier_index, excluded.last_tier_index),
			last_value = excluded.last_value,
			updated_at = excluded.updated_at,
			authority_kind = NULL,
			authority_revision = NULL,
			authority_digest = NULL,
			authority_state = NULL,
			qualified_notification_claimed_tier_index = NULL
		""",
	)
	suspend fun upsertMonotonic(
		metricKey: String,
		lastTierIndex: Int,
		lastValue: Double,
		updatedAt: Long,
		lastUnlockedAt: Long? = null,
	)

	suspend fun upsert(entity: AchievementProgressEntity) {
		if (entity.authorityKind == null) {
			upsertMonotonic(
				entity.metricKey,
				entity.lastTierIndex,
				entity.lastValue,
				entity.updatedAt,
				entity.lastUnlockedAt,
			)
		} else {
			replaceQualified(
				entity.metricKey,
				entity.lastTierIndex,
				entity.lastValue,
				entity.updatedAt,
				entity.lastUnlockedAt,
				requireNotNull(entity.authorityKind),
				requireNotNull(entity.authorityRevision),
				requireNotNull(entity.authorityDigest),
				requireNotNull(entity.authorityState),
				entity.qualifiedNotificationClaimedTierIndex,
			)
		}
	}
	@Transaction
	suspend fun upsertAll(entities: List<AchievementProgressEntity>) { entities.forEach { upsert(it) } }
	@Query("DELETE FROM achievement_progress")
	fun deleteAll()
	@Query("DELETE FROM achievement_progress WHERE updated_at < :beforeMs AND last_tier_index < 0")
	suspend fun deleteOlderThan(beforeMs: Long): Int
}
