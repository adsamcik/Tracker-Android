package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import com.adsamcik.tracker.shared.base.database.data.StepsGoalEffectEntity
import kotlinx.coroutines.flow.Flow

/**
 * Reads and persists Steps goal effects and their source/calendar revision bindings for correction
 * and deletion repair.
 */
@Dao
interface StepsGoalEffectDao : BaseDao<StepsGoalEffectEntity> {
	/**
	 * Reads `steps_goal_effect` rows. Query conditions include `effect_identity = :effectIdentity`.
	 * Returns at most 1 row. Returns null when no row matches.
	 */
	@Query("SELECT * FROM steps_goal_effect WHERE effect_identity = :effectIdentity LIMIT 1")
	suspend fun get(effectIdentity: String): StepsGoalEffectEntity?

	/**
	 * Counts rows in `steps_goal_effect`.
	 */
	@Query("SELECT COUNT(*) FROM steps_goal_effect")
	suspend fun countAll(): Long

	/**
	 * Reads `steps_goal_effect` rows. Query conditions include `period_start_epoch_day <= :epochDay`,
	 * `qualified_through_epoch_day >= :epochDay`, `source_evidence_revision <
	 * :sourceEvidenceRevision`, `calendar_authority !=
	 * '${StepsGoalEffectEntity.CALENDAR_AUTHORITY_UNAVAILABLE}'`. Orders by `period_kind ASC,
	 * period_start_epoch_day ASC`. Returns at most `limit` rows. Day keys use civil-calendar epoch
	 * days.
	 */
	@Query(
		"SELECT * FROM steps_goal_effect " +
			"WHERE period_start_epoch_day <= :epochDay " +
			"AND qualified_through_epoch_day >= :epochDay " +
			"AND source_evidence_revision < :sourceEvidenceRevision " +
			"AND calendar_authority != '${StepsGoalEffectEntity.CALENDAR_AUTHORITY_UNAVAILABLE}' " +
			"ORDER BY period_kind ASC, period_start_epoch_day ASC LIMIT :limit",
	)
	suspend fun getStaleAffectedByDay(
		epochDay: Long,
		sourceEvidenceRevision: Long,
		limit: Int,
	): List<StepsGoalEffectEntity>

	/**
	 * Reports whether a row exists within the query scope. Query conditions include
	 * `period_start_epoch_day <= :epochDay`, `qualified_through_epoch_day >= :epochDay`,
	 * `source_evidence_revision < :sourceEvidenceRevision`, `calendar_authority !=
	 * '${StepsGoalEffectEntity.CALENDAR_AUTHORITY_UNAVAILABLE}'`. Day keys use civil-calendar epoch
	 * days.
	 */
	@Query(
		"SELECT EXISTS(SELECT 1 FROM steps_goal_effect " +
			"WHERE period_start_epoch_day <= :epochDay " +
			"AND qualified_through_epoch_day >= :epochDay " +
			"AND source_evidence_revision < :sourceEvidenceRevision " +
			"AND calendar_authority != '${StepsGoalEffectEntity.CALENDAR_AUTHORITY_UNAVAILABLE}')",
	)
	suspend fun hasStaleAffectedByDay(
		epochDay: Long,
		sourceEvidenceRevision: Long,
	): Boolean

	/**
	 * Reads `steps_goal_effect` rows. Query conditions include `period_start_epoch_day <=
	 * :lastEpochDayInclusive`, `qualified_through_epoch_day >= :firstEpochDay`,
	 * `source_evidence_revision < :sourceEvidenceRevision`, `calendar_authority !=
	 * '${StepsGoalEffectEntity.CALENDAR_AUTHORITY_UNAVAILABLE}'`. Orders by `period_start_epoch_day
	 * ASC, period_kind ASC`.
	 */
	@Query(
		"SELECT * FROM steps_goal_effect " +
			"WHERE period_start_epoch_day <= :lastEpochDayInclusive " +
			"AND qualified_through_epoch_day >= :firstEpochDay " +
			"AND source_evidence_revision < :sourceEvidenceRevision " +
			"AND calendar_authority != '${StepsGoalEffectEntity.CALENDAR_AUTHORITY_UNAVAILABLE}' " +
			"ORDER BY period_start_epoch_day ASC, period_kind ASC",
	)
	suspend fun getStaleAffectedByDayRange(
		firstEpochDay: Long,
		lastEpochDayInclusive: Long,
		sourceEvidenceRevision: Long,
	): List<StepsGoalEffectEntity>

