package com.adsamcik.tracker.stats.engine.achievement

import com.adsamcik.tracker.stats.api.AchievementCategory
import com.adsamcik.tracker.stats.api.AchievementDefinition
import com.adsamcik.tracker.stats.api.AchievementTier
import com.adsamcik.tracker.stats.api.achievement.AchievementCatalog
import com.adsamcik.tracker.stats.api.achievement.AchievementEvaluator
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class AchievementEvaluatorTest {

	private lateinit var evaluator: AchievementEvaluator

	private val testDefinition = AchievementDefinition(
		id = "test_achievement",
		category = AchievementCategory.EXPLORATION,
		titleRes = "test_title",
		descriptionRes = "test_desc",
		metric = "test_metric",
		tiers = mapOf(
			AchievementTier.BRONZE to 10L,
			AchievementTier.SILVER to 50L,
			AchievementTier.GOLD to 200L,
			AchievementTier.DIAMOND to 1000L,
		),
	)

	private val singleTierDefinition = AchievementDefinition(
		id = "test_milestone",
		category = AchievementCategory.MILESTONES,
		titleRes = "milestone_title",
		descriptionRes = "milestone_desc",
		metric = "test_metric",
		tiers = mapOf(
			AchievementTier.BRONZE to 1L,
		),
	)

	private val secondMetricDefinition = AchievementDefinition(
		id = "other_achievement",
		category = AchievementCategory.DISTANCE,
		titleRes = "other_title",
		descriptionRes = "other_desc",
		metric = "other_metric",
		tiers = mapOf(
			AchievementTier.BRONZE to 5L,
			AchievementTier.SILVER to 25L,
		),
	)

	@BeforeEach
	fun setUp() {
		evaluator = AchievementEvaluator(
			catalog = listOf(testDefinition, singleTierDefinition, secondMetricDefinition),
		)
	}

	@Nested
	inner class SnapshotTests {
		@Test
		fun `zero progress returns no tier`() {
			val snap = evaluator.snapshot(testDefinition, 0L)

			snap.currentTier.shouldBeNull()
			snap.nextTier shouldBe AchievementTier.BRONZE
			snap.nextTierTarget shouldBe 10L
			snap.progress shouldBe 0.0f
		}

		@Test
		fun `partial progress toward bronze`() {
			val snap = evaluator.snapshot(testDefinition, 5L)

			snap.currentTier.shouldBeNull()
			snap.nextTier shouldBe AchievementTier.BRONZE
			snap.nextTierTarget shouldBe 10L
			snap.progress shouldBe 0.5f
		}

		@Test
		fun `reaching bronze threshold unlocks bronze`() {
			val snap = evaluator.snapshot(testDefinition, 10L)

			snap.currentTier shouldBe AchievementTier.BRONZE
			snap.nextTier shouldBe AchievementTier.SILVER
			snap.nextTierTarget shouldBe 50L
			snap.progress shouldBe 0.0f
		}

		@Test
		fun `exceeding bronze shows progress toward silver`() {
			val snap = evaluator.snapshot(testDefinition, 30L)

			snap.currentTier shouldBe AchievementTier.BRONZE
			snap.nextTier shouldBe AchievementTier.SILVER
			snap.nextTierTarget shouldBe 50L
			// Progress = (30 - 10) / (50 - 10) = 20/40 = 0.5
			snap.progress shouldBe 0.5f
		}

		@Test
		fun `silver tier progress`() {
			val snap = evaluator.snapshot(testDefinition, 50L)

			snap.currentTier shouldBe AchievementTier.SILVER
			snap.nextTier shouldBe AchievementTier.GOLD
			snap.nextTierTarget shouldBe 200L
			snap.progress shouldBe 0.0f
		}

		@Test
		fun `gold tier with progress toward diamond`() {
			val snap = evaluator.snapshot(testDefinition, 600L)

			snap.currentTier shouldBe AchievementTier.GOLD
			snap.nextTier shouldBe AchievementTier.DIAMOND
			snap.nextTierTarget shouldBe 1000L
			// Progress = (600 - 200) / (1000 - 200) = 400/800 = 0.5
			snap.progress shouldBe 0.5f
		}

		@Test
		fun `diamond tier is final - no next tier`() {
			val snap = evaluator.snapshot(testDefinition, 1000L)

			snap.currentTier shouldBe AchievementTier.DIAMOND
			snap.nextTier.shouldBeNull()
			snap.nextTierTarget.shouldBeNull()
			snap.progress shouldBe 1.0f
		}

		@Test
		fun `exceeding diamond still shows completed`() {
			val snap = evaluator.snapshot(testDefinition, 5000L)

			snap.currentTier shouldBe AchievementTier.DIAMOND
			snap.nextTier.shouldBeNull()
			snap.nextTierTarget.shouldBeNull()
			snap.progress shouldBe 1.0f
			snap.currentValue shouldBe 5000L
		}

		@Test
		fun `milestone with single tier unlocked`() {
			val snap = evaluator.snapshot(singleTierDefinition, 1L)

			snap.currentTier shouldBe AchievementTier.BRONZE
			snap.nextTier.shouldBeNull()
			snap.nextTierTarget.shouldBeNull()
			snap.progress shouldBe 1.0f
		}

		@Test
		fun `milestone with single tier not yet unlocked`() {
			val snap = evaluator.snapshot(singleTierDefinition, 0L)

			snap.currentTier.shouldBeNull()
			snap.nextTier shouldBe AchievementTier.BRONZE
			snap.nextTierTarget shouldBe 1L
			snap.progress shouldBe 0.0f
		}

		@Test
		fun `negative value treated as zero progress`() {
			val snap = evaluator.snapshot(testDefinition, -5L)

			snap.currentTier.shouldBeNull()
			snap.nextTier shouldBe AchievementTier.BRONZE
			snap.nextTierTarget shouldBe 10L
			snap.progress shouldBe 0.0f
		}

		@Test
		fun `currentValue is preserved in snapshot`() {
			val snap = evaluator.snapshot(testDefinition, 42L)

			snap.currentValue shouldBe 42L
		}

		@Test
		fun `definition is preserved in snapshot`() {
			val snap = evaluator.snapshot(testDefinition, 42L)

			snap.definition shouldBe testDefinition
		}

		@Test
		fun `progress is between 0 and 1 for various values`() {
			for (value in listOf(0L, 1L, 5L, 9L, 10L, 25L, 49L, 50L, 100L, 199L, 200L, 500L, 999L, 1000L, 5000L)) {
				val snap = evaluator.snapshot(testDefinition, value)
				assert(snap.progress in 0.0f..1.0f) {
					"Progress ${snap.progress} out of range for value $value"
				}
			}
		}
	}

	@Nested
	inner class EvaluateTests {
		@Test
		fun `unknown metric returns empty list`() {
			val result = evaluator.evaluate(
				metric = "nonexistent_metric",
				currentValue = 100L,
				previousProgress = emptyMap(),
			)

			result.shouldBeEmpty()
		}

		@Test
		fun `no change from previous tier returns empty`() {
			val result = evaluator.evaluate(
				metric = "test_metric",
				currentValue = 15L,
				previousProgress = mapOf(
					"test_achievement" to (10L to AchievementTier.BRONZE),
					"test_milestone" to (1L to AchievementTier.BRONZE),
				),
			)

			result.shouldBeEmpty()
		}

		@Test
		fun `new tier unlock is detected`() {
			val result = evaluator.evaluate(
				metric = "test_metric",
				currentValue = 10L,
				previousProgress = mapOf(
					"test_achievement" to (5L to null),
					"test_milestone" to (1L to AchievementTier.BRONZE),
				),
			)

			result shouldHaveSize 1
			result[0].definition.id shouldBe "test_achievement"
			result[0].currentTier shouldBe AchievementTier.BRONZE
		}

		@Test
		fun `multiple achievements for same metric evaluated`() {
			val result = evaluator.evaluate(
				metric = "test_metric",
				currentValue = 10L,
				previousProgress = emptyMap(),
			)

			// Both test_achievement (bronze) and test_milestone (bronze) should trigger
			result shouldHaveSize 2
			val ids = result.map { it.definition.id }.toSet()
			assert("test_achievement" in ids)
			assert("test_milestone" in ids)
		}

		@Test
		fun `only matching metric achievements are returned`() {
			val result = evaluator.evaluate(
				metric = "other_metric",
				currentValue = 5L,
				previousProgress = emptyMap(),
			)

			result shouldHaveSize 1
			result[0].definition.id shouldBe "other_achievement"
			result[0].currentTier shouldBe AchievementTier.BRONZE
		}

		@Test
		fun `missing previous progress treated as no tier`() {
			val result = evaluator.evaluate(
				metric = "test_metric",
				currentValue = 10L,
				previousProgress = emptyMap(),
			)

			// Both should change from null to BRONZE
			result shouldHaveSize 2
			result.forEach { snap ->
				snap.currentTier shouldBe AchievementTier.BRONZE
			}
		}

		@Test
		fun `tier skip from none to silver detected`() {
			val result = evaluator.evaluate(
				metric = "test_metric",
				currentValue = 50L,
				previousProgress = mapOf(
					"test_achievement" to (0L to null),
					"test_milestone" to (0L to null),
				),
			)

			result shouldHaveSize 2
			val mainAchievement = result.first { it.definition.id == "test_achievement" }
			mainAchievement.currentTier shouldBe AchievementTier.SILVER
		}

		@Test
		fun `zero value with no previous progress returns empty`() {
			val result = evaluator.evaluate(
				metric = "test_metric",
				currentValue = 0L,
				previousProgress = mapOf(
					"test_achievement" to (0L to null),
					"test_milestone" to (0L to null),
				),
			)

			result.shouldBeEmpty()
		}
	}

	@Nested
	inner class EdgeCases {
		@Test
		fun `exactly at each tier boundary`() {
			val tiers = listOf(
				10L to AchievementTier.BRONZE,
				50L to AchievementTier.SILVER,
				200L to AchievementTier.GOLD,
				1000L to AchievementTier.DIAMOND,
			)

			tiers.forEach { (value, expectedTier) ->
				val snap = evaluator.snapshot(testDefinition, value)
				snap.currentTier shouldBe expectedTier
			}
		}

		@Test
		fun `one below each tier boundary`() {
			val snap9 = evaluator.snapshot(testDefinition, 9L)
			snap9.currentTier.shouldBeNull()

			val snap49 = evaluator.snapshot(testDefinition, 49L)
			snap49.currentTier shouldBe AchievementTier.BRONZE

			val snap199 = evaluator.snapshot(testDefinition, 199L)
			snap199.currentTier shouldBe AchievementTier.SILVER

			val snap999 = evaluator.snapshot(testDefinition, 999L)
			snap999.currentTier shouldBe AchievementTier.GOLD
		}

		@Test
		fun `progress increases monotonically within a tier range`() {
			var lastProgress = -1.0f
			for (value in 10L..49L) {
				val snap = evaluator.snapshot(testDefinition, value)
				snap.progress shouldBeGreaterThan lastProgress
				lastProgress = snap.progress
			}
		}

		@Test
		fun `evaluator with empty catalog returns empty for any metric`() {
			val emptyEvaluator = AchievementEvaluator(catalog = emptyList())

			val result = emptyEvaluator.evaluate(
				metric = "test_metric",
				currentValue = 100L,
				previousProgress = emptyMap(),
			)

			result.shouldBeEmpty()
		}

		@Test
		fun `default evaluator uses AchievementCatalog`() {
			val defaultEvaluator = AchievementEvaluator()

			val snap = defaultEvaluator.snapshot(
				AchievementCatalog.byId("explorer_cells")!!,
				currentValue = 10L,
			)

			snap.currentTier shouldBe AchievementTier.BRONZE
		}
	}

	@Nested
	inner class EvaluateForUnlocksTests {

		@Test
		fun `no previous tier - value at bronze - returns only bronze`() {
			val unlocks = evaluator.evaluateForUnlocks(testDefinition, 10L, null)

			unlocks shouldHaveSize 1
			unlocks[0].tier shouldBe AchievementTier.BRONZE
			unlocks[0].achievementId shouldBe "test_achievement"
		}

		@Test
		fun `no previous tier - value at gold - returns bronze, silver, gold`() {
			val unlocks = evaluator.evaluateForUnlocks(testDefinition, 200L, null)

			unlocks shouldHaveSize 3
			unlocks[0].tier shouldBe AchievementTier.BRONZE
			unlocks[1].tier shouldBe AchievementTier.SILVER
			unlocks[2].tier shouldBe AchievementTier.GOLD
		}

		@Test
		fun `previous bronze - value at diamond - returns silver, gold, diamond`() {
			val unlocks = evaluator.evaluateForUnlocks(
				testDefinition,
				1000L,
				AchievementTier.BRONZE,
			)

			unlocks shouldHaveSize 3
			unlocks[0].tier shouldBe AchievementTier.SILVER
			unlocks[1].tier shouldBe AchievementTier.GOLD
			unlocks[2].tier shouldBe AchievementTier.DIAMOND
		}

		@Test
		fun `previous diamond - value at diamond - returns empty`() {
			val unlocks = evaluator.evaluateForUnlocks(
				testDefinition,
				5000L,
				AchievementTier.DIAMOND,
			)

			unlocks.shouldBeEmpty()
		}

		@Test
		fun `value below any tier - returns empty`() {
			val unlocks = evaluator.evaluateForUnlocks(testDefinition, 5L, null)

			unlocks.shouldBeEmpty()
		}

		@Test
		fun `single-tier milestone - no previous - value met - returns bronze`() {
			val unlocks = evaluator.evaluateForUnlocks(singleTierDefinition, 1L, null)

			unlocks shouldHaveSize 1
			unlocks[0].tier shouldBe AchievementTier.BRONZE
		}

		@Test
		fun `single-tier milestone - already bronze - returns empty`() {
			val unlocks = evaluator.evaluateForUnlocks(
				singleTierDefinition,
				10L,
				AchievementTier.BRONZE,
			)

			unlocks.shouldBeEmpty()
		}

		@Test
		fun `import scenario - jump from none to diamond`() {
			val unlocks = evaluator.evaluateForUnlocks(testDefinition, 5000L, null)

			unlocks shouldHaveSize 4
			unlocks.map { it.tier } shouldBe listOf(
				AchievementTier.BRONZE,
				AchievementTier.SILVER,
				AchievementTier.GOLD,
				AchievementTier.DIAMOND,
			)
		}
	}
}
