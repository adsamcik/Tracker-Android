package com.adsamcik.tracker.game.challenge.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.adsamcik.tracker.game.challenge.database.entity.XpLedgerEntity
import com.adsamcik.tracker.shared.base.database.dao.BaseDao
import kotlinx.coroutines.flow.Flow

@Dao
interface XpLedgerDao : BaseDao<XpLedgerEntity> {
	@Query("SELECT COALESCE(SUM(amount), 0) FROM xp_ledger")
	fun getTotalXp(): Long

	@Query("SELECT COALESCE(SUM(amount), 0) FROM xp_ledger")
	fun observeTotalXp(): Flow<Long>

	@Query("SELECT COALESCE(SUM(amount), 0) FROM xp_ledger WHERE source = :source")
	fun getTotalXpBySource(source: String): Long

	@Query("SELECT COALESCE(SUM(amount), 0) FROM xp_ledger WHERE earned_at >= :since")
	fun getXpSince(since: Long): Long

	@Query("SELECT * FROM xp_ledger ORDER BY earned_at DESC LIMIT :limit")
	fun getRecent(limit: Int): List<XpLedgerEntity>
}
