package com.adsamcik.tracker.game.challenge.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_5_6 = object : Migration(5, 6) {
	override fun migrate(db: SupportSQLiteDatabase) {
		// 1. Dedup xp_ledger rows that share (source, source_id) before creating unique index
		db.execSQL(
			"""
			DELETE FROM xp_ledger
			WHERE source_id IS NOT NULL
			  AND id NOT IN (
				SELECT MIN(id)
				FROM xp_ledger
				WHERE source_id IS NOT NULL
				GROUP BY source, source_id
			  )
			""".trimIndent(),
		)
		// 2. Unique index for idempotent XP inserts (matches XpLedgerEntity)
		db.execSQL(
			"""
			CREATE UNIQUE INDEX IF NOT EXISTS index_xp_ledger_source_source_id
			ON xp_ledger (source, source_id)
			""".trimIndent(),
		)
		// 3. Performance indices for hot challenge/history queries
		db.execSQL("CREATE INDEX IF NOT EXISTS `index_challenge_is_completed_end_time` ON `challenge` (`is_completed`, `end_time`)")
		db.execSQL("CREATE INDEX IF NOT EXISTS `index_challenge_history_outcome` ON `challenge_history` (`outcome`)")
		db.execSQL("CREATE INDEX IF NOT EXISTS `index_challenge_history_medal` ON `challenge_history` (`medal`)")
	}
}
