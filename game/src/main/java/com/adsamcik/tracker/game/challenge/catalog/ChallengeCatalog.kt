package com.adsamcik.tracker.game.challenge.catalog

import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.challenge.ChallengeDifficulty
import com.adsamcik.tracker.game.challenge.data.ChallengeType
import com.adsamcik.tracker.stats.api.metric.MetricKeys

/**
 * Static catalog of all challenge definitions. Lookup by [ChallengeType] is total —
 * every enum value must be present in [definitions]. Verified by
 * `ChallengeCatalogTest`.
 *
 * Modeled on `AchievementCatalog` (in `:stats-api`): immutable, eagerly loaded,
 * safe to read from any thread.
 */
object ChallengeCatalog {

	/**
	 * Speed challenge difficulty curve. Tighter than [ChallengeDefinition.DEFAULT_DIFFICULTY_TARGET_MULT]
	 * because realistic walking/running cadence has hard upper bounds — VERY_HARD = 1.45× target
	 * is achievable; 1.8× starts to require running a marathon.
	 */
	private val SPEED_DIFFICULTY_TARGET_MULT: (ChallengeDifficulty) -> Double = { d ->
		when (d) {
			ChallengeDifficulty.VERY_EASY -> 0.7
			ChallengeDifficulty.EASY -> 0.85
			ChallengeDifficulty.MEDIUM -> 1.0
			ChallengeDifficulty.HARD -> 1.2
			ChallengeDifficulty.VERY_HARD -> 1.45
		}
	}

	/**
	 * Explorer challenge difficulty curve. Floor of 0.7 (3.5 cells at default 5) rather than the
	 * default 0.6 — exploring 3 fresh L14 cells in 7 days is borderline trivial for any urban user.
	 */
	private val EXPLORER_DIFFICULTY_TARGET_MULT: (ChallengeDifficulty) -> Double = { d ->
		when (d) {
			ChallengeDifficulty.VERY_EASY -> 0.7
			ChallengeDifficulty.EASY -> 0.85
			ChallengeDifficulty.MEDIUM -> 1.0
			ChallengeDifficulty.HARD -> 1.4
			ChallengeDifficulty.VERY_HARD -> 1.8
		}
	}

	/**
	 * Consistency challenge difficulty curve. Target is "distinct days tracked"; cap at 1.4 so a
	 * VERY_HARD 10-day window asks for at most ~10 days (which already requires perfect
	 * consistency); 1.8× would be impossible.
	 */
	private val CONSISTENCY_DIFFICULTY_TARGET_MULT: (ChallengeDifficulty) -> Double = { d ->
		when (d) {
			ChallengeDifficulty.VERY_EASY -> 0.6
			ChallengeDifficulty.EASY -> 0.8
			ChallengeDifficulty.MEDIUM -> 1.0
			ChallengeDifficulty.HARD -> 1.2
			ChallengeDifficulty.VERY_HARD -> 1.4
		}
	}

	/** All challenge definitions in the catalog. */
	val definitions: List<ChallengeDefinition> = listOf(
		ChallengeDefinition(
			type = ChallengeType.Step,
			titleRes = R.string.challenge_step_title,
			descriptionTemplateRes = R.string.challenge_step_description,
			metric = MetricKeys.STEPS,
			unit = ChallengeUnit.STEPS,
			defaultRequiredValue = 50_000.0,
			defaultDurationMs = 7L * 24 * 60 * 60 * 1000L,
		),
		ChallengeDefinition(
			type = ChallengeType.WalkDistance,
			titleRes = R.string.challenge_walk_distance_title,
			descriptionTemplateRes = R.string.challenge_walk_in_the_park_description,
			metric = MetricKeys.DISTANCE_ON_FOOT_M,
			unit = ChallengeUnit.DISTANCE_M,
			defaultRequiredValue = 30_000.0,
			defaultDurationMs = 7L * 24 * 60 * 60 * 1000L,
		),
		ChallengeDefinition(
			type = ChallengeType.ActiveTime,
			titleRes = R.string.challenge_active_time_title,
			descriptionTemplateRes = R.string.challenge_active_time_description,
			metric = MetricKeys.ACTIVE_MINUTES,
			unit = ChallengeUnit.MINUTES,
			defaultRequiredValue = 300.0,
			defaultDurationMs = 7L * 24 * 60 * 60 * 1000L,
		),
		ChallengeDefinition(
			type = ChallengeType.Speed,
			titleRes = R.string.challenge_speed_title,
			descriptionTemplateRes = R.string.challenge_speed_description,
			metric = MetricKeys.DISTANCE_ON_FOOT_M,
			unit = ChallengeUnit.DISTANCE_M,
			defaultRequiredValue = 2_000.0,
			// 1 day window — tight enough to feel like a sprint challenge.
			defaultDurationMs = 24L * 60 * 60 * 1000L,
			minDurationMultiplier = 0.5,
			maxDurationMultiplier = 2.0,
			// Tighter curve than the default: people don't realistically go 1.8× faster.
			difficultyTargetMultiplier = SPEED_DIFFICULTY_TARGET_MULT,
		),
		ChallengeDefinition(
			type = ChallengeType.Explorer,
			titleRes = R.string.challenge_explorer_title,
			descriptionTemplateRes = R.string.challenge_explorer_description,
			metric = MetricKeys.CELLS_DISCOVERED,
			unit = ChallengeUnit.CELLS,
			// Explorer counts distinct S2 L14 cells (~565 m wide) the user has just
			// discovered within the challenge window. 5 fresh cells over a week is roughly
			// equivalent to the legacy "100 distinct 20m grid points" challenge in
			// city-walk semantics — picked to keep the difficulty curve familiar.
			defaultRequiredValue = 5.0,
			defaultDurationMs = 7L * 24 * 60 * 60 * 1000L,
			// Tighter bottom (`0.6` would mean 3 cells over 7 days, too easy).
			difficultyTargetMultiplier = EXPLORER_DIFFICULTY_TARGET_MULT,
		),
		ChallengeDefinition(
			type = ChallengeType.Consistency,
			titleRes = R.string.challenge_consistency_title,
			descriptionTemplateRes = R.string.challenge_consistency_description,
			// Counts distinct calendar days with >= MIN_DAILY_TRIPS trips in the challenge
			// window, backed by the pre-aggregated daily_summary table.
			// On rollout, existing rows with legacy extraJson = {"trackedDays":[...]} will be
			// re-evaluated against the new metric on next session — the user gets the "correct"
			// daily_summary count instead of the JSON-tracked count. extraJson becomes dead data
			// (the v26→v27 schema fold in Phase 3 will drop the column).
			metric = MetricKeys.ACTIVE_DAYS,
			unit = ChallengeUnit.DAYS,
			defaultRequiredValue = 7.0,
			defaultDurationMs = 10L * 24 * 60 * 60 * 1000L,
			minDurationMultiplier = 0.7,
			maxDurationMultiplier = 2.0,
			// Cap top end at 1.4: going from 7 distinct days to 12 in a 10-day window is impossible.
			difficultyTargetMultiplier = CONSISTENCY_DIFFICULTY_TARGET_MULT,
		),
	)

	private val byTypeMap: Map<ChallengeType, ChallengeDefinition> =
		definitions.associateBy { it.type }

	/**
	 * Lookup by type. Throws [IllegalStateException] for unknown types — the
	 * catalog is total over [ChallengeType].
	 */
	fun byType(type: ChallengeType): ChallengeDefinition =
		byTypeMap[type]
			?: error("No ChallengeDefinition for $type — every ChallengeType must be in the catalog")
}
