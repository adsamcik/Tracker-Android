package com.adsamcik.tracker.stats.api.achievement

import com.adsamcik.tracker.stats.api.AchievementCategory
import com.adsamcik.tracker.stats.api.AchievementDefinition
import com.adsamcik.tracker.stats.api.AchievementTier

/**
 * Static catalog of all achievement definitions.
 *
 * Contains ~18 achievements across 6 categories with sensible tier thresholds.
 * Definitions are immutable and loaded eagerly. Runtime progress is evaluated
 * separately by [AchievementEvaluator].
 *
 * Thread-safety: Immutable after initialization. Safe to read from any thread.
 */
object AchievementCatalog {

	/** All achievement definitions in the catalog. */
	val definitions: List<AchievementDefinition> = buildList {
		// ── EXPLORATION ─────────────────────────────────────────────
		add(
			AchievementDefinition(
				id = "explorer_cells",
				category = AchievementCategory.EXPLORATION,
				titleRes = "achievement_explorer_cells_title",
				descriptionRes = "achievement_explorer_cells_desc",
				metric = "cells_discovered",
				tiers = mapOf(
					AchievementTier.BRONZE to 10L,
					AchievementTier.SILVER to 50L,
					AchievementTier.GOLD to 200L,
					AchievementTier.DIAMOND to 1000L,
				),
			)
		)
		add(
			AchievementDefinition(
				id = "explorer_areas",
				category = AchievementCategory.EXPLORATION,
				titleRes = "achievement_explorer_areas_title",
				descriptionRes = "achievement_explorer_areas_desc",
				metric = "unique_areas",
				tiers = mapOf(
					AchievementTier.BRONZE to 3L,
					AchievementTier.SILVER to 10L,
					AchievementTier.GOLD to 30L,
					AchievementTier.DIAMOND to 100L,
				),
			)
		)
		add(
			AchievementDefinition(
				id = "seasonal_explorer",
				category = AchievementCategory.EXPLORATION,
				titleRes = "achievement_seasonal_explorer_title",
				descriptionRes = "achievement_seasonal_explorer_desc",
				metric = "seasons_explored",
				tiers = mapOf(
					AchievementTier.BRONZE to 1L,
					AchievementTier.SILVER to 2L,
					AchievementTier.GOLD to 3L,
					AchievementTier.DIAMOND to 4L,
				),
			)
		)

		// ── DISTANCE ────────────────────────────────────────────────
		add(
			AchievementDefinition(
				id = "distance_total",
				category = AchievementCategory.DISTANCE,
				titleRes = "achievement_distance_total_title",
				descriptionRes = "achievement_distance_total_desc",
				metric = "total_distance_km",
				tiers = mapOf(
					AchievementTier.BRONZE to 10L,
					AchievementTier.SILVER to 100L,
					AchievementTier.GOLD to 1000L,
					AchievementTier.DIAMOND to 10000L,
				),
			)
		)
		add(
			AchievementDefinition(
				id = "distance_single_trip",
				category = AchievementCategory.DISTANCE,
				titleRes = "achievement_distance_single_trip_title",
				descriptionRes = "achievement_distance_single_trip_desc",
				metric = "longest_trip_km",
				tiers = mapOf(
					AchievementTier.BRONZE to 5L,
					AchievementTier.SILVER to 20L,
					AchievementTier.GOLD to 50L,
					AchievementTier.DIAMOND to 200L,
				),
			)
		)

		// ── STEPS ───────────────────────────────────────────────────
		add(
			AchievementDefinition(
				id = "steps_total",
				category = AchievementCategory.STEPS,
				titleRes = "achievement_steps_total_title",
				descriptionRes = "achievement_steps_total_desc",
				metric = "total_steps",
				tiers = mapOf(
					AchievementTier.BRONZE to 10_000L,
					AchievementTier.SILVER to 100_000L,
					AchievementTier.GOLD to 1_000_000L,
					AchievementTier.DIAMOND to 10_000_000L,
				),
			)
		)
		add(
			AchievementDefinition(
				id = "steps_daily_best",
				category = AchievementCategory.STEPS,
				titleRes = "achievement_steps_daily_best_title",
				descriptionRes = "achievement_steps_daily_best_desc",
				metric = "best_daily_steps",
				tiers = mapOf(
					AchievementTier.BRONZE to 5_000L,
					AchievementTier.SILVER to 10_000L,
					AchievementTier.GOLD to 20_000L,
					AchievementTier.DIAMOND to 50_000L,
				),
			)
		)

		// ── STREAKS ─────────────────────────────────────────────────
		add(
			AchievementDefinition(
				id = "streak_daily",
				category = AchievementCategory.STREAKS,
				titleRes = "achievement_streak_daily_title",
				descriptionRes = "achievement_streak_daily_desc",
				metric = "daily_streak",
				tiers = mapOf(
					AchievementTier.BRONZE to 3L,
					AchievementTier.SILVER to 7L,
					AchievementTier.GOLD to 30L,
					AchievementTier.DIAMOND to 100L,
				),
			)
		)
		add(
			AchievementDefinition(
				id = "streak_weekly",
				category = AchievementCategory.STREAKS,
				titleRes = "achievement_streak_weekly_title",
				descriptionRes = "achievement_streak_weekly_desc",
				metric = "weekly_streak",
				tiers = mapOf(
					AchievementTier.BRONZE to 2L,
					AchievementTier.SILVER to 4L,
					AchievementTier.GOLD to 12L,
					AchievementTier.DIAMOND to 52L,
				),
			)
		)

		// ── MODES ───────────────────────────────────────────────────
		add(
			AchievementDefinition(
				id = "mode_variety",
				category = AchievementCategory.MODES,
				titleRes = "achievement_mode_variety_title",
				descriptionRes = "achievement_mode_variety_desc",
				metric = "transport_mode_count",
				tiers = mapOf(
					AchievementTier.BRONZE to 2L,
					AchievementTier.SILVER to 3L,
					AchievementTier.GOLD to 4L,
					AchievementTier.DIAMOND to 5L,
				),
			)
		)
		add(
			AchievementDefinition(
				id = "mode_walking_trips",
				category = AchievementCategory.MODES,
				titleRes = "achievement_mode_walking_trips_title",
				descriptionRes = "achievement_mode_walking_trips_desc",
				metric = "walking_trips",
				tiers = mapOf(
					AchievementTier.BRONZE to 5L,
					AchievementTier.SILVER to 20L,
					AchievementTier.GOLD to 100L,
					AchievementTier.DIAMOND to 500L,
				),
			)
		)
		add(
			AchievementDefinition(
				id = "mode_cycling_trips",
				category = AchievementCategory.MODES,
				titleRes = "achievement_mode_cycling_trips_title",
				descriptionRes = "achievement_mode_cycling_trips_desc",
				metric = "cycling_trips",
				tiers = mapOf(
					AchievementTier.BRONZE to 5L,
					AchievementTier.SILVER to 20L,
					AchievementTier.GOLD to 100L,
					AchievementTier.DIAMOND to 500L,
				),
			)
		)

		// ── MILESTONES ──────────────────────────────────────────────
		add(
			AchievementDefinition(
				id = "first_cell",
				category = AchievementCategory.MILESTONES,
				titleRes = "achievement_first_cell_title",
				descriptionRes = "achievement_first_cell_desc",
				metric = "cells_discovered",
				tiers = mapOf(
					AchievementTier.BRONZE to 1L,
				),
			)
		)
		add(
			AchievementDefinition(
				id = "first_trip",
				category = AchievementCategory.MILESTONES,
				titleRes = "achievement_first_trip_title",
				descriptionRes = "achievement_first_trip_desc",
				metric = "total_trips",
				tiers = mapOf(
					AchievementTier.BRONZE to 1L,
				),
			)
		)
		add(
			AchievementDefinition(
				id = "first_streak",
				category = AchievementCategory.MILESTONES,
				titleRes = "achievement_first_streak_title",
				descriptionRes = "achievement_first_streak_desc",
				metric = "daily_streak",
				tiers = mapOf(
					AchievementTier.BRONZE to 2L,
				),
			)
		)
		add(
			AchievementDefinition(
				id = "century_cells",
				category = AchievementCategory.MILESTONES,
				titleRes = "achievement_century_cells_title",
				descriptionRes = "achievement_century_cells_desc",
				metric = "cells_discovered",
				tiers = mapOf(
					AchievementTier.BRONZE to 100L,
				),
			)
		)
		add(
			AchievementDefinition(
				id = "first_export",
				category = AchievementCategory.MILESTONES,
				titleRes = "achievement_first_export_title",
				descriptionRes = "achievement_first_export_desc",
				metric = "total_exports",
				tiers = mapOf(
					AchievementTier.BRONZE to 1L,
				),
			)
		)
		add(
			AchievementDefinition(
				id = "distance_first_km",
				category = AchievementCategory.MILESTONES,
				titleRes = "achievement_distance_first_km_title",
				descriptionRes = "achievement_distance_first_km_desc",
				metric = "total_distance_km",
				tiers = mapOf(
					AchievementTier.BRONZE to 1L,
				),
			)
		)
	}

	private val byIdMap: Map<String, AchievementDefinition> =
		definitions.associateBy { it.id }

	private val byCategoryMap: Map<AchievementCategory, List<AchievementDefinition>> =
		definitions.groupBy { it.category }

	private val byMetricMap: Map<String, List<AchievementDefinition>> =
		definitions.groupBy { it.metric }

	/**
	 * Look up an achievement definition by its unique [id].
	 *
	 * @return The definition, or null if no achievement with this ID exists.
	 */
	fun byId(id: String): AchievementDefinition? = byIdMap[id]

	/**
	 * Return all achievements in the given [category].
	 *
	 * @return List of definitions, or empty list if no achievements in this category.
	 */
	fun byCategory(category: AchievementCategory): List<AchievementDefinition> =
		byCategoryMap[category].orEmpty()

	/**
	 * Return all achievements that track the given [metric] key.
	 *
	 * Multiple achievements may share a metric (e.g. "cells_discovered" is used
	 * by both "explorer_cells" and "first_cell").
	 *
	 * @return List of definitions, or empty list if no achievements use this metric.
	 */
	fun byMetric(metric: String): List<AchievementDefinition> =
		byMetricMap[metric].orEmpty()
}
