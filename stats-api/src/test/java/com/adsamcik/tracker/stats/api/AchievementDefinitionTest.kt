package com.adsamcik.tracker.stats.api

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class AchievementDefinitionTest {

	private fun explorerDefinition() = AchievementDefinition(
		id = "explorer_cells",
		category = AchievementCategory.EXPLORATION,
		titleRes = "achievement_explorer_cells_title",
		descriptionRes = "achievement_explorer_cells_desc",
		metric = "cells_discovered",
		tiers = mapOf(
			AchievementTier.BRONZE to 10L,
			AchievementTier.SILVER to 100L,
			AchievementTier.GOLD to 1_000L,
			AchievementTier.DIAMOND to 10_000L,
		),
	)

	private fun distanceDefinition() = AchievementDefinition(
		id = "distance_walker",
		category = AchievementCategory.DISTANCE,
		titleRes = "achievement_distance_title",
		descriptionRes = "achievement_distance_desc",
		metric = "total_distance_m",
		tiers = mapOf(
			AchievementTier.BRONZE to 5_000L,
			AchievementTier.SILVER to 50_000L,
			AchievementTier.GOLD to 500_000L,
			AchievementTier.DIAMOND to 5_000_000L,
		),
	)

	@Nested
	inner class TierProgression {

		@Test
		fun `tiers are ordered BRONZE - SILVER - GOLD - DIAMOND`() {
			AchievementTier.entries shouldBe listOf(
				AchievementTier.BRONZE,
				AchievementTier.SILVER,
				AchievementTier.GOLD,
				AchievementTier.DIAMOND,
			)
		}

		@Test
		fun `tier values are strictly increasing`() {
			val def = explorerDefinition()
			val sortedTiers = AchievementTier.entries
			for (i in 0 until sortedTiers.size - 1) {
				val current = def.tiers[sortedTiers[i]]!!
				val next = def.tiers[sortedTiers[i + 1]]!!
				assert(current < next) {
					"${sortedTiers[i]} ($current) should be less than ${sortedTiers[i + 1]} ($next)"
				}
			}
		}

		@Test
		fun `definition contains all four tiers`() {
			val def = explorerDefinition()
			def.tiers.keys shouldHaveSize 4
		}

		@Test
		fun `BRONZE has lowest threshold`() {
			val def = explorerDefinition()
			def.tiers[AchievementTier.BRONZE] shouldBe 10L
		}

		@Test
		fun `DIAMOND has highest threshold`() {
			val def = explorerDefinition()
			def.tiers[AchievementTier.DIAMOND] shouldBe 10_000L
		}
	}

	@Nested
	inner class TierProperties {

		@Test
		fun `BRONZE label is Bronze`() {
			AchievementTier.BRONZE.label shouldBe "Bronze"
		}

		@Test
		fun `SILVER label is Silver`() {
			AchievementTier.SILVER.label shouldBe "Silver"
		}

		@Test
		fun `GOLD label is Gold`() {
			AchievementTier.GOLD.label shouldBe "Gold"
		}

		@Test
		fun `DIAMOND label is Diamond`() {
			AchievementTier.DIAMOND.label shouldBe "Diamond"
		}

		@Test
		fun `point bonuses increase with tier`() {
			val tiers = AchievementTier.entries
			for (i in 0 until tiers.size - 1) {
				assert(tiers[i].pointBonus < tiers[i + 1].pointBonus) {
					"${tiers[i].name} bonus (${tiers[i].pointBonus}) should be less than ${tiers[i + 1].name} (${tiers[i + 1].pointBonus})"
				}
			}
		}

		@Test
		fun `BRONZE awards 50 points`() {
			AchievementTier.BRONZE.pointBonus shouldBe 50
		}

		@Test
		fun `DIAMOND awards 2000 points`() {
			AchievementTier.DIAMOND.pointBonus shouldBe 2000
		}
	}

	@Nested
	inner class CategoryClassification {

		@Test
		fun `all six categories exist`() {
			AchievementCategory.entries shouldHaveSize 6
		}

		@Test
		fun `categories are ordered correctly`() {
			AchievementCategory.entries shouldBe listOf(
				AchievementCategory.EXPLORATION,
				AchievementCategory.DISTANCE,
				AchievementCategory.STEPS,
				AchievementCategory.STREAKS,
				AchievementCategory.MODES,
				AchievementCategory.MILESTONES,
			)
		}

		@Test
		fun `explorer definition has EXPLORATION category`() {
			explorerDefinition().category shouldBe AchievementCategory.EXPLORATION
		}

		@Test
		fun `distance definition has DISTANCE category`() {
			distanceDefinition().category shouldBe AchievementCategory.DISTANCE
		}
	}

	@Nested
	inner class SnapshotCreation {

		@Test
		fun `snapshot with zero progress has null current tier`() {
			val snapshot = AchievementSnapshot(
				definition = explorerDefinition(),
				currentValue = 0L,
				currentTier = null,
				nextTier = AchievementTier.BRONZE,
				nextTierTarget = 10L,
				progress = 0.0f,
			)
			snapshot.currentTier.shouldBeNull()
			snapshot.nextTier shouldBe AchievementTier.BRONZE
		}

		@Test
		fun `snapshot at BRONZE has SILVER as next tier`() {
			val snapshot = AchievementSnapshot(
				definition = explorerDefinition(),
				currentValue = 15L,
				currentTier = AchievementTier.BRONZE,
				nextTier = AchievementTier.SILVER,
				nextTierTarget = 100L,
				progress = 15f / 100f,
			)
			snapshot.currentTier shouldBe AchievementTier.BRONZE
			snapshot.nextTier shouldBe AchievementTier.SILVER
			snapshot.nextTierTarget shouldBe 100L
		}

		@Test
		fun `fully completed snapshot has null next tier`() {
			val snapshot = AchievementSnapshot(
				definition = explorerDefinition(),
				currentValue = 20_000L,
				currentTier = AchievementTier.DIAMOND,
				nextTier = null,
				nextTierTarget = null,
				progress = 1.0f,
			)
			snapshot.currentTier shouldBe AchievementTier.DIAMOND
			snapshot.nextTier.shouldBeNull()
			snapshot.nextTierTarget.shouldBeNull()
			snapshot.progress shouldBe 1.0f
		}

		@Test
		fun `snapshot preserves definition reference`() {
			val def = explorerDefinition()
			val snapshot = AchievementSnapshot(
				definition = def,
				currentValue = 5L,
				currentTier = null,
				nextTier = AchievementTier.BRONZE,
				nextTierTarget = 10L,
				progress = 0.5f,
			)
			snapshot.definition shouldBe def
			snapshot.definition.id shouldBe "explorer_cells"
		}

		@Test
		fun `snapshot progress is between 0 and 1`() {
			val snapshot = AchievementSnapshot(
				definition = explorerDefinition(),
				currentValue = 50L,
				currentTier = AchievementTier.BRONZE,
				nextTier = AchievementTier.SILVER,
				nextTierTarget = 100L,
				progress = 0.5f,
			)
			assert(snapshot.progress in 0.0f..1.0f)
		}
	}

	@Nested
	inner class DataClassEquality {

		@Test
		fun `definitions with same values are equal`() {
			explorerDefinition() shouldBe explorerDefinition()
		}

		@Test
		fun `definitions with different ids are not equal`() {
			val def1 = explorerDefinition()
			val def2 = def1.copy(id = "different_id")
			assert(def1 != def2)
		}

		@Test
		fun `definition copy with changed metric preserves other fields`() {
			val original = explorerDefinition()
			val modified = original.copy(metric = "new_metric")
			modified.id shouldBe original.id
			modified.category shouldBe original.category
			modified.titleRes shouldBe original.titleRes
			modified.metric shouldBe "new_metric"
		}

		@Test
		fun `snapshot with same values are equal`() {
			val def = explorerDefinition()
			val snap1 = AchievementSnapshot(def, 10L, AchievementTier.BRONZE, AchievementTier.SILVER, 100L, 0.1f)
			val snap2 = AchievementSnapshot(def, 10L, AchievementTier.BRONZE, AchievementTier.SILVER, 100L, 0.1f)
			snap1 shouldBe snap2
		}
	}

	@Nested
	inner class PartialTierMaps {

		@Test
		fun `definition with only BRONZE tier is valid`() {
			val def = AchievementDefinition(
				id = "milestone_first_trip",
				category = AchievementCategory.MILESTONES,
				titleRes = "milestone_first_trip_title",
				descriptionRes = "milestone_first_trip_desc",
				metric = "trip_count",
				tiers = mapOf(AchievementTier.BRONZE to 1L),
			)
			def.tiers.size shouldBe 1
			def.tiers[AchievementTier.BRONZE] shouldBe 1L
			def.tiers[AchievementTier.SILVER].shouldBeNull()
		}

		@Test
		fun `definition with subset of tiers`() {
			val def = AchievementDefinition(
				id = "streak_weekly",
				category = AchievementCategory.STREAKS,
				titleRes = "streak_weekly_title",
				descriptionRes = "streak_weekly_desc",
				metric = "weekly_streak",
				tiers = mapOf(
					AchievementTier.BRONZE to 2L,
					AchievementTier.GOLD to 10L,
				),
			)
			def.tiers.size shouldBe 2
			def.tiers.containsKey(AchievementTier.SILVER) shouldBe false
			def.tiers.containsKey(AchievementTier.DIAMOND) shouldBe false
		}
	}
}
