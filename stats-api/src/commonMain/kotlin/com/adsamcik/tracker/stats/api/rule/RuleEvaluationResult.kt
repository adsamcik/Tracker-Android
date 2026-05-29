package com.adsamcik.tracker.stats.api.rule

import com.adsamcik.tracker.stats.api.AchievementTier

/**
 * Result of evaluating a single [RuleInstance] against a current metric value.
 *
 * Sealed so the engine can dispatch on result type without leaking implementation details
 * about what each kind means (achievement tier unlock vs. challenge completion vs. progress).
 *
 * Battery note: when the new value equals [RuleInstance.previousValue], the evaluator returns
 * [Unchanged] WITHOUT examining the target — so the hot path is a single equality check.
 */
sealed interface RuleEvaluationResult {
	val instance: RuleInstance
	val currentValue: Long

	/** No change from the previous observation. Event-producing consumers should not emit. */
	data class Unchanged(
		override val instance: RuleInstance,
		override val currentValue: Long,
	) : RuleEvaluationResult

	/**
	 * Achievement-kind rules only. The user crossed one or more tier thresholds; [unlocked] is
	 * the highest newly unlocked tier (callers needing per-tier XP should compute the list of
	 * crossed tiers from [previousTier] in [instance] up to [unlocked]).
	 */
	data class TierUnlocked(
		override val instance: RuleInstance,
		override val currentValue: Long,
		val unlocked: AchievementTier,
		/** Target value of the next tier above [unlocked], or null when [unlocked] is max. */
		val nextTierTarget: Long?,
	) : RuleEvaluationResult

	/**
	 * Achievement-kind rules only. Metric value moved within the same tier (or before any tier).
	 * [nextTierTarget] is the target for the next tier, or null when already at max tier.
	 * Use for progress bars in the UI.
	 */
	data class ProgressUpdated(
		override val instance: RuleInstance,
		override val currentValue: Long,
		val nextTierTarget: Long?,
	) : RuleEvaluationResult

	/** Challenge-kind rules only. Metric crossed the single target — challenge is done. */
	data class Completed(
		override val instance: RuleInstance,
		override val currentValue: Long,
	) : RuleEvaluationResult

	/**
	 * Challenge-kind rules only. Metric moved but did not cross the target.
	 */
	data class ChallengeProgress(
		override val instance: RuleInstance,
		override val currentValue: Long,
	) : RuleEvaluationResult
}
