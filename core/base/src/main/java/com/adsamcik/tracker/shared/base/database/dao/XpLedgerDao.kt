package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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

	@Query("SELECT * FROM xp_ledger ORDER BY earned_at DESC LIMIT :limit")
	suspend fun getRecent(limit: Int): List<XpLedgerEntity>
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

}
