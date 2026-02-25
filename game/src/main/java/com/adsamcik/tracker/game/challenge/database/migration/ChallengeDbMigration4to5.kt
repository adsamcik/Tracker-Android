package com.adsamcik.tracker.game.challenge.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_4_5 = object : Migration(4, 5) {
	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL(
			"""CREATE TABLE IF NOT EXISTS `minigame_score` (
				`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
				`game_id` TEXT NOT NULL,
				`score` REAL NOT NULL,
				`xp_awarded` INTEGER NOT NULL,
				`played_at` INTEGER NOT NULL
			)"""
		)
	}
}
