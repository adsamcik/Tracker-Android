package com.adsamcik.tracker.stats.api.rule

import com.adsamcik.tracker.stats.api.AchievementTier
import com.adsamcik.tracker.stats.api.metric.TimeWindow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContainExactly
import kotlin.test.Test

class RuleEvaluatorTest {

	private val evaluator = RuleEvaluator()

	private fun achievement(id: String, tiers: Map<AchievementTier, Long>) =
		Rule(id = id, kind = RuleKind.Achievement, metric = "steps", target = RuleTarget.Tiered(tiers))

	private fun challenge(id: String, target: Double) =
		Rule(id = id, kind = RuleKind.Challenge, metric = "steps", target = RuleTarget.Single(target))

	@Test
	fun `unchanged returns Unchanged without examining target`() {
		val rule = achievement("steps", mapOf(AchievementTier.BRONZE to 100L))
		val instance = RuleInstance(rule, TimeWindow.Cumulative, previousValue = 50L)
		val result = evaluator.evaluate(instance, currentValue = 50L)
		(result is RuleEvaluationResult.Unchanged) shouldBe true
	}

	@Test
	fun `achievement crossing first tier emits TierUnlocked BRONZE`() {
		val rule = achievement(
			"steps",
			mapOf(AchievementTier.BRONZE to 100L, AchievementTier.SILVER to 500L),
		)
		val instance = RuleInstance(rule, TimeWindow.Cumulative, previousValue = 50L, previousTier = null)
		val result = evaluator.evaluate(instance, currentValue = 150L)
		(result is RuleEvaluationResult.TierUnlocked) shouldBe true
		result as RuleEvaluationResult.TierUnlocked
		result.unlocked shouldBe AchievementTier.BRONZE
		result.nextTierTarget shouldBe 500L
	}

	@Test
	fun `achievement progress within same tier emits ProgressUpdated`() {
		val rule = achievement(
			"steps",
			mapOf(AchievementTier.BRONZE to 100L, AchievementTier.SILVER to 500L),
		)
		val instance = RuleInstance(
			rule, TimeWindow.Cumulative,
			previousValue = 200L, previousTier = AchievementTier.BRONZE,
		)
		val result = evaluator.evaluate(instance, currentValue = 300L)
		(result is RuleEvaluationResult.ProgressUpdated) shouldBe true
		(result as RuleEvaluationResult.ProgressUpdated).nextTierTarget shouldBe 500L
	}

	@Test
	fun `achievement jumping multiple tiers reports highest unlocked`() {
		val rule = achievement(
			"steps",
			mapOf(
				AchievementTier.BRONZE to 100L,
				AchievementTier.SILVER to 500L,
				AchievementTier.GOLD to 1000L,
			),
		)
		val instance = RuleInstance(rule, TimeWindow.Cumulative, previousValue = 0L, previousTier = null)
		val result = evaluator.evaluate(instance, currentValue = 1500L)
		(result is RuleEvaluationResult.TierUnlocked) shouldBe true
		result as RuleEvaluationResult.TierUnlocked
		result.unlocked shouldBe AchievementTier.GOLD
		result.nextTierTarget shouldBe null
	}

	@Test
	fun `achievement progress before first tier reports first target`() {
		val rule = achievement(
			"steps",
			mapOf(AchievementTier.BRONZE to 100L, AchievementTier.SILVER to 500L),
		)
		val instance = RuleInstance(rule, TimeWindow.Cumulative, previousValue = 10L, previousTier = null)
		val result = evaluator.evaluate(instance, currentValue = 20L)
		(result is RuleEvaluationResult.ProgressUpdated) shouldBe true
		(result as RuleEvaluationResult.ProgressUpdated).nextTierTarget shouldBe 100L
	}

	@Test
	fun `crossedTiers lists every tier user passed since previousTier`() {
		val target = RuleTarget.Tiered(
			mapOf(
				AchievementTier.BRONZE to 100L,
				AchievementTier.SILVER to 500L,
				AchievementTier.GOLD to 1000L,
				AchievementTier.DIAMOND to 5000L,
			),
		)
		val crossed = evaluator.crossedTiers(target, currentValue = 1500L, previousTier = null)
		crossed shouldContainExactly listOf(
			AchievementTier.BRONZE, AchievementTier.SILVER, AchievementTier.GOLD,
		)
	}

	@Test
	fun `crossedTiers filters tiers up to and including previousTier`() {
		val target = RuleTarget.Tiered(
			mapOf(
				AchievementTier.BRONZE to 100L,
				AchievementTier.SILVER to 500L,
				AchievementTier.GOLD to 1000L,
			),
		)
		val crossed = evaluator.crossedTiers(target, currentValue = 1500L, previousTier = AchievementTier.SILVER)
		crossed shouldContainExactly listOf(AchievementTier.GOLD)
	}

	@Test
	fun `challenge reaching target emits Completed`() {
		val rule = challenge("walk_5k", target = 5000.0)
		val instance = RuleInstance(rule, TimeWindow.Interval(0L, 1000L), previousValue = 4000L)
		val result = evaluator.evaluate(instance, currentValue = 5000L)
		(result is RuleEvaluationResult.Completed) shouldBe true
	}

	@Test
	fun `challenge below target emits ChallengeProgress`() {
		val rule = challenge("walk_5k", target = 5000.0)
		val instance = RuleInstance(rule, TimeWindow.Interval(0L, 1000L), previousValue = 1000L)
		val result = evaluator.evaluate(instance, currentValue = 2000L)
		(result is RuleEvaluationResult.ChallengeProgress) shouldBe true
	}
}
