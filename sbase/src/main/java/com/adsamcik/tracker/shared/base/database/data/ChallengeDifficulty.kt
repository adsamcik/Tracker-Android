package com.adsamcik.tracker.shared.base.database.data

/**
 * Stable challenge difficulty names persisted in the challenge table.
 * Additions are append-only; never rename existing values without a migration.
 */
enum class ChallengeDifficulty {
	VERY_EASY,
	EASY,
	MEDIUM,
	HARD,
	VERY_HARD
}
