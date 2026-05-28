package com.adsamcik.tracker.stats.api.rule

import com.adsamcik.tracker.stats.api.AchievementTier

/**
 * Unified rule abstraction subsuming achievements (cumulative + tiered) and challenges
 * (windowed + single target).
 *
 * **Scope caveat.** The current model is a closed two-domain shape: [RuleKind] enumerates
 * exactly `Achievement` + `Challenge`, [RuleTarget.Tiered] is keyed by [AchievementTier],
 * and [RuleEvaluationResult] carries domain-specific variants. Adding a third kind
 * (`Streak`, `Milestone`, `Goal`) is NOT a drop-in extension — it requires editing this
 * sealed family AND every exhaustive consumer. If a third kind is needed, first
 * generalise: replace [RuleTarget.Tiered] with a domain-neutral tier identifier and split
 * domain interpretation into per-kind result handlers.
 *
 * @property id Stable string identifier. For static achievement rules, this is the
 *   [com.adsamcik.tracker.stats.api.AchievementDefinition.id]. For dynamic challenge rules
 *   instantiated per row, this is `"challenge:$entityId"`.
 * @property kind Discriminator — drives evaluation and progression semantics.
 * @property metric Shared metric key from [com.adsamcik.tracker.stats.api.metric.MetricKeys].
 *   Used by the registry to index "which rules are affected when table X changes".
 * @property target Threshold(s) the metric value is compared against.
 * @property tuningVersion Bumped when balance changes so historical rows aren't retroactively
 *   warped.
 */
data class Rule(
	val id: String,
	val kind: RuleKind,
	val metric: String,
	val target: RuleTarget,
	val tuningVersion: Int = CURRENT_TUNING_VERSION,
) {
	companion object {
		const val CURRENT_TUNING_VERSION: Int = 2
	}
}

/**
 * Kind of rule. Drives time window semantics (cumulative vs. interval) and
 * progression event emission (TierUnlocked vs. Completed). Closed over the two
 * currently-supported domains; see [Rule] KDoc for extensibility caveats.
 */
sealed interface RuleKind {
	object Achievement : RuleKind
	object Challenge : RuleKind
}

/**
 * Target value(s) for rule evaluation. Sealed so the [RuleEvaluator] can dispatch on shape
 * without leaking implementation details.
 */
sealed interface RuleTarget {
	/**
	 * Single target — value-based completion. Used by challenges where you complete
	 * exactly once on crossing [value].
	 */
	data class Single(val value: Double) : RuleTarget

	/**
	 * Tiered targets — multiple thresholds in increasing order. Currently keyed by
	 * [AchievementTier] because the only consumer is the achievement engine. A
	 * future domain-neutral tier identifier would be a small breaking change to
	 * this and to [RuleEvaluator.evaluateAchievement].
	 */
	data class Tiered(val tiers: Map<AchievementTier, Long>) : RuleTarget
}
