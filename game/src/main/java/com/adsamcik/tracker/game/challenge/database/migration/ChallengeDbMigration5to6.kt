package com.adsamcik.tracker.game.challenge.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_5_6 = object : Migration(5, 6) {
	override fun migrate(db: SupportSQLiteDatabase) {
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
		db.execSQL(
			"""
			CREATE UNIQUE INDEX IF NOT EXISTS index_xp_ledger_source_source_id
			ON xp_ledger (source, source_id)
			""".trimIndent(),
		)
	}
}
