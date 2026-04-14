package com.adsamcik.tracker.stats.data.worker

import com.adsamcik.tracker.shared.base.database.dao.AchievementProgressDao
import com.adsamcik.tracker.shared.base.database.data.AchievementProgressEntity
import com.adsamcik.tracker.stats.api.AchievementCategory
import com.adsamcik.tracker.stats.api.AchievementDefinition
import com.adsamcik.tracker.stats.api.AchievementTier
import com.adsamcik.tracker.stats.api.repository.AchievementMetricsProvider
import com.adsamcik.tracker.stats.api.achievement.AchievementEvaluator
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Tests for the achievement worker's evaluation logic.
 *
 * Since [AchievementWorker] is a CoroutineWorker requiring Android context,
 * we test the core logic by calling the evaluator directly with the same
 * patterns used in the worker.
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

	private val evaluator = AchievementEvaluator(catalog = listOf(testDefinition))

	@Test
	fun `tier-skip emits all crossed tiers`() = runTest {
		// Simulates jumping from no progress to GOLD level
		val unlocks = evaluator.evaluateForUnlocks(testDefinition, 200L, null)

		unlocks.size shouldBe 3
		unlocks[0].tier shouldBe AchievementTier.BRONZE
		unlocks[1].tier shouldBe AchievementTier.SILVER
		unlocks[2].tier shouldBe AchievementTier.GOLD
	}

	@Test
	fun `normal progression emits only new tier`() = runTest {
		val unlocks = evaluator.evaluateForUnlocks(
			testDefinition,
			50L,
			AchievementTier.BRONZE,
		)

		unlocks.size shouldBe 1
		unlocks[0].tier shouldBe AchievementTier.SILVER
	}

	@Test
	fun `no change emits nothing`() = runTest {
		val unlocks = evaluator.evaluateForUnlocks(
			testDefinition,
			30L,
			AchievementTier.BRONZE,
		)

		unlocks.size shouldBe 0
	}

	@Test
	fun `unlockedAt null means no previous tier even if tier field is set`() = runTest {
		// Issue 2: tier=0 with unlockedAt=null should be treated as no tier
		val entity = AchievementProgressEntity(
			achievementId = "test_cells",
			currentValue = 5L,
			targetValue = 10L,
			tier = 0, // BRONZE ordinal
			unlockedAt = null, // But not actually unlocked!
			updatedAt = 0L,
		)

		// The worker logic uses unlockedAt as discriminator
		val tierOrdinal = entity.tier
		val previousTier = if (entity.unlockedAt != null && tierOrdinal != null) {
			AchievementTier.entries.getOrNull(tierOrdinal)
		} else {
			null
		}

		previousTier shouldBe null
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

		// Simulate what the worker does
		val metrics = metricsProvider.collect()
		val previousProgress = dao.getAll()
		val previousMap = previousProgress.associateBy { it.achievementId }

		for (definition in listOf(testDefinition)) {
			val metricValue = metrics[definition.metric] ?: 0L
			val previous = previousMap[definition.id]
			val previousTier = previous?.let { entity ->
				val tierOrdinal = entity.tier
				if (entity.unlockedAt != null && tierOrdinal != null) {
					AchievementTier.entries.getOrNull(tierOrdinal)
				} else {
					null
				}
			}

			val newUnlocks = evaluator.evaluateForUnlocks(definition, metricValue, previousTier)
			val nextTierTarget = definition.tiers.entries
				.sortedBy { it.key.ordinal }
				.firstOrNull { metricValue < it.value }
				?.value ?: metricValue
			val highestNewTier = newUnlocks.lastOrNull()?.tier

			val entity = AchievementProgressEntity(
				achievementId = definition.id,
				currentValue = metricValue,
				targetValue = nextTierTarget,
				tier = highestNewTier?.ordinal ?: previous?.tier,
				unlockedAt = if (newUnlocks.isNotEmpty()) System.currentTimeMillis() else null,
				updatedAt = System.currentTimeMillis(),
			)
			// Call the individual methods (upsert is a default method that chains them)
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
		// First run: no previous progress, value = 200 → unlocks BRONZE, SILVER, GOLD
		val firstUnlocks = evaluator.evaluateForUnlocks(testDefinition, 200L, null)
		firstUnlocks.size shouldBe 3

		// Second run: previous tier is GOLD, value = 200 → no new unlocks
		val secondUnlocks = evaluator.evaluateForUnlocks(
			testDefinition,
			200L,
			AchievementTier.GOLD,
		)
		secondUnlocks.size shouldBe 0
	}
}
