package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.adsamcik.tracker.shared.base.database.data.XpLedgerEntity
import com.adsamcik.tracker.shared.base.database.dao.BaseDao
import kotlinx.coroutines.flow.Flow

@Dao
interface XpLedgerDao : BaseDao<XpLedgerEntity> {
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertOrIgnore(entry: XpLedgerEntity): Long

	@Query("SELECT COALESCE(SUM(amount), 0) FROM xp_ledger")
	fun getTotalXp(): Long

	@Query("SELECT COALESCE(SUM(amount), 0) FROM xp_ledger")
	fun observeTotalXp(): Flow<Long>

	@Query("SELECT COALESCE(SUM(amount), 0) FROM xp_ledger WHERE source = :source")
	fun getTotalXpBySource(source: String): Long

	@Query("SELECT COALESCE(SUM(amount), 0) FROM xp_ledger WHERE earned_at >= :since")
	fun getXpSince(since: Long): Long

	/** XP earned inside one explicitly bounded, half-open calendar-day interval. */
	@Query(
		"SELECT COALESCE(SUM(amount), 0) FROM xp_ledger " +
			"WHERE earned_at >= :fromInclusive AND earned_at < :untilExclusive",
	)
	fun getXpBetween(fromInclusive: Long, untilExclusive: Long): Long

	@Query("SELECT * FROM xp_ledger ORDER BY earned_at DESC LIMIT :limit")
	suspend fun getRecent(limit: Int): List<XpLedgerEntity>

	@Query(
		"""
		SELECT * FROM xp_ledger
		WHERE source = :source AND source_key = :sourceKey
		LIMIT 1
		""",
	)
	suspend fun getRevisionedEffect(source: String, sourceKey: String): XpLedgerEntity?

	@Query(
		"""
		UPDATE xp_ledger
		SET amount = :amount,
			earned_at = :earnedAt,
			source_revision = :sourceRevision
		WHERE source = :source
			AND source_key = :sourceKey
			AND source_revision <= :sourceRevision
		""",
	)
	suspend fun updateRevisionedEffect(
		source: String,
		sourceKey: String,
		sourceRevision: Long,
		amount: Int,
		earnedAt: Long,
	): Int

	/**
	 * Applies the latest revision of one derived Steps goal XP effect.
	 *
	 * Zero-XP revisions stay in the ledger as receipts, preventing an older asynchronous projection
	 * from restoring XP after a correction or deletion.
	 */
	@Transaction
	suspend fun applyStepsGoalEffect(
		effectKey: String,
		effectRevision: Long,
		amount: Int,
		earnedAt: Long,
	): Boolean {
		require(effectKey.isNotBlank()) { "Effect key must not be blank" }
		require(effectRevision > 0L) { "Effect revision must be positive" }
		require(amount >= 0) { "Goal XP must not be negative" }
		require(earnedAt >= 0L) { "Earned time must not be negative" }

		val current = getRevisionedEffect(STEPS_GOAL_SOURCE, effectKey)
		if (current != null) {
			val currentRevision = checkNotNull(current.sourceRevision) {
				"Revisioned Steps goal effect is missing its revision"
			}
			if (currentRevision > effectRevision) return false
			return updateRevisionedEffect(
				source = STEPS_GOAL_SOURCE,
				sourceKey = effectKey,
				sourceRevision = effectRevision,
				amount = amount,
				earnedAt = earnedAt,
			) == 1
		}

		return insertOrIgnore(
			XpLedgerEntity(
				amount = amount,
				source = STEPS_GOAL_SOURCE,
				sourceKey = effectKey,
				sourceRevision = effectRevision,
				earnedAt = earnedAt,
			),
		) != -1L
	}
	/** Highest total XP earned within any single LOCAL calendar day. */
	@Query(
		"""
		SELECT COALESCE(MAX(daySum), 0) FROM (
			SELECT SUM(amount) AS daySum FROM xp_ledger
			GROUP BY strftime('%Y-%m-%d', earned_at / 1000, 'unixepoch', 'localtime')
		)
		"""
	)
	suspend fun maxDailyXp(): Long

	/** Number of distinct XP sources (session / mini-game / goal) ever credited. */
	@Query("SELECT COUNT(DISTINCT source) FROM xp_ledger")
	suspend fun countDistinctSources(): Long

	/** `earned_at` timestamps for all ledger rows of a given source, oldest first. */
	@Query("SELECT earned_at FROM xp_ledger WHERE source = :source ORDER BY earned_at ASC")
	suspend fun getEarnedAtBySource(source: String): List<Long>
	@Query("DELETE FROM xp_ledger")
	fun deleteAll()

	private companion object {
		const val STEPS_GOAL_SOURCE = "GOAL"
	}
}
