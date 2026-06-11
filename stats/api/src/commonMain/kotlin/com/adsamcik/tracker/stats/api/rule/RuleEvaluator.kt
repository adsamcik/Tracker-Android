package com.adsamcik.tracker.stats.api.rule

object RuleEvaluator {
	fun evaluate(instance: RuleInstance, currentValue: Long): RuleEvaluationResult {
		val previous = instance.previousValue
		if (previous != null && previous == currentValue) return RuleEvaluationResult.Unchanged(instance, currentValue)
		return when (instance.rule.kind) {
			RuleKind.Achievement -> evaluateAchievement(instance, currentValue)
		}
	}

	private fun evaluateAchievement(instance: RuleInstance, currentValue: Long): RuleEvaluationResult = when (val target = instance.rule.target) {
		is RuleTarget.Single -> if (currentValue.toDouble() >= target.value && instance.previousTier == null) {
			RuleEvaluationResult.TierUnlocked(instance, currentValue, target.tier, null)
		} else {
			RuleEvaluationResult.ProgressUpdated(instance, currentValue, if (instance.previousTier == null) target.value.toLong() else null)
		}
		is RuleTarget.Tiered -> evaluateTiered(instance, currentValue, target)
	}

	private fun evaluateTiered(instance: RuleInstance, currentValue: Long, target: RuleTarget.Tiered): RuleEvaluationResult {
		val ordered = target.tiers.entries.sortedBy { it.value }
		val previousTier = instance.previousTier
		val unlocked = ordered.lastOrNull { (tier, threshold) -> currentValue >= threshold && (previousTier == null || tier.ordinal > previousTier.ordinal) }
		return if (unlocked != null) {
			RuleEvaluationResult.TierUnlocked(instance, currentValue, unlocked.key, ordered.firstOrNull { it.key.ordinal > unlocked.key.ordinal }?.value)
		} else {
			RuleEvaluationResult.ProgressUpdated(instance, currentValue, ordered.firstOrNull { (tier, _) -> previousTier == null || tier.ordinal > previousTier.ordinal }?.value)
		}
	}
}
