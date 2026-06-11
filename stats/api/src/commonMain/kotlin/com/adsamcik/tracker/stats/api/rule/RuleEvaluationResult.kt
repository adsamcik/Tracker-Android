package com.adsamcik.tracker.stats.api.rule

import com.adsamcik.tracker.stats.api.AchievementTier

sealed interface RuleEvaluationResult {
	val instance: RuleInstance
	val currentValue: Long
	data class Unchanged(override val instance: RuleInstance, override val currentValue: Long) : RuleEvaluationResult
	data class TierUnlocked(override val instance: RuleInstance, override val currentValue: Long, val unlocked: AchievementTier, val nextTierTarget: Long?) : RuleEvaluationResult
	data class ProgressUpdated(override val instance: RuleInstance, override val currentValue: Long, val nextTierTarget: Long?) : RuleEvaluationResult
}
