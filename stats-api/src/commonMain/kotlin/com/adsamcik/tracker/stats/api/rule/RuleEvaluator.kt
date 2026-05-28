package com.adsamcik.tracker.stats.api.rule

import com.adsamcik.tracker.stats.api.AchievementTier

/**
 * Pure stateless evaluator of [RuleInstance]s.
 *
 * Subsumes [com.adsamcik.tracker.stats.api.achievement.AchievementEvaluator] for
 * challenge-kind rules and is the comparison primitive shared with the (still pending)
 * achievement migration. Centralising the value-vs-target comparison in one place means
 * the dirty-aware signal processor and the session-end engine both call the same logic.
 *
 * Adding a new rule kind requires editing [RuleKind], [RuleTarget], [RuleEvaluationResult],
 * the [evaluate] dispatch, and every exhaustive consumer; it is not the plug-in extension
 * the older KDoc implied. See [Rule] KDoc for the extensibility caveat.
 *
 * Thread-safety: stateless; safe from any thread.
 */
class RuleEvaluator {

	/**
	 * Evaluate [instance] against [currentValue]. Returns the appropriate result variant —
	 * see [RuleEvaluationResult] for semantics. Battery-critical fast path: if
	 * `currentValue == instance.previousValue`, returns [RuleEvaluationResult.Unchanged]
	 * without examining the target.
	 */
	fun evaluate(instance: RuleInstance, currentValue: Long): RuleEvaluationResult {
		if (instance.previousValue != null && currentValue == instance.previousValue) {
			return RuleEvaluationResult.Unchanged(instance, currentValue)
		}

		return when (instance.rule.kind) {
			RuleKind.Achievement -> evaluateAchievement(instance, currentValue)
			RuleKind.Challenge -> evaluateChallenge(instance, currentValue)
		}
	}

	private fun evaluateAchievement(
		instance: RuleInstance,
		currentValue: Long,
	): RuleEvaluationResult {
		val target = instance.rule.target
		check(target is RuleTarget.Tiered) {
			"Achievement rule ${instance.rule.id} must have RuleTarget.Tiered"
		}
		val newTier = highestUnlockedTier(target.tiers, currentValue)
		val prevTier = instance.previousTier
		val crossedUp = newTier != null && (prevTier == null || newTier.ordinal > prevTier.ordinal)
		return if (crossedUp) {
			RuleEvaluationResult.TierUnlocked(instance, currentValue, newTier!!)
		} else {
			RuleEvaluationResult.ProgressUpdated(instance, currentValue)
		}
	}

	private fun evaluateChallenge(
		instance: RuleInstance,
		currentValue: Long,
	): RuleEvaluationResult {
		val target = instance.rule.target
		check(target is RuleTarget.Single) {
			"Challenge rule ${instance.rule.id} must have RuleTarget.Single"
		}
		val justCompleted = currentValue.toDouble() >= target.value
		return if (justCompleted) {
			RuleEvaluationResult.Completed(instance, currentValue)
		} else {
			RuleEvaluationResult.ChallengeProgress(instance, currentValue)
		}
	}

	/**
	 * Compute ALL tiers crossed since [previousTier]. Used by the engine to emit one
	 * XP grant per tier when the user jumps multiple tiers (e.g. on data import).
	 *
	 * Returns tiers ordered lowest → highest. Empty if no new tiers crossed.
	 */
	fun crossedTiers(
		target: RuleTarget.Tiered,
		currentValue: Long,
		previousTier: AchievementTier?,
	): List<AchievementTier> {
		return target.tiers.entries
			.sortedBy { it.key.ordinal }
			.filter { (tier, threshold) ->
				currentValue >= threshold &&
					(previousTier == null || tier.ordinal > previousTier.ordinal)
			}
			.map { it.key }
	}

	private fun highestUnlockedTier(
		tiers: Map<AchievementTier, Long>,
		currentValue: Long,
	): AchievementTier? {
		var highest: AchievementTier? = null
		for ((tier, threshold) in tiers.entries.sortedBy { it.key.ordinal }) {
			if (currentValue >= threshold) {
				highest = tier
			} else {
				break
			}
		}
		return highest
	}
}
