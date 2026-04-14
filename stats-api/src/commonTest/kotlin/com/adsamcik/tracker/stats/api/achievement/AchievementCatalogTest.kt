package com.adsamcik.tracker.stats.api.achievement

import com.adsamcik.tracker.stats.api.AchievementCategory
import com.adsamcik.tracker.stats.api.AchievementTier
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class AchievementCatalogTest {

	@Nested
	inner class CatalogCompleteness {

		@Test
		fun `catalog contains at least 18 definitions`() {
			AchievementCatalog.definitions shouldHaveAtLeastSize 18
		}

		@Test
		fun `all definition ids are unique`() {
			val ids = AchievementCatalog.definitions.map { it.id }
			ids.toSet().size shouldBe ids.size
		}

		@Test
		fun `every definition has at least a BRONZE tier`() {
			AchievementCatalog.definitions.forEach { def ->
				def.tiers.containsKey(AchievementTier.BRONZE) shouldBe true
			}
		}

		@Test
		fun `every definition has non-empty id`() {
			AchievementCatalog.definitions.forEach { def ->
				assert(def.id.isNotBlank()) { "Definition id must not be blank" }
			}
		}

		@Test
		fun `every definition has non-empty metric`() {
			AchievementCatalog.definitions.forEach { def ->
				assert(def.metric.isNotBlank()) { "Definition metric must not be blank for ${def.id}" }
			}
		}

		@Test
		fun `every definition has non-empty title and description resources`() {
			AchievementCatalog.definitions.forEach { def ->
				assert(def.titleRes.isNotBlank()) { "titleRes blank for ${def.id}" }
				assert(def.descriptionRes.isNotBlank()) { "descriptionRes blank for ${def.id}" }
			}
		}

		@Test
		fun `tier thresholds are strictly increasing within each definition`() {
			AchievementCatalog.definitions.forEach { def ->
				val sortedEntries = def.tiers.entries.sortedBy { it.key.ordinal }
				for (i in 0 until sortedEntries.size - 1) {
					val current = sortedEntries[i]
					val next = sortedEntries[i + 1]
					assert(current.value < next.value) {
						"${def.id}: ${current.key}(${current.value}) should be < ${next.key}(${next.value})"
					}
				}
			}
		}

		@Test
		fun `all tier threshold values are positive`() {
			AchievementCatalog.definitions.forEach { def ->
				def.tiers.values.forEach { value ->
					assert(value > 0) { "${def.id} has non-positive threshold: $value" }
				}
			}
		}
	}

	@Nested
	inner class CategoryCoverage {

		@Test
		fun `every AchievementCategory has at least one definition`() {
			AchievementCategory.entries.forEach { category ->
				AchievementCatalog.byCategory(category).shouldNotBeEmpty()
			}
		}

		@Test
		fun `EXPLORATION category has expected achievements`() {
			val ids = AchievementCatalog.byCategory(AchievementCategory.EXPLORATION).map { it.id }
			ids shouldContain "explorer_cells"
			ids shouldContain "explorer_areas"
			ids shouldContain "seasonal_explorer"
		}

		@Test
		fun `DISTANCE category has expected achievements`() {
			val ids = AchievementCatalog.byCategory(AchievementCategory.DISTANCE).map { it.id }
			ids shouldContain "distance_total"
			ids shouldContain "distance_single_trip"
		}

		@Test
		fun `STEPS category has expected achievements`() {
			val ids = AchievementCatalog.byCategory(AchievementCategory.STEPS).map { it.id }
			ids shouldContain "steps_total"
			ids shouldContain "steps_daily_best"
		}

		@Test
		fun `STREAKS category has expected achievements`() {
			val ids = AchievementCatalog.byCategory(AchievementCategory.STREAKS).map { it.id }
			ids shouldContain "streak_daily"
			ids shouldContain "streak_weekly"
		}

		@Test
		fun `MODES category has expected achievements`() {
			val ids = AchievementCatalog.byCategory(AchievementCategory.MODES).map { it.id }
			ids shouldContain "mode_variety"
			ids shouldContain "mode_walking_trips"
			ids shouldContain "mode_cycling_trips"
		}

		@Test
		fun `MILESTONES category has expected achievements`() {
			val ids = AchievementCatalog.byCategory(AchievementCategory.MILESTONES).map { it.id }
			ids shouldContain "first_cell"
			ids shouldContain "first_trip"
			ids shouldContain "first_streak"
			ids shouldContain "century_cells"
			ids shouldContain "first_export"
			ids shouldContain "distance_first_km"
		}

		@Test
		fun `MILESTONES achievements have only BRONZE tier`() {
			AchievementCatalog.byCategory(AchievementCategory.MILESTONES).forEach { def ->
				def.tiers.keys shouldHaveSize 1
				def.tiers.containsKey(AchievementTier.BRONZE) shouldBe true
			}
		}
	}

	@Nested
	inner class LookupById {

		@Test
		fun `byId returns correct definition for known id`() {
			val def = AchievementCatalog.byId("explorer_cells")
			def.shouldNotBeNull()
			def.id shouldBe "explorer_cells"
			def.category shouldBe AchievementCategory.EXPLORATION
			def.metric shouldBe "cells_discovered"
		}

		@Test
		fun `byId returns null for unknown id`() {
			AchievementCatalog.byId("nonexistent_achievement").shouldBeNull()
		}

		@Test
		fun `byId returns null for empty string`() {
			AchievementCatalog.byId("").shouldBeNull()
		}

		@Test
		fun `every definition is retrievable by its id`() {
			AchievementCatalog.definitions.forEach { def ->
				val found = AchievementCatalog.byId(def.id)
				found.shouldNotBeNull()
				found shouldBe def
			}
		}
	}

	@Nested
	inner class LookupByMetric {

		@Test
		fun `byMetric returns definitions sharing a metric`() {
			val cellDefs = AchievementCatalog.byMetric("cells_discovered")
			cellDefs shouldHaveAtLeastSize 2
			cellDefs.map { it.id } shouldContain "explorer_cells"
			cellDefs.map { it.id } shouldContain "first_cell"
		}

		@Test
		fun `byMetric returns empty list for unknown metric`() {
			AchievementCatalog.byMetric("nonexistent_metric").shouldBeEmpty()
		}

		@Test
		fun `daily_streak metric is shared between streak_daily and first_streak`() {
			val streakDefs = AchievementCatalog.byMetric("daily_streak")
			streakDefs.map { it.id } shouldContain "streak_daily"
			streakDefs.map { it.id } shouldContain "first_streak"
		}

		@Test
		fun `total_distance_km metric is shared between distance_total and distance_first_km`() {
			val distDefs = AchievementCatalog.byMetric("total_distance_km")
			distDefs.map { it.id } shouldContain "distance_total"
			distDefs.map { it.id } shouldContain "distance_first_km"
		}
	}

	@Nested
	inner class SpecificAchievementThresholds {

		@Test
		fun `explorer_cells has expected tier thresholds`() {
			val def = AchievementCatalog.byId("explorer_cells")!!
			def.tiers[AchievementTier.BRONZE] shouldBe 10L
			def.tiers[AchievementTier.SILVER] shouldBe 50L
			def.tiers[AchievementTier.GOLD] shouldBe 200L
			def.tiers[AchievementTier.DIAMOND] shouldBe 1000L
		}

		@Test
		fun `first_trip is a single-tier milestone`() {
			val def = AchievementCatalog.byId("first_trip")!!
			def.tiers.keys shouldHaveSize 1
			def.tiers[AchievementTier.BRONZE] shouldBe 1L
		}

		@Test
		fun `steps_total has escalating thresholds`() {
			val def = AchievementCatalog.byId("steps_total")!!
			def.tiers[AchievementTier.BRONZE] shouldBe 10_000L
			def.tiers[AchievementTier.DIAMOND] shouldBe 10_000_000L
		}
	}
}
