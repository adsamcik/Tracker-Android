package com.adsamcik.tracker.game.challenge.progression

/**
 * Features that unlock at specific player levels.
 * Locked items visible with lock icon + level requirement.
 */
enum class UnlockableFeature(val requiredLevel: Int) {
	// Level 1 (default)
	BASE_CHALLENGES(1),
	MEDALS(1),
	STREAKS(1),
	TROPHY_CASE(1),

	// Level 2
	PERSONAL_RECORDS(2),

	// Level 3
	OUTRUN_MINI_GAME(3),

	// Level 4
	STREAK_FREEZE(4),

	// Level 5
	SPEED_CHALLENGE(5),

	// Level 6
	TERRITORY_MINI_GAME(6),

	// Level 7
	LIFETIME_STATS(7),

	// Level 8
	CONSISTENCY_CHALLENGE(8),

	// Level 9
	ZEN_WALK_MINI_GAME(9),

	// Level 10
	WEEKLY_RANK(10),
}
