package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import com.adsamcik.tracker.shared.base.database.data.StepsGoalEffectEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StepsGoalEffectDao : BaseDao<StepsGoalEffectEntity> {
	@Query("SELECT * FROM steps_goal_effect WHERE effect_identity = :effectIdentity LIMIT 1")
	suspend fun get(effectIdentity: String): StepsGoalEffectEntity?

	@Query("SELECT COUNT(*) FROM steps_goal_effect")
	suspend fun countAll(): Long

	@Query(
		"SELECT * FROM steps_goal_effect " +
			"WHERE points_applied_revision < effect_revision OR xp_applied_revision < effect_revision " +
			"ORDER BY period_start_epoch_day ASC, period_kind ASC",
	)
	fun observePending(): Flow<List<StepsGoalEffectEntity>>

	@Query(
		"UPDATE steps_goal_effect SET source_evidence_revision = :sourceEvidenceRevision, " +
			"updated_at_ms = :updatedAtMs WHERE effect_identity = :effectIdentity " +
			"AND effect_revision = :expectedEffectRevision " +
			"AND source_evidence_revision <= :sourceEvidenceRevision",
	)
	suspend fun refreshObservation(
		effectIdentity: String,
		expectedEffectRevision: Long,
		sourceEvidenceRevision: Long,
		updatedAtMs: Long,
	): Int

	@Query(
		"UPDATE steps_goal_effect SET points_applied_revision = :effectRevision " +
			"WHERE effect_identity = :effectIdentity AND effect_revision = :effectRevision",
	)
	suspend fun markPointsApplied(effectIdentity: String, effectRevision: Long): Int

	@Query(
		"UPDATE steps_goal_effect SET xp_applied_revision = :effectRevision " +
			"WHERE effect_identity = :effectIdentity AND effect_revision = :effectRevision",
	)
	suspend fun markXpApplied(effectIdentity: String, effectRevision: Long): Int

	@Query(
		"UPDATE steps_goal_effect SET notification_claimed_revision = :effectRevision, " +
			"notification_claimed_at_ms = :claimedAtMs " +
		"WHERE effect_identity = :effectIdentity AND effect_revision = :effectRevision " +
			"AND decision_state = '${StepsGoalEffectEntity.STATE_READY_COMPLETE}' " +
			"AND notification_claimed_revision IS NULL AND :claimedAtMs >= 0",
	)
	suspend fun claimNotification(
		effectIdentity: String,
		effectRevision: Long,
		claimedAtMs: Long,
	): Int

	@Query("DELETE FROM steps_goal_effect")
	fun deleteAll()

	/**
	 * Installs one decision or advances its source-specific revision.
	 *
	 * The caller supplies a pristine revision-one candidate. A lower global evidence revision is
	 * stale and cannot replace newer durable authority. Semantically identical observation only
	 * refreshes diagnostic provenance; it does not re-arm points, XP, or notification work.
	 */
	@Transaction
	suspend fun recordDecision(candidate: StepsGoalEffectEntity): StepsGoalEffectWriteResult {
		require(candidate.effectRevision == 1L)
		require(candidate.pointsAppliedRevision == 0L && candidate.xpAppliedRevision == 0L)
		require(candidate.notificationClaimedRevision == null)
		val current = get(candidate.effectIdentity)
		if (current == null) {
			return if (insert(candidate) == -1L) {
				StepsGoalEffectWriteResult.STALE
			} else {
				StepsGoalEffectWriteResult.INSERTED
			}
		}
		if (candidate.sourceEvidenceRevision < current.sourceEvidenceRevision) {
			return StepsGoalEffectWriteResult.STALE
		}
		if (current.hasSameDecision(candidate)) {
			if (candidate.sourceEvidenceRevision > current.sourceEvidenceRevision ||
				candidate.updatedAtMs > current.updatedAtMs
			) {
				refreshObservation(
					effectIdentity = current.effectIdentity,
					expectedEffectRevision = current.effectRevision,
					sourceEvidenceRevision = candidate.sourceEvidenceRevision,
					updatedAtMs = maxOf(current.updatedAtMs, candidate.updatedAtMs),
				)
			}
			return StepsGoalEffectWriteResult.UNCHANGED
		}
		check(current.effectRevision < Long.MAX_VALUE) { "Steps goal effect revision exhausted" }
		update(
			candidate.copy(
				effectRevision = current.effectRevision + 1L,
				firstCompletedAtMs = current.firstCompletedAtMs ?: candidate.firstCompletedAtMs,
				pointsAppliedRevision = current.pointsAppliedRevision,
				xpAppliedRevision = current.xpAppliedRevision,
				notificationClaimedRevision = current.notificationClaimedRevision,
				notificationClaimedAtMs = current.notificationClaimedAtMs,
				updatedAtMs = maxOf(current.updatedAtMs, candidate.updatedAtMs),
			),
		)
		return StepsGoalEffectWriteResult.REVISED
	}
}

enum class StepsGoalEffectWriteResult {
	INSERTED,
	UNCHANGED,
	REVISED,
	STALE,
}
