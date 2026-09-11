package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.data.AchievementProgressEntity
import com.adsamcik.tracker.stats.api.metric.MetricKey

/**
 * Metrics whose persisted achievement progress is currently safe to consume as product truth.
 *
 * Steps-owned rows are trusted only when a dedicated qualified Steps projector attached exact
 * READY provenance. The XP metrics remain unavailable because existing ledger/profile rows do not
 * identify which portion came from historical raw Steps goals or session scoring. Derived meta
 * rows are excluded from persisted presentation because older rows may include an unavailable
 * family.
 */
object AchievementMetricQualification {
	val derivedMetaMetrics = setOf(
		MetricKey.ACHIEVEMENTS_UNLOCKED,
		MetricKey.CATEGORIES_COMPLETED,
	)

	private val sourceUnverifiableMetrics = setOf(
		MetricKey.STEPS_TOTAL,
		MetricKey.BEST_DAILY_STEPS,
		MetricKey.PERFECT_WEEKS,
		MetricKey.GOAL_STREAK_DAYS,
		MetricKey.PLAYER_LEVEL,
		MetricKey.BEST_DAY_XP,
		MetricKey.XP_SOURCES_USED,
	)

	private val qualifiedStepsMetrics = setOf(
		MetricKey.STEPS_TOTAL,
		MetricKey.BEST_DAILY_STEPS,
		MetricKey.GOAL_STREAK_DAYS,
		MetricKey.PERFECT_WEEKS,
	)

	fun requiresQualifiedStepsAuthority(metric: MetricKey): Boolean = metric in qualifiedStepsMetrics

	fun isAvailableForEvaluation(metric: MetricKey): Boolean = metric !in sourceUnverifiableMetrics

	/** Whether a catalog series can be shown without inventing unavailable progress as zero. */
	fun isAvailableForProduct(metric: MetricKey, hasTrustedProgress: Boolean): Boolean =
		if (metric in qualifiedStepsMetrics) hasTrustedProgress else isTrustedPersistedProgress(metric)

	fun isTrustedPersistedProgress(
		metric: MetricKey,
		row: AchievementProgressEntity? = null,
	): Boolean {
		if (metric in derivedMetaMetrics) return false
		if (metric in qualifiedStepsMetrics) {
			return row?.metricKey == metric.storageKey &&
				row.authorityKind == AchievementProgressEntity.AUTHORITY_QUALIFIED_STEPS_V1 &&
				row.authorityRevision != null &&
				row.authorityDigest != null &&
				row.authorityState == AchievementProgressEntity.AUTHORITY_STATE_READY &&
				row.qualifiedNotificationClaimedTierIndex != null
		}
		return metric !in sourceUnverifiableMetrics
	}

	/**
	 * Whether the row carries an exact durable unlock timestamp for its currently shown tier.
	 *
	 * Correction-sensitive qualified rows preserve the last genuinely emitted tier-advancement
	 * time across later projection observations. A correction below the claimed high-water hides
	 * that historical unlock until the current tier is again authoritative; it never invents a new
	 * unlock time or notification.
	 */
	fun hasTrustedUnlockTimestamp(
		metric: MetricKey,
		row: AchievementProgressEntity,
	): Boolean {
		if (!isTrustedPersistedProgress(metric, row) || row.lastUnlockedAt == null) return false
		return if (metric in qualifiedStepsMetrics) {
			row.qualifiedNotificationClaimedTierIndex == row.lastTierIndex
		} else {
			true
		}
	}
}
