package com.adsamcik.tracker.stats.api.rule

import com.adsamcik.tracker.stats.api.AchievementTier
import com.adsamcik.tracker.stats.api.metric.MetricKey

sealed interface RuleKind {
	object Achievement : RuleKind
}

sealed interface RuleTarget {
	data class Single(val value: Double, val tier: AchievementTier) : RuleTarget
	data class Tiered(val tiers: Map<AchievementTier, Long>) : RuleTarget
}

data class Rule(
	val id: String,
	val kind: RuleKind,
	val metric: MetricKey,
	val target: RuleTarget,
)
