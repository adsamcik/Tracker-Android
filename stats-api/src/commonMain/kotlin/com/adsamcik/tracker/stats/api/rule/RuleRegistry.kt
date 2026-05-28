package com.adsamcik.tracker.stats.api.rule

/**
 * Source of [RuleInstance]s for the unified rule engine.
 *
 * Implementations expose two views:
 *  1. [allInstances] — full enumeration, used at startup or for a forced reconciliation.
 *  2. [instancesAffectedByTables] — fast-path lookup keyed by which pre-aggregated
 *     tables have changed since the last flush. Used by the dirty-aware signal processor
 *     so a write to (say) `daily_summary` only re-evaluates rules whose metric reads
 *     `daily_summary`, NOT the entire catalog.
 *
 * The registry is composite-friendly: a top-level registry can compose
 * `AchievementRuleRegistry` (static catalog) and `ChallengeRuleRegistry` (dynamic from DB)
 * without either side knowing about the other.
 */
interface RuleRegistry {
	/**
	 * All currently-evaluable rule instances. May trigger a DB read for dynamic registries
	 * (e.g. loading active challenges); the dirty-aware path should NOT call this on every
	 * flush.
	 */
	suspend fun allInstances(): List<RuleInstance>

	/**
	 * Subset of [allInstances] whose [Rule.metric] reads at least one of [dirtyTables].
	 * Returns the empty list if no rule is affected — in which case the caller should
	 * short-circuit and skip the flush entirely.
	 *
	 * Implementations resolve metric → tables via
	 * [com.adsamcik.tracker.stats.api.metric.MetricKeys.sourceTables].
	 */
	suspend fun instancesAffectedByTables(dirtyTables: Set<String>): List<RuleInstance>
}
