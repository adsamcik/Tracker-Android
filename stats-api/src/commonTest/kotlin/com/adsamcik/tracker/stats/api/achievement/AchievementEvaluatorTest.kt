package com.adsamcik.tracker.stats.api.achievement

import com.adsamcik.tracker.stats.api.AchievementCategory
import com.adsamcik.tracker.stats.api.AchievementDefinition
import com.adsamcik.tracker.stats.api.AchievementTier
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class AchievementEvaluatorTest {

	private val fourTierDef = AchievementDefinition(
		id = "test_achievement",
		category = AchievementCategory.EXPLORATION,
		titleRes = "title",
		descriptionRes = "desc",
		metric = "test_metric",
		tiers = mapOf(
			AchievementTier.BRONZE to 10L,
			AchievementTier.SILVER to 50L,
			AchievementTier.GOLD to 200L,
			AchievementTier.DIAMOND to 1000L,
		),
	)

	private val singleTierDef = AchievementDefinition(
		id = "milestone_single",
		category = AchievementCategory.MILESTONES,
		titleRes = "title",
		descriptionRes = "desc",
		metric = "milestone_metric",
		tiers = mapOf(AchievementTier.BRONZE to 1L),
	)

	private val evaluator = AchievementEvaluator(listOf(fourTierDef, singleTierDef))

	@Nested
	inner class Snapshot {

		@Test
		fun `zero value yields null current tier and BRONZE next tier`() {
			val snap = evaluator.snapshot(fourTierDef, 0L)
			snap.currentTier.shouldBeNull()
			snap.nextTier shouldBe AchievementTier.BRONZE
			snap.nextTierTarget shouldBe 10L
			snap.currentValue shouldBe 0L
		}

		@Test
		fun `progress toward first tier is fractional`() {
			val snap = evaluator.snapshot(fourTierDef, 5L)
			snap.currentTier.shouldBeNull()
			snap.nextTier shouldBe AchievementTier.BRONZE
			snap.progress shouldBe (0.5f plusOrMinus 0.01f)
		}

		@Test
		fun `exactly at BRONZE threshold unlocks BRONZE`() {
			val snap = evaluator.snapshot(fourTierDef, 10L)
			snap.currentTier shouldBe AchievementTier.BRONZE
			snap.nextTier shouldBe AchievementTier.SILVER
			snap.nextTierTarget shouldBe 50L
		}

		@Test
		fun `between BRONZE and SILVER shows inter-tier progress`() {
			// 30 is between 10 (BRONZE) and 50 (SILVER)
			// progress = (30 - 10) / (50 - 10) = 20/40 = 0.5
			val snap = evaluator.snapshot(fourTierDef, 30L)
			snap.currentTier shouldBe AchievementTier.BRONZE
			snap.nextTier shouldBe AchievementTier.SILVER
			snap.progress shouldBe (0.5f plusOrMinus 0.01f)
		}

		@Test
		fun `exactly at SILVER threshold unlocks SILVER`() {
			val snap = evaluator.snapshot(fourTierDef, 50L)
			snap.currentTier shouldBe AchievementTier.SILVER
			snap.nextTier shouldBe AchievementTier.GOLD
		}

		@Test
		fun `at DIAMOND threshold is fully completed`() {
			val snap = evaluator.snapshot(fourTierDef, 1000L)
			snap.currentTier shouldBe AchievementTier.DIAMOND
			snap.nextTier.shouldBeNull()
			snap.nextTierTarget.shouldBeNull()
			snap.progress shouldBe 1.0f
		}

		@Test
		fun `exceeding DIAMOND threshold is still fully completed`() {
			val snap = evaluator.snapshot(fourTierDef, 9999L)
			snap.currentTier shouldBe AchievementTier.DIAMOND
			snap.nextTier.shouldBeNull()
			snap.progress shouldBe 1.0f
		}

		@Test
		fun `single-tier definition at threshold is fully completed`() {
			val snap = evaluator.snapshot(singleTierDef, 1L)
			snap.currentTier shouldBe AchievementTier.BRONZE
			snap.nextTier.shouldBeNull()
			snap.progress shouldBe 1.0f
		}

		@Test
		fun `single-tier definition below threshold shows progress`() {
			val snap = evaluator.snapshot(singleTierDef, 0L)
			snap.currentTier.shouldBeNull()
			snap.nextTier shouldBe AchievementTier.BRONZE
			snap.progress shouldBe 0.0f
		}

		@Test
		fun `progress is clamped to 0 to 1 range`() {
			val snap = evaluator.snapshot(fourTierDef, 5L)
			assert(snap.progress in 0.0f..1.0f) {
				"Progress ${snap.progress} is out of [0,1] range"
			}
		}

		@Test
		fun `snapshot preserves definition reference`() {
			val snap = evaluator.snapshot(fourTierDef, 100L)
			snap.definition shouldBe fourTierDef
		}
	}

	@Nested
	inner class Snapshots {

		@Test
		fun `snapshots returns results for matching metric`() {
			val results = evaluator.snapshots("test_metric", 30L)
			results shouldHaveSize 1
			results[0].definition shouldBe fourTierDef
		}

		@Test
		fun `snapshots returns empty for unknown metric`() {
			evaluator.snapshots("unknown_metric", 100L).shouldBeEmpty()
		}

		@Test
		fun `snapshots returns multiple when metric is shared`() {
			val sharedDef = fourTierDef.copy(id = "another_test", metric = "test_metric")
			val eval = AchievementEvaluator(listOf(fourTierDef, sharedDef))
			val results = eval.snapshots("test_metric", 30L)
			results shouldHaveSize 2
		}
	}

	@Nested
	inner class Evaluate {

		@Test
		fun `evaluate detects new tier unlock from no previous progress`() {
			val results = evaluator.evaluate(
				metric = "test_metric",
				currentValue = 15L,
				previousProgress = emptyMap(),
			)
			results shouldHaveSize 1
			results[0].currentTier shouldBe AchievementTier.BRONZE
		}

		@Test
		fun `evaluate returns empty when tier has not changed`() {
			val results = evaluator.evaluate(
				metric = "test_metric",
				currentValue = 15L,
				previousProgress = mapOf("test_achievement" to (10L to AchievementTier.BRONZE)),
			)
			results.shouldBeEmpty()
		}

		@Test
		fun `evaluate detects tier upgrade from BRONZE to SILVER`() {
			val results = evaluator.evaluate(
				metric = "test_metric",
				currentValue = 60L,
				previousProgress = mapOf("test_achievement" to (10L to AchievementTier.BRONZE)),
			)
			results shouldHaveSize 1
			results[0].currentTier shouldBe AchievementTier.SILVER
		}

		@Test
		fun `evaluate returns empty for unknown metric`() {
			evaluator.evaluate("unknown", 100L, emptyMap()).shouldBeEmpty()
		}

		@Test
		fun `evaluate detects first tier unlock from null previous tier`() {
			val results = evaluator.evaluate(
				metric = "test_metric",
				currentValue = 10L,
				previousProgress = mapOf("test_achievement" to (0L to null)),
			)
			results shouldHaveSize 1
			results[0].currentTier shouldBe AchievementTier.BRONZE
		}

		@Test
		fun `evaluate with value below any tier and no previous returns empty`() {
			val results = evaluator.evaluate(
				metric = "test_metric",
				currentValue = 5L,
				previousProgress = emptyMap(),
			)
			// No previous = previousTier is null, current tier is null → no change
			results.shouldBeEmpty()
		}
	}

	@Nested
	inner class EvaluateForUnlocks {

		@Test
		fun `single tier crossed from null previous`() {
			val unlocks = evaluator.evaluateForUnlocks(fourTierDef, 15L, null)
			unlocks shouldHaveSize 1
			unlocks[0].achievementId shouldBe "test_achievement"
			unlocks[0].tier shouldBe AchievementTier.BRONZE
		}

		@Test
		fun `multiple tiers crossed in a single jump`() {
			val unlocks = evaluator.evaluateForUnlocks(fourTierDef, 250L, null)
			unlocks shouldHaveSize 3
			unlocks[0].tier shouldBe AchievementTier.BRONZE
			unlocks[1].tier shouldBe AchievementTier.SILVER
			unlocks[2].tier shouldBe AchievementTier.GOLD
		}

		@Test
		fun `all tiers crossed at once`() {
			val unlocks = evaluator.evaluateForUnlocks(fourTierDef, 1000L, null)
			unlocks shouldHaveSize 4
			unlocks.map { it.tier } shouldBe listOf(
				AchievementTier.BRONZE,
				AchievementTier.SILVER,
				AchievementTier.GOLD,
				AchievementTier.DIAMOND,
			)
		}

		@Test
		fun `only new tiers returned when previous tier exists`() {
			val unlocks = evaluator.evaluateForUnlocks(fourTierDef, 250L, AchievementTier.BRONZE)
			unlocks shouldHaveSize 2
			unlocks[0].tier shouldBe AchievementTier.SILVER
			unlocks[1].tier shouldBe AchievementTier.GOLD
		}

		@Test
		fun `no unlocks when value is below all thresholds`() {
			evaluator.evaluateForUnlocks(fourTierDef, 5L, null).shouldBeEmpty()
		}

		@Test
		fun `no unlocks when already at highest tier crossed`() {
			evaluator.evaluateForUnlocks(fourTierDef, 15L, AchievementTier.BRONZE).shouldBeEmpty()
		}

		@Test
		fun `no unlocks when already at DIAMOND`() {
			evaluator.evaluateForUnlocks(fourTierDef, 5000L, AchievementTier.DIAMOND).shouldBeEmpty()
		}

		@Test
		fun `single-tier definition from null previous`() {
			val unlocks = evaluator.evaluateForUnlocks(singleTierDef, 1L, null)
			unlocks shouldHaveSize 1
			unlocks[0].tier shouldBe AchievementTier.BRONZE
		}

		@Test
		fun `tier unlock preserves achievement id`() {
			val unlocks = evaluator.evaluateForUnlocks(fourTierDef, 1000L, null)
			unlocks.forEach { it.achievementId shouldBe "test_achievement" }
		}
	}

	@Nested
	inner class DefaultCatalog {

		@Test
		fun `evaluator defaults to AchievementCatalog definitions`() {
			val defaultEvaluator = AchievementEvaluator()
			val snaps = defaultEvaluator.snapshots("cells_discovered", 50L)
			snaps.shouldNotBeNull()
			assert(snaps.isNotEmpty()) { "Default catalog should have cells_discovered achievements" }
		}
	}
}