	/**
	 * Reads `steps_goal_effect` rows. Query conditions include `source_evidence_revision <
	 * :sourceEvidenceRevision`, `calendar_authority !=
	 * '${StepsGoalEffectEntity.CALENDAR_AUTHORITY_UNAVAILABLE}'`. Orders by `period_start_epoch_day
	 * ASC, period_kind ASC`.
	 */
	@Query(
		"SELECT * FROM steps_goal_effect " +
			"WHERE source_evidence_revision < :sourceEvidenceRevision " +
			"AND calendar_authority != '${StepsGoalEffectEntity.CALENDAR_AUTHORITY_UNAVAILABLE}' " +
			"ORDER BY period_start_epoch_day ASC, period_kind ASC",
	)
	suspend fun getAllStale(
		sourceEvidenceRevision: Long,
	): List<StepsGoalEffectEntity>

	/**
	 * Reads `steps_goal_effect` rows. Query conditions include `period_kind =
	 * '${StepsGoalEffectEntity.PERIOD_DAY}'`. Orders by `period_start_epoch_day ASC`.
	 */
	@Query(
		"SELECT * FROM steps_goal_effect " +
			"WHERE period_kind = '${StepsGoalEffectEntity.PERIOD_DAY}' " +
			"ORDER BY period_start_epoch_day ASC",
	)
	suspend fun getDailyDecisions(): List<StepsGoalEffectEntity>

	/**
	 * Observes `steps_goal_effect` rows. Orders by `period_start_epoch_day ASC, period_kind ASC`.
	 */
	@Query(
		"SELECT * FROM steps_goal_effect " +
			"WHERE points_applied_revision < effect_revision OR xp_applied_revision < effect_revision " +
			"ORDER BY period_start_epoch_day ASC, period_kind ASC",
	)
	fun observePending(): Flow<List<StepsGoalEffectEntity>>

	/**
	 * Observes `steps_goal_effect` rows. Query conditions include `decision_state =
	 * '${StepsGoalEffectEntity.STATE_READY_COMPLETE}'`. Orders by `period_start_epoch_day ASC,
	 * period_kind ASC`.
	 */
	@Query(
		"SELECT * FROM steps_goal_effect " +
			"WHERE points_applied_revision < effect_revision " +
			"OR xp_applied_revision < effect_revision " +
			"OR (decision_state = '${StepsGoalEffectEntity.STATE_READY_COMPLETE}' " +
			"AND notification_claimed_revision IS NULL) " +
			"ORDER BY period_start_epoch_day ASC, period_kind ASC",
	)
	fun observeActionable(): Flow<List<StepsGoalEffectEntity>>

	/**
	 * Updates `steps_goal_effect` rows matching the supplied scope. Query conditions include
	 * `effect_identity = :effectIdentity`, `effect_revision = :expectedEffectRevision`,
	 * `source_evidence_revision <= :sourceEvidenceRevision`. Returns the affected row count.
	 */
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

	/**
	 * Updates `steps_goal_effect` rows matching the supplied scope. Query conditions include
	 * `effect_identity = :effectIdentity`, `effect_revision = :effectRevision`. Returns the affected
	 * row count.
	 */
	@Query(
		"UPDATE steps_goal_effect SET points_applied_revision = :effectRevision " +
			"WHERE effect_identity = :effectIdentity AND effect_revision = :effectRevision",
	)
	suspend fun markPointsApplied(effectIdentity: String, effectRevision: Long): Int

	/**
	 * Updates `steps_goal_effect` rows matching the supplied scope. Query conditions include
	 * `effect_identity = :effectIdentity`, `effect_revision = :effectRevision`. Returns the affected
	 * row count.
	 */
	@Query(
		"UPDATE steps_goal_effect SET xp_applied_revision = :effectRevision " +
			"WHERE effect_identity = :effectIdentity AND effect_revision = :effectRevision",
	)
	suspend fun markXpApplied(effectIdentity: String, effectRevision: Long): Int

	/**
	 * Updates `steps_goal_effect` rows matching the supplied scope. Query conditions include
	 * `effect_identity = :effectIdentity`, `effect_revision = :effectRevision`, `decision_state =
	 * '${StepsGoalEffectEntity.STATE_READY_COMPLETE}'`, `claimedAtMs >= 0`. Returns the affected row
	 * count.
	 */
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

	/**
	 * Deletes `steps_goal_effect` rows.
	 */
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

/**
 * Outcome of writing a goal effect: new, unchanged, revised or stale relative to saved evidence.
 */
enum class StepsGoalEffectWriteResult {
	INSERTED,
	UNCHANGED,
	REVISED,
	STALE,
}
