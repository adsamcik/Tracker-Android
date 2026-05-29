package com.adsamcik.tracker.shared.base.database.data

/**
 * Stable challenge type names persisted in the challenge table.
 * Additions are append-only; never rename existing values without a migration.
 */
enum class ChallengeType {
	Explorer,
	WalkDistance,
	Step,
	ActiveTime,
	Speed,
	Consistency
}
