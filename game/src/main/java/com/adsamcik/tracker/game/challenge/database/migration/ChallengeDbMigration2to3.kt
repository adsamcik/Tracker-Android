package com.adsamcik.tracker.game.challenge.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration from v2 to v3: adds unified challenge table.
 * Old type-specific tables are kept for data migration in the cleanup phase.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL(
			"""
			CREATE TABLE IF NOT EXISTS `challenge` (
				`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
				`type` TEXT NOT NULL,
				`start_time` INTEGER NOT NULL,
				`end_time` INTEGER NOT NULL,
				`difficulty` TEXT NOT NULL,
				`required_value` REAL NOT NULL,
				`current_value` REAL NOT NULL DEFAULT 0.0,
				`is_completed` INTEGER NOT NULL DEFAULT 0,
				`extra_json` TEXT
			)
			""".trimIndent()
		)

		migrateExplorerChallenges(db)
		migrateWalkDistanceChallenges(db)
		migrateStepChallenges(db)
		migrateActiveTimeChallenges(db)
	}

	private fun migrateExplorerChallenges(db: SupportSQLiteDatabase) {
		db.execSQL(
			"""
			INSERT INTO `challenge` (`type`, `start_time`, `end_time`, `difficulty`, `required_value`, `current_value`, `is_completed`)
			SELECT 'Explorer',
				e.start_time,
				e.end_time,
				CASE e.difficulty
					WHEN 0 THEN 'VERY_EASY'
					WHEN 1 THEN 'EASY'
					WHEN 2 THEN 'MEDIUM'
					WHEN 3 THEN 'HARD'
					WHEN 4 THEN 'VERY_HARD'
					ELSE 'MEDIUM'
				END,
				CAST(c.requiredLocationCount AS REAL),
				CAST(c.locationCount AS REAL),
				c.completed
			FROM challenge_explorer c
			INNER JOIN challenge_entry e ON c.entry_id = e.id
			""".trimIndent()
		)
	}

	private fun migrateWalkDistanceChallenges(db: SupportSQLiteDatabase) {
		db.execSQL(
			"""
			INSERT INTO `challenge` (`type`, `start_time`, `end_time`, `difficulty`, `required_value`, `current_value`, `is_completed`)
			SELECT 'WalkDistance',
				e.start_time,
				e.end_time,
				CASE e.difficulty
					WHEN 0 THEN 'VERY_EASY'
					WHEN 1 THEN 'EASY'
					WHEN 2 THEN 'MEDIUM'
					WHEN 3 THEN 'HARD'
					WHEN 4 THEN 'VERY_HARD'
					ELSE 'MEDIUM'
				END,
				c.requiredDistanceInM,
				c.distanceInM,
				c.completed
			FROM challenge_walk_distance c
			INNER JOIN challenge_entry e ON c.entry_id = e.id
			""".trimIndent()
		)
	}

	private fun migrateStepChallenges(db: SupportSQLiteDatabase) {
		db.execSQL(
			"""
			INSERT INTO `challenge` (`type`, `start_time`, `end_time`, `difficulty`, `required_value`, `current_value`, `is_completed`)
			SELECT 'Step',
				e.start_time,
				e.end_time,
				CASE e.difficulty
					WHEN 0 THEN 'VERY_EASY'
					WHEN 1 THEN 'EASY'
					WHEN 2 THEN 'MEDIUM'
					WHEN 3 THEN 'HARD'
					WHEN 4 THEN 'VERY_HARD'
					ELSE 'MEDIUM'
				END,
				CAST(c.requiredStepCount AS REAL),
				CAST(c.stepCount AS REAL),
				c.completed
			FROM challenge_step c
			INNER JOIN challenge_entry e ON c.entry_id = e.id
			""".trimIndent()
		)
	}

	private fun migrateActiveTimeChallenges(db: SupportSQLiteDatabase) {
		db.execSQL(
			"""
			INSERT INTO `challenge` (`type`, `start_time`, `end_time`, `difficulty`, `required_value`, `current_value`, `is_completed`)
			SELECT 'ActiveTime',
				e.start_time,
				e.end_time,
				CASE e.difficulty
					WHEN 0 THEN 'VERY_EASY'
					WHEN 1 THEN 'EASY'
					WHEN 2 THEN 'MEDIUM'
					WHEN 3 THEN 'HARD'
					WHEN 4 THEN 'VERY_HARD'
					ELSE 'MEDIUM'
				END,
				CAST(c.requiredActiveTimeInMinutes AS REAL),
				CAST(c.activeTimeInMinutes AS REAL),
				c.completed
			FROM challenge_active_time c
			INNER JOIN challenge_entry e ON c.entry_id = e.id
			""".trimIndent()
		)
	}
}
