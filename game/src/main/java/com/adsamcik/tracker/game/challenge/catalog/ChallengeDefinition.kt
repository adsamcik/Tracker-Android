package com.adsamcik.tracker.game.challenge.catalog

import androidx.annotation.StringRes
import com.adsamcik.tracker.game.challenge.ChallengeDifficulty
import com.adsamcik.tracker.game.challenge.data.ChallengeType
import com.adsamcik.tracker.stats.api.metric.TimeWindow

/**
 * Declarative description of a challenge type. Single source of truth for the
 * challenge engine's per-type configuration — there are no imperative per-type
 * processor classes anymore; the engine reads a [ChallengeDefinition] and asks
 * the shared `WindowedMetricsProvider` for the metric value over the active
 * challenge's window, then compares to `requiredValue`.
 *
 * A [ChallengeDefinition] is a pure value: metric × window × target × payoff.
 *
 * @property type Stable identifier matching the [ChallengeType] enum so existing
 *   DB rows still map correctly. Catalog is total over [ChallengeType].
 * @property titleRes String resource for the UI title.
 * @property descriptionTemplateRes String resource for the description, with one
 *   `%s` slot for the formatted target value.
 * @property metric Shared metric key from `:stats-api` `MetricKeys`. The engine
 *   collects the metric value over [windowFor]`(entity)` and compares it to
 *   `requiredValue`.
 * @property unit How to format the target for UI display.
 * @property defaultRequiredValue Base target before [difficultyTargetMultiplier]
 *   and random duration/target multipliers are applied at activation time.
 * @property defaultDurationMs Base challenge duration. Actual durations vary by a
 *   random factor inside `[minDurationMultiplier, maxDurationMultiplier]` at
 *   activation.
 * @property minDurationMultiplier Inclusive lower bound for the random duration
 *   multiplier sampled at activation.
 * @property maxDurationMultiplier Inclusive upper bound for the random duration
 *   multiplier sampled at activation. Must be strictly greater than
 *   [minDurationMultiplier].
 * @property difficultyTargetMultiplier Function mapping difficulty band → target
 *   multiplier; defaults to 1.0 everywhere so MEDIUM produces the canonical
 *   `defaultRequiredValue` (× target randomness).
 */
data class ChallengeDefinition(
	val type: ChallengeType,
	@StringRes val titleRes: Int,
	@StringRes val descriptionTemplateRes: Int,
	val metric: String,
	val unit: ChallengeUnit,
	val defaultRequiredValue: Double,
	val defaultDurationMs: Long,
	val minDurationMultiplier: Double = 0.5,
	val maxDurationMultiplier: Double = 2.0,
	/**
	 * Inclusive lower bound for the random target multiplier sampled at activation.
	 * Decoupled from [minDurationMultiplier] so duration randomness and target randomness
	 * don't double-count (a common bug in the legacy processor model where both shared
	 * the same multiplier).
	 */
	val minTargetMultiplier: Double = 0.8,
	val maxTargetMultiplier: Double = 1.25,
	/**
	 * Function mapping difficulty band → target multiplier. Per-type so the curve can be
	 * tuned independently (e.g. Speed challenges can't realistically scale to 1.8× harder).
	 * MUST return 1.0 for [ChallengeDifficulty.MEDIUM] so the median row equals
	 * [defaultRequiredValue] (× target randomness).
	 */
	val difficultyTargetMultiplier: (ChallengeDifficulty) -> Double = DEFAULT_DIFFICULTY_TARGET_MULT,
) {
	/**
	 * Compute the [TimeWindow] for a single active challenge row.
	 *
	 * Uses the row's `startTime` and `endTime` as the interval bounds. Callers
	 * evaluating progress for a still-active challenge should clamp `endMs` to
	 * `min(now, entity.endTime)` before constructing the window.
	 */
	fun windowFor(startTimeMs: Long, endTimeMs: Long): TimeWindow =
		TimeWindow.Interval(startTimeMs, endTimeMs)

	companion object {
		/**
		 * Default difficulty target multiplier curve. Use for challenge types whose progress is
		 * naturally elastic (steps, distance, active time, days). Speed and Explorer use tighter
		 * curves defined inline in [ChallengeCatalog].
		 *
		 * MEDIUM always returns 1.0 so existing balance is preserved at the median band.
		 */
		val DEFAULT_DIFFICULTY_TARGET_MULT: (ChallengeDifficulty) -> Double = { d ->
			when (d) {
				ChallengeDifficulty.VERY_EASY -> 0.6
				ChallengeDifficulty.EASY -> 0.8
				ChallengeDifficulty.MEDIUM -> 1.0
				ChallengeDifficulty.HARD -> 1.35
				ChallengeDifficulty.VERY_HARD -> 1.8
			}
		}
	}
}

/**
 * UI formatting hint for a challenge's target value. The engine itself is unit
 * agnostic — this is consumed by description formatters.
 */
enum class ChallengeUnit {
	STEPS,
	DISTANCE_M,
	MINUTES,
	CELLS,
	DAYS,
}
