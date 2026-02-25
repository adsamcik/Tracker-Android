package com.adsamcik.tracker.stats.api

/**
 * Categories for achievement grouping in the UI.
 *
 * Each category corresponds to a distinct gameplay dimension:
 * exploration (cell discovery), physical activity (distance, steps),
 * consistency (streaks), variety (transport modes), and one-time milestones.
 */
enum class AchievementCategory {
	/** Cell and area discovery achievements. */
	EXPLORATION,

	/** Travel distance achievements. */
	DISTANCE,

	/** Step count achievements. */
	STEPS,

	/** Consecutive discovery day/week streak achievements. */
	STREAKS,

	/** Transport mode variety and usage achievements. */
	MODES,

	/** One-time first-occurrence achievements. */
	MILESTONES,
}
