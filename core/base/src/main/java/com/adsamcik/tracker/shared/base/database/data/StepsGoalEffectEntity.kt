package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Durable desired state for one source-qualified Steps goal period.
 *
 * The identity is stable across settings, source corrections, deletion, and retention. Those
 * changes advance [effectRevision] and replace the desired effect instead of creating a second
 * award. Points and XP are independently projected and acknowledge only the exact current
 * revision. Notification claims are deliberately preserved across retraction so a later replay
 * cannot emit the same period notification twice.
 */
@Entity(
	tableName = "steps_goal_effect",
	indices = [
		Index(
			value = ["points_applied_revision", "effect_revision"],
			name = "idx_steps_goal_effect_points_pending",
		),
		Index(
			value = ["xp_applied_revision", "effect_revision"],
			name = "idx_steps_goal_effect_xp_pending",
		),
	],
)
data class StepsGoalEffectEntity(
	@PrimaryKey
	@ColumnInfo(name = "effect_identity") val effectIdentity: String,
	@ColumnInfo(name = "period_kind") val periodKind: String,
	@ColumnInfo(name = "period_start_epoch_day") val periodStartEpochDay: Long,
	@ColumnInfo(name = "period_end_epoch_day") val periodEndEpochDay: Long,
	@ColumnInfo(name = "qualified_through_epoch_day") val qualifiedThroughEpochDay: Long,
	/** Canonical ordered `epochDay=zoneId` lines used for the qualified source read. */
	@ColumnInfo(name = "calendar_authority") val calendarAuthority: String,
	@ColumnInfo(name = "target_steps") val targetSteps: Long,
	@ColumnInfo(name = "weekly_daily_limit_bits") val weeklyDailyLimitBits: Int?,
	@ColumnInfo(name = "decision_state") val decisionState: String,
	@ColumnInfo(name = "unavailable_reason") val unavailableReason: String?,
	@ColumnInfo(name = "qualified_steps") val qualifiedSteps: Long?,
	/** Lowercase SHA-256 over the exact qualified result and structural-calendar authority. */
	@ColumnInfo(name = "source_authority_digest") val sourceAuthorityDigest: String,
	@ColumnInfo(name = "source_evidence_revision") val sourceEvidenceRevision: Long,
	@ColumnInfo(name = "effect_revision") val effectRevision: Long,
	@ColumnInfo(name = "desired_points_micros") val desiredPointsMicros: Long,
	@ColumnInfo(name = "desired_xp") val desiredXp: Int,
	@ColumnInfo(name = "points_applied_revision") val pointsAppliedRevision: Long,
	@ColumnInfo(name = "xp_applied_revision") val xpAppliedRevision: Long,
	@ColumnInfo(name = "notification_claimed_revision") val notificationClaimedRevision: Long?,
	@ColumnInfo(name = "notification_claimed_at_ms") val notificationClaimedAtMs: Long?,
	@ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
) {
	init {
		require(effectIdentity == identity(periodKind, periodStartEpochDay)) {
			"Steps goal effect identity does not match its period"
		}
		require(periodKind in PERIOD_KINDS)
		val expectedEndEpochDay = Math.addExact(
			periodStartEpochDay,
			if (periodKind == PERIOD_DAY) 0L else 6L,
		)
		require(periodEndEpochDay == expectedEndEpochDay)
		require(qualifiedThroughEpochDay in periodStartEpochDay..periodEndEpochDay)
		require(calendarAuthority.isNotBlank())
		require(targetSteps > 0L)
		when (periodKind) {
			PERIOD_DAY -> require(weeklyDailyLimitBits == null)
			PERIOD_WEEK -> requireNotNull(weeklyDailyLimitBits).let { bits ->
				val limit = Float.fromBits(bits)
				require(limit.isFinite() && limit > 0f && limit <= 1f)
			}
		}
		require(decisionState in DECISION_STATES)
		when (decisionState) {
			STATE_READY_COMPLETE,
			STATE_READY_INCOMPLETE -> {
				require(qualifiedSteps != null && qualifiedSteps >= 0L)
				require(unavailableReason == null)
			}
			STATE_MATERIALIZING -> {
				require(qualifiedSteps == null)
				require(unavailableReason == null)
			}
			STATE_UNVERIFIABLE -> {
				require(qualifiedSteps == null)
				require(!unavailableReason.isNullOrBlank())
			}
		}
		require(SHA_256_HEX.matches(sourceAuthorityDigest))
		require(sourceEvidenceRevision >= 0L)
		require(effectRevision > 0L)
		require(desiredPointsMicros >= 0L)
		require(desiredXp >= 0)
		if (decisionState == STATE_READY_COMPLETE) {
			require(desiredPointsMicros > 0L)
		} else {
			require(desiredPointsMicros == 0L && desiredXp == 0)
		}
		require(pointsAppliedRevision in 0L..effectRevision)
		require(xpAppliedRevision in 0L..effectRevision)
		require((notificationClaimedRevision == null) == (notificationClaimedAtMs == null))
		notificationClaimedRevision?.let { revision -> require(revision in 1L..effectRevision) }
		notificationClaimedAtMs?.let { time -> require(time >= 0L) }
		require(updatedAtMs >= 0L)
	}

	/** Semantic equality deliberately excludes observation time and the global evidence counter. */
	fun hasSameDecision(other: StepsGoalEffectEntity): Boolean =
		effectIdentity == other.effectIdentity &&
			periodKind == other.periodKind &&
			periodStartEpochDay == other.periodStartEpochDay &&
			periodEndEpochDay == other.periodEndEpochDay &&
			qualifiedThroughEpochDay == other.qualifiedThroughEpochDay &&
			calendarAuthority == other.calendarAuthority &&
			targetSteps == other.targetSteps &&
			weeklyDailyLimitBits == other.weeklyDailyLimitBits &&
			decisionState == other.decisionState &&
			unavailableReason == other.unavailableReason &&
			qualifiedSteps == other.qualifiedSteps &&
			sourceAuthorityDigest == other.sourceAuthorityDigest &&
			desiredPointsMicros == other.desiredPointsMicros &&
			desiredXp == other.desiredXp

	companion object {
		const val PERIOD_DAY = "DAY"
		const val PERIOD_WEEK = "WEEK"
		const val STATE_READY_COMPLETE = "READY_COMPLETE"
		const val STATE_READY_INCOMPLETE = "READY_INCOMPLETE"
		const val STATE_MATERIALIZING = "MATERIALIZING"
		const val STATE_UNVERIFIABLE = "UNVERIFIABLE"

		private val PERIOD_KINDS = setOf(PERIOD_DAY, PERIOD_WEEK)
		private val DECISION_STATES = setOf(
			STATE_READY_COMPLETE,
			STATE_READY_INCOMPLETE,
			STATE_MATERIALIZING,
			STATE_UNVERIFIABLE,
		)
		private val SHA_256_HEX = Regex("[0-9a-f]{64}")

		fun identity(periodKind: String, periodStartEpochDay: Long): String {
			require(periodKind in PERIOD_KINDS)
			return "steps-goal-v1:$periodKind:$periodStartEpochDay"
		}
	}
}
