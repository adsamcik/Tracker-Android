package com.adsamcik.tracker.shared.base.database.analysis

/**
 * Reversible rollout gate for the legacy persisted presence grid.
 *
 * Existing tables are intentionally retained until historical-source availability has been
 * audited. Keeping this false prevents retention work from expanding the grid for users who never
 * open the location-history heatmap.
 */
object LegacyPresencePersistence {
	const val PROACTIVE_COMPACTION_ENABLED: Boolean = false
}
