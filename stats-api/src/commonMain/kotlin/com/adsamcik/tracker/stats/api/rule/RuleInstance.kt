package com.adsamcik.tracker.stats.api.rule

import com.adsamcik.tracker.stats.api.metric.TimeWindow

/**
 * A runtime-evaluable rule. Pairs a [Rule] (static-or-catalog template) with the time window
 * over which to evaluate it.
 *
 * For achievements, [window] is always [TimeWindow.Cumulative]. For challenges, it's
 * [TimeWindow.Interval] bound to the challenge entity's `startTime` / `endTime`.
 *
 * @property rule The rule template.
 * @property window Time window for metric collection.
 * @property previousValue Last observed value (for change detection during live evaluation).
 *   Null if the rule has never been evaluated before in this session.
 * @property previousTier For achievement rules: last unlocked tier (persisted in
 *   `achievement_progress`). Null means "no tier yet".
 * @property contextId Optional identifier the engine uses to correlate an evaluation back
 *   to its persistent row (e.g. the `ChallengeEntity.id` for a challenge instance).
 */
data class RuleInstance(
	val rule: Rule,
	val window: TimeWindow,
	val previousValue: Long? = null,
	val previousTier: com.adsamcik.tracker.stats.api.AchievementTier? = null,
	val contextId: Long? = null,
)
