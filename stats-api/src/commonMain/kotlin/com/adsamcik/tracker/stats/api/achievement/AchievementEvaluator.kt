package com.adsamcik.tracker.stats.api.achievement

import com.adsamcik.tracker.stats.api.AchievementDefinition
import com.adsamcik.tracker.stats.api.AchievementSnapshot
import com.adsamcik.tracker.stats.api.AchievementTier
import com.adsamcik.tracker.stats.api.TierUnlock

/**
 * Evaluates metric values against achievement definitions to produce snapshots.
 *
 * Stateless evaluator that compares current metric values against tier thresholds
 * to determine achievement progress and detect tier unlocks.
 *
 * Thread-safety: Stateless; safe to call from any thread.
 *
 * @param catalog List of achievement definitions to evaluate against.
 *   Defaults to [AchievementCatalog.definitions].
 */
class AchievementEvaluator(
	private val catalog: List<AchievementDefinition> = AchievementCatalog.definitions,
) {
	private val catalogByMetric: Map<String, List<AchievementDefinition>> =
		catalog.groupBy { it.metric }

	/**
	 * Evaluate a single metric against all relevant achievements.
	 *
	 * Filters the catalog for achievements that track [metric], computes the
	 * current snapshot for each, and returns only those that differ from
	 * [previousProgress] (new tier unlocked or tier changed).
	 *
	 * @param metric The metric key to evaluate (e.g. "cells_discovered").
	 * @param currentValue Current cumulative value for this metric.
	 * @param previousProgress Map of achievementId to (previousValue, previousTier).
	 *   Achievements not in this map are treated as having no prior progress.
	 * @return List of snapshots where the tier changed compared to previous state.
	 *   Empty if nothing changed or if no achievements track this metric.
	 */
	fun evaluate(
		metric: String,
		currentValue: Long,
		previousProgress: Map<String, Pair<Long, AchievementTier?>>,
	): List<AchievementSnapshot> {
		val relevantDefinitions = relevantDefinitions(metric)
		if (relevantDefinitions.isEmpty()) return emptyList()

		return relevantDefinitions.mapNotNull { definition ->
			val snap = snapshot(definition, currentValue)
			val previous = previousProgress[definition.id]
			val previousTier = previous?.second

			// Only return if tier changed (new unlock or regression)
			if (snap.currentTier != previousTier) {
				snap
			} else {
				null
			}
		}
	}

	fun snapshots(metric: String, currentValue: Long): List<AchievementSnapshot> {
		return relevantDefinitions(metric).map { definition ->
			snapshot(definition, currentValue)
		}
	}

	/**
	 * Evaluate a single achievement and return ALL newly crossed tiers.
	 *
	 * Fixes the tier-skip bug: if a user jumps from no tier to GOLD (e.g. on import),
	 * this returns [BRONZE, SILVER, GOLD] so each tier's XP bonus is awarded.
	 *
	 * @param definition The achievement definition to evaluate.
	 * @param currentValue Current cumulative metric value.
	 * @param previousTier The highest tier previously persisted (null if none).
	 *   Only tiers strictly greater than [previousTier] are returned.
	 * @return List of [TierUnlock] for each newly crossed tier, ordered lowest→highest.
	 *   Empty if no new tiers were crossed.
	 */
	fun evaluateForUnlocks(
		definition: AchievementDefinition,
		currentValue: Long,
		previousTier: AchievementTier?,
	): List<TierUnlock> {
		return definition.tiers.entries
			.sortedBy { it.key.ordinal }
			.filter { (tier, threshold) ->
				currentValue >= threshold &&
					(previousTier == null || tier.ordinal > previousTier.ordinal)
			}
			.map { (tier, _) -> TierUnlock(definition.id, tier) }
	}

	/**
	 * Compute a snapshot for a single achievement definition given the current value.
	 *
	 * Determines the highest unlocked tier (where [currentValue] >= threshold),
	 * the next tier to unlock, and fractional progress toward that next tier.
	 *
	 * @param definition The achievement definition to evaluate.
	 * @param currentValue Current cumulative value for the achievement's metric.
	 * @return Snapshot with current tier, next tier, and progress.
	 */
	fun snapshot(definition: AchievementDefinition, currentValue: Long): AchievementSnapshot {
		val sortedTiers = definition.tiers.entries
			.sortedBy { it.key.ordinal }

		// Find the highest tier where currentValue >= target
		var currentTier: AchievementTier? = null
		var nextTier: AchievementTier? = null
		var nextTierTarget: Long? = null

		for (entry in sortedTiers) {
			if (currentValue >= entry.value) {
				currentTier = entry.key
			} else {
				nextTier = entry.key
				nextTierTarget = entry.value
				break
			}
		}

		// Calculate progress toward next tier
		val progress = when {
			// Maxed out - all tiers unlocked
			nextTier == null && currentTier != null -> 1.0f
			// No tier unlocked yet - progress from 0 to first tier
			nextTier != null && currentTier == null -> {
				val target = nextTierTarget!!
				if (target <= 0L) 1.0f
				else (currentValue.toFloat() / target).coerceIn(0.0f, 1.0f)
			}
			// Between tiers - progress from current tier target to next tier target
			nextTier != null && currentTier != null -> {
				val currentTierTarget = definition.tiers[currentTier]!!
				val target = nextTierTarget!!
				val range = target - currentTierTarget
				if (range <= 0L) 1.0f
				else ((currentValue - currentTierTarget).toFloat() / range).coerceIn(0.0f, 1.0f)
			}
			// No tiers at all (shouldn't happen with valid definitions) or zero value
			else -> 0.0f
		}

		return AchievementSnapshot(
			definition = definition,
			currentValue = currentValue,
			currentTier = currentTier,
			nextTier = nextTier,
			nextTierTarget = nextTierTarget,
			progress = progress,
		)
	}

	private fun relevantDefinitions(metric: String): List<AchievementDefinition> {
		return catalogByMetric[metric].orEmpty()
	}
}
