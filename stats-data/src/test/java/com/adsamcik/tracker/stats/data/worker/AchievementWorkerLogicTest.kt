package com.adsamcik.tracker.stats.data.worker

import com.adsamcik.tracker.shared.base.database.dao.AchievementProgressDao
import com.adsamcik.tracker.shared.base.database.data.AchievementProgressEntity
import com.adsamcik.tracker.stats.api.AchievementCategory
import com.adsamcik.tracker.stats.api.AchievementDefinition
import com.adsamcik.tracker.stats.api.AchievementTier
import com.adsamcik.tracker.stats.api.repository.AchievementMetricsProvider
import com.adsamcik.tracker.stats.api.rule.RuleEvaluationResult
import com.adsamcik.tracker.stats.api.rule.RuleEvaluator
import com.adsamcik.tracker.stats.api.rule.RuleTarget
import com.adsamcik.tracker.stats.data.achievement.AchievementRuleRegistry
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Tests for the achievement worker's rule-evaluation logic.
 *
 * Since [AchievementWorker] is a CoroutineWorker requiring Android context,
 * we test the core logic through [RuleEvaluator] and [AchievementRuleRegistry]
 * with the same patterns used in the worker.
 */
class AchievementWorkerLogicTest {

	private val testDefinition = AchievementDefinition(
		id = "test_cells",
		category = AchievementCategory.EXPLORATION,
		titleRes = "test_title",
		descriptionRes = "test_desc",
		metric = "cells_discovered",
		tiers = mapOf(
			AchievementTier.BRONZE to 10L,
			AchievementTier.SILVER to 50L,
			AchievementTier.GOLD to 200L,
			AchievementTier.DIAMOND to 1000L,
		),
	)

	private val evaluator = RuleEvaluator()
	private val target = RuleTarget.Tiered(testDefinition.tiers)

	@Test
	fun `tier-skip emits all crossed tiers`() = runTest {
		// Simulates jumping from no progress to GOLD level
		val unlocks = evaluator.crossedTiers(target, currentValue = 200L, previousTier = null)

		unlocks shouldHaveSize 3
		unlocks[0] shouldBe AchievementTier.BRONZE
		unlocks[1] shouldBe AchievementTier.SILVER
		unlocks[2] shouldBe AchievementTier.GOLD
	}

	@Test
	fun `normal progression emits only new tier`() = runTest {
		val unlocks = evaluator.crossedTiers(
			target = target,
			currentValue = 50L,
			previousTier = AchievementTier.BRONZE,
		)

		unlocks shouldHaveSize 1
		unlocks[0] shouldBe AchievementTier.SILVER
	}

	@Test
	fun `no change emits nothing`() = runTest {
		val unlocks = evaluator.crossedTiers(
			target = target,
			currentValue = 30L,
			previousTier = AchievementTier.BRONZE,
		)

		unlocks shouldHaveSize 0
	}

	@Test
	fun `registry reads previous tier from tier column`() = runTest {
		val dao: AchievementProgressDao = mockk()
		coEvery { dao.getAll() } returns listOf(
			AchievementProgressEntity(
				achievementId = "test_cells",
				currentValue = 5L,
				targetValue = 10L,
				tier = AchievementTier.BRONZE.ordinal,
				unlockedAt = null,
				updatedAt = 0L,
			),
		)
		val registry = AchievementRuleRegistry(dao, listOf(testDefinition))

		val instance = registry.allInstances().single()

		instance.previousTier shouldBe AchievementTier.BRONZE
	}

	@Test
	fun `dao upsert is called with correct values`() = runTest {
		val dao: AchievementProgressDao = mockk()
		val metricsProvider: AchievementMetricsProvider = mockk()

		coEvery { metricsProvider.collect() } returns mapOf("cells_discovered" to 200L)
		coEvery { dao.getAll() } returns emptyList()
		coEvery { dao.insertIfNew(any()) } returns 1L
		coEvery {
			dao.updateProgress(
				achievementId = any(),
				currentValue = any(),
				targetValue = any(),
				tier = any(),
				unlockedAt = any(),
				updatedAt = any(),
			)
		} just Runs
		val registry = AchievementRuleRegistry(dao, listOf(testDefinition))

		val metrics = metricsProvider.collect()
		for (instance in registry.allInstances()) {
			val metricValue = metrics[instance.rule.metric] ?: 0L
			val target = instance.rule.target as RuleTarget.Tiered
			val result = evaluator.evaluate(instance, metricValue)
			val newUnlocks = evaluator.crossedTiers(target, metricValue, instance.previousTier)
			val nextTierTarget = when (result) {
				is RuleEvaluationResult.TierUnlocked -> result.nextTierTarget ?: metricValue
				is RuleEvaluationResult.ProgressUpdated -> result.nextTierTarget ?: metricValue
				is RuleEvaluationResult.Unchanged -> nextTierTarget(target, instance.previousTier) ?: metricValue
				is RuleEvaluationResult.Completed,
				is RuleEvaluationResult.ChallengeProgress -> metricValue
			}
			val highestNewTier = newUnlocks.lastOrNull()
			val entity = AchievementProgressEntity(
				achievementId = instance.rule.id,
				currentValue = metricValue,
				targetValue = nextTierTarget,
				tier = highestNewTier?.ordinal ?: instance.previousTier?.ordinal,
				unlockedAt = if (newUnlocks.isNotEmpty()) System.currentTimeMillis() else null,
				updatedAt = System.currentTimeMillis(),
			)
			dao.insertIfNew(entity)
			dao.updateProgress(
				achievementId = entity.achievementId,
				currentValue = entity.currentValue,
				targetValue = entity.targetValue,
				tier = entity.tier,
				unlockedAt = entity.unlockedAt,
				updatedAt = entity.updatedAt,
			)
		}

		coVerify(exactly = 1) { dao.insertIfNew(any()) }
		coVerify(exactly = 1) {
			dao.updateProgress(
				achievementId = "test_cells",
				currentValue = 200L,
				targetValue = 1000L, // Next tier is DIAMOND at 1000
				tier = AchievementTier.GOLD.ordinal,
				unlockedAt = any(),
				updatedAt = any(),
			)
		}
	}

	@Test
	fun `running evaluation twice does not re-unlock`() = runTest {
		// First run: no previous progress, value = 200 -> unlocks BRONZE, SILVER, GOLD
		val firstUnlocks = evaluator.crossedTiers(target, currentValue = 200L, previousTier = null)
		firstUnlocks shouldHaveSize 3

		// Second run: previous tier is GOLD, value = 200 -> no new unlocks
		val secondUnlocks = evaluator.crossedTiers(
			target = target,
			currentValue = 200L,
			previousTier = AchievementTier.GOLD,
		)
		secondUnlocks shouldHaveSize 0
	}

	private fun nextTierTarget(
		target: RuleTarget.Tiered,
		currentTier: AchievementTier?,
	): Long? {
		val sorted = target.tiers.entries.sortedBy { it.key.ordinal }
		return if (currentTier == null) {
			sorted.firstOrNull()?.value
		} else {
			sorted.firstOrNull { it.key.ordinal > currentTier.ordinal }?.value
		}
	}
}
