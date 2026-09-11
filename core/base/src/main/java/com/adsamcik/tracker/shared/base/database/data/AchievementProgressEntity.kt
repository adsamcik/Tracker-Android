package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "achievement_progress", indices = [Index(value = ["metric_key"], unique = true)])
data class AchievementProgressEntity(
	@PrimaryKey @ColumnInfo(name = "metric_key") val metricKey: String,
	@ColumnInfo(name = "last_tier_index") val lastTierIndex: Int = -1,
	@ColumnInfo(name = "last_value") val lastValue: Double = 0.0,
	@ColumnInfo(name = "updated_at") val updatedAt: Long = 0L,
	@ColumnInfo(name = "last_unlocked_at") val lastUnlockedAt: Long? = null,
	@ColumnInfo(name = "authority_kind") val authorityKind: String? = null,
	@ColumnInfo(name = "authority_revision") val authorityRevision: Long? = null,
	@ColumnInfo(name = "authority_digest") val authorityDigest: String? = null,
	@ColumnInfo(name = "authority_state") val authorityState: String? = null,
	@ColumnInfo(name = "qualified_notification_claimed_tier_index")
	val qualifiedNotificationClaimedTierIndex: Int? = null,
) {
	init {
		val authorityFields = listOf(authorityKind, authorityRevision, authorityDigest, authorityState)
		require(authorityFields.all { it == null } || authorityFields.all { it != null })
		authorityRevision?.let { require(it >= 0L) }
		authorityDigest?.let { require(SHA_256_HEX.matches(it)) }
		lastUnlockedAt?.let { require(it >= 0L) }
		qualifiedNotificationClaimedTierIndex?.let { claimedTier ->
			require(authorityKind == AUTHORITY_QUALIFIED_STEPS_V1)
			require(claimedTier >= lastTierIndex && claimedTier >= -1)
		}
	}

	companion object {
		const val AUTHORITY_QUALIFIED_STEPS_V1 = "QUALIFIED_STEPS_V1"
		const val AUTHORITY_STATE_READY = "READY"
		const val AUTHORITY_STATE_MATERIALIZING = "MATERIALIZING"
		const val AUTHORITY_STATE_UNVERIFIABLE = "UNVERIFIABLE"
		const val METRIC_GOAL_STREAK_DAYS = "goal_streak_days"
		const val METRIC_PERFECT_WEEKS = "perfect_weeks"

		private val SHA_256_HEX = Regex("[0-9a-f]{64}")
	}
}
