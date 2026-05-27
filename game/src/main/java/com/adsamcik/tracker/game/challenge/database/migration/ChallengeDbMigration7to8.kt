package com.adsamcik.tracker.game.challenge.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v7 → v8: add `index_challenge_history_completed_at` on `challenge_history(completed_at)`.
 *
 * The trophy case, history list, and `getCompleted`/`getByMedal` queries all `ORDER BY completed_at DESC`.
 * Without an index this is a full table scan + sort; with one it's a backward index walk.
 * Materializes once history grows past ~hundreds of rows (p5-2).
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL(
			"CREATE INDEX IF NOT EXISTS `index_challenge_history_completed_at` " +
				"ON `challenge_history` (`completed_at`)"
		)
	}
}
