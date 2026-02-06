package com.adsamcik.tracker.stats.api

/**
 * Quality tiers for S2 cell exploration. Quality only upgrades, never downgrades.
 *
 * Progresses based on time spent and interaction type within a cell.
 */
enum class DiscoveryQuality(val multiplier: Double) {
	/** Passed through at speed (vehicle, transit). */
	PASSED_THROUGH(0.5),

	/** Cycled through the cell. */
	CYCLED_THROUGH(0.7),

	/** Walked/ran through the cell. */
	TRAVERSED_ON_FOOT(1.0),

	/** Spent significant time on foot (>5 min). */
	EXPLORED(1.5),

	/** Visited on multiple occasions or extended exploration. */
	THOROUGHLY_EXPLORED(2.0);
}
