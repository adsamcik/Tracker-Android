package com.adsamcik.tracker.stats.engine.achievement

import com.adsamcik.tracker.stats.api.AchievementCategory
import com.adsamcik.tracker.stats.api.AchievementTier
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class AchievementCatalogTest {

	@Nested
	inner class DefinitionIntegrity {
		@Test
		fun `all IDs are unique`() {
			val ids = AchievementCatalog.definitions.map { it.id }
			ids.distinct().size shouldBe ids.size
		}

		@Test
		fun `all definitions have at least Bronze tier`() {
			AchievementCatalog.definitions.forEach { definition ->
				definition.tiers.keys shouldContain AchievementTier.BRONZE
			}
		}

		@Test
		fun `tier thresholds are strictly increasing within each definition`() {
			AchievementCatalog.definitions.forEach { definition ->
				val sortedEntries = definition.tiers.entries
					.sortedBy { it.key.ordinal }

				for (i in 1 until sortedEntries.size) {
					val prev = sortedEntries[i - 1]
					val curr = sortedEntries[i]
					assert(curr.value > prev.value) {
						"Definition '${definition.id}': ${curr.key} (${curr.value}) " +
							"should be > ${prev.key} (${prev.value})"
					}
				}
			}
		}

		@Test
		fun `all definitions have non-blank id`() {
			AchievementCatalog.definitions.forEach { definition ->
				assert(definition.id.isNotBlank()) {
					"Found definition with blank id"
				}
			}
		}

		@Test
		fun `all definitions have non-blank metric`() {
			AchievementCatalog.definitions.forEach { definition ->
				assert(definition.metric.isNotBlank()) {
					"Definition '${definition.id}' has blank metric"
				}
			}
		}

		@Test
		fun `all definitions have non-blank title and description resource names`() {
			AchievementCatalog.definitions.forEach { definition ->
				assert(definition.titleRes.isNotBlank()) {
					"Definition '${definition.id}' has blank titleRes"
				}
				assert(definition.descriptionRes.isNotBlank()) {
					"Definition '${definition.id}' has blank descriptionRes"
				}
			}
		}

		@Test
		fun `all tier targets are positive`() {
			AchievementCatalog.definitions.forEach { definition ->
				definition.tiers.forEach { (tier, target) ->
					assert(target > 0L) {
						"Definition '${definition.id}': ${tier} has non-positive target $target"
					}
				}
			}
		}

		@Test
		fun `catalog has at least 15 definitions`() {
			AchievementCatalog.definitions shouldHaveAtLeastSize 15
		}
	}

	@Nested
	inner class ByIdLookup {
		@Test
		fun `returns correct definition for known id`() {
			val definition = AchievementCatalog.byId("explorer_cells")

			definition.shouldNotBeNull()
			definition.id shouldBe "explorer_cells"
			definition.category shouldBe AchievementCategory.EXPLORATION
			definition.metric shouldBe "cells_discovered"
		}

		@Test
		fun `returns null for unknown id`() {
			AchievementCatalog.byId("nonexistent_achievement").shouldBeNull()
		}

		@Test
		fun `returns correct definition for milestone`() {
			val definition = AchievementCatalog.byId("first_cell")

			definition.shouldNotBeNull()
			definition.category shouldBe AchievementCategory.MILESTONES
			definition.tiers.size shouldBe 1
			definition.tiers[AchievementTier.BRONZE] shouldBe 1L
		}
	}

	@Nested
	inner class ByCategoryLookup {
		@Test
		fun `returns exploration achievements`() {
			val exploration = AchievementCatalog.byCategory(AchievementCategory.EXPLORATION)

			exploration.shouldNotBeEmpty()
			exploration.forEach { it.category shouldBe AchievementCategory.EXPLORATION }
		}

		@Test
		fun `returns milestones`() {
			val milestones = AchievementCatalog.byCategory(AchievementCategory.MILESTONES)

			milestones.shouldNotBeEmpty()
			milestones.forEach { it.category shouldBe AchievementCategory.MILESTONES }
		}

		@Test
		fun `every category has at least one achievement`() {
			AchievementCategory.entries.forEach { category ->
				val achievements = AchievementCatalog.byCategory(category)
				assert(achievements.isNotEmpty()) {
					"Category $category has no achievements"
				}
			}
		}

		@Test
		fun `all definitions are reachable by category`() {
			val fromCategory = AchievementCategory.entries
				.flatMap { AchievementCatalog.byCategory(it) }
				.toSet()

			fromCategory.size shouldBe AchievementCatalog.definitions.size
		}
	}

	@Nested
	inner class ByMetricLookup {
		@Test
		fun `returns achievements for cells_discovered metric`() {
			val achievements = AchievementCatalog.byMetric("cells_discovered")

			achievements.shouldNotBeEmpty()
			achievements.forEach { it.metric shouldBe "cells_discovered" }
		}

		@Test
		fun `cells_discovered has multiple achievements`() {
			val achievements = AchievementCatalog.byMetric("cells_discovered")

			// explorer_cells, first_cell, century_cells all use this metric
			achievements shouldHaveAtLeastSize 3
		}

		@Test
		fun `returns empty list for unknown metric`() {
			AchievementCatalog.byMetric("nonexistent_metric").shouldBeEmpty()
		}

		@Test
		fun `daily_streak metric shared between streaks and milestones`() {
			val achievements = AchievementCatalog.byMetric("daily_streak")

			achievements shouldHaveAtLeastSize 2
			val categories = achievements.map { it.category }.toSet()
			categories shouldContain AchievementCategory.STREAKS
			categories shouldContain AchievementCategory.MILESTONES
		}
	}
}
