package com.adsamcik.tracker.game.challenge.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration from v3 to v4: adds progression tables
 * (challenge_history, xp_ledger, player_profile, challenge_streak, challenge_personal_record).
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL("""
			CREATE TABLE IF NOT EXISTS `challenge_history` (
				`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
				`challenge_type` TEXT NOT NULL,
				`difficulty` TEXT NOT NULL,
				`start_time` INTEGER NOT NULL,
				`end_time` INTEGER NOT NULL,
				`outcome` TEXT NOT NULL,
				`completed_at` INTEGER,
				`progress_value` REAL NOT NULL,
				`target_value` REAL NOT NULL,
				`medal` TEXT,
				`xp_awarded` INTEGER NOT NULL DEFAULT 0,
				`original_challenge_id` INTEGER
			)
		""".trimIndent())

		db.execSQL("""
			CREATE TABLE IF NOT EXISTS `xp_ledger` (
				`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
				`amount` INTEGER NOT NULL,
				`source` TEXT NOT NULL,
				`source_id` INTEGER,
				`earned_at` INTEGER NOT NULL
			)
		""".trimIndent())

		db.execSQL("""
			CREATE TABLE IF NOT EXISTS `player_profile` (
				`id` INTEGER NOT NULL PRIMARY KEY,
				`total_xp` INTEGER NOT NULL DEFAULT 0,
				`level` INTEGER NOT NULL DEFAULT 1,
				`xp_into_current_level` INTEGER NOT NULL DEFAULT 0,
				`xp_for_next_level` INTEGER NOT NULL DEFAULT 30
			)
		""".trimIndent())

		db.execSQL("""
			CREATE TABLE IF NOT EXISTS `challenge_streak` (
				`id` INTEGER NOT NULL PRIMARY KEY,
				`current_count` INTEGER NOT NULL DEFAULT 0,
				`best_count` INTEGER NOT NULL DEFAULT 0,
				`last_completion_time` INTEGER NOT NULL DEFAULT 0,
				`freeze_count` INTEGER NOT NULL DEFAULT 0
			)
		""".trimIndent())

		db.execSQL("""
			CREATE TABLE IF NOT EXISTS `challenge_personal_record` (
				`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
				`challenge_type` TEXT NOT NULL,
				`metric` TEXT NOT NULL,
				`value` REAL NOT NULL,
				`history_id` INTEGER,
				`achieved_at` INTEGER NOT NULL
			)
		""".trimIndent())
	}
}
