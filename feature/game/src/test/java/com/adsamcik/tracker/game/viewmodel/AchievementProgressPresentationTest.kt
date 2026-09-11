package com.adsamcik.tracker.game.viewmodel

import com.adsamcik.tracker.game.data.AchievementProgress
import com.adsamcik.tracker.game.data.toProductProgress
import com.adsamcik.tracker.game.ui.compose.achievementDetailRows
import com.adsamcik.tracker.shared.base.database.data.AchievementProgressEntity
import com.adsamcik.tracker.stats.api.metric.MetricKey
import io.kotest.matchers.shouldBe
import org.junit.Test

class AchievementProgressPresentationTest {
	@Test
	fun `all four qualified Steps metrics are visible without fabricated unlock times`() {
		QUALIFIED_STEPS_METRICS.forEach { metric ->
			val product = requireNotNull(
				qualified(
					state = AchievementProgressEntity.AUTHORITY_STATE_READY,
					metric = metric,
				).toProductProgress(),
			)

			product.metric shouldBe metric
			product.lastValue shouldBe 3.0
			product.unlockedAt shouldBe null
			achievementSummaryState(listOf(product)).let { summary ->
				summary.totalUnlocked shouldBe 1
				summary.recentUnlocks shouldBe emptyList()
			}
		}
	}

	@Test
	fun `unavailable source metrics are absent from list and detail composition`() {
		QUALIFIED_STEPS_METRICS.forEach { metric ->
			qualified(AchievementProgressEntity.AUTHORITY_STATE_MATERIALIZING, metric)
				.toProductProgress() shouldBe null
			qualified(AchievementProgressEntity.AUTHORITY_STATE_UNVERIFIABLE, metric)
				.toProductProgress() shouldBe null
			qualified(AchievementProgressEntity.AUTHORITY_STATE_READY, metric, claimedTierIndex = null)
				.toProductProgress() shouldBe null
			AchievementProgressEntity(
				metricKey = metric.storageKey,
				lastTierIndex = 0,
				lastValue = 3.0,
				updatedAt = 999L,
			).toProductProgress() shouldBe null
		}
		val rows = achievementDetailRows(emptyList())

		(rows.none { it.definition.metric == MetricKey.STEPS_TOTAL }) shouldBe true
		(rows.none { it.definition.metric == MetricKey.BEST_DAILY_STEPS }) shouldBe true
		(rows.none { it.definition.metric == MetricKey.GOAL_STREAK_DAYS }) shouldBe true
		(rows.none { it.definition.metric == MetricKey.PERFECT_WEEKS }) shouldBe true
	}

	@Test
	fun `ordinary progress retains its persisted unlock time`() {
		val product = requireNotNull(
			AchievementProgressEntity(
				metricKey = MetricKey.DISTANCE_TOTAL_M.storageKey,
				lastTierIndex = 0,
				lastValue = 1_000.0,
				updatedAt = 123L,
				lastUnlockedAt = 123L,
			).toProductProgress(),
		)

		product shouldBe AchievementProgress(MetricKey.DISTANCE_TOTAL_M, 0, 1_000.0, 123L)
	}

	private fun qualified(
		state: String,
		metric: MetricKey = MetricKey.GOAL_STREAK_DAYS,
		claimedTierIndex: Int? = 0,
	) = AchievementProgressEntity(
		metricKey = metric.storageKey,
		lastTierIndex = 0,
		lastValue = 3.0,
		updatedAt = 999L,
		authorityKind = AchievementProgressEntity.AUTHORITY_QUALIFIED_STEPS_V1,
		authorityRevision = 4L,
		authorityDigest = "a".repeat(64),
		authorityState = state,
		qualifiedNotificationClaimedTierIndex = claimedTierIndex,
	)

	private companion object {
		val QUALIFIED_STEPS_METRICS = listOf(
			MetricKey.STEPS_TOTAL,
			MetricKey.BEST_DAILY_STEPS,
			MetricKey.GOAL_STREAK_DAYS,
			MetricKey.PERFECT_WEEKS,
		)
	}
}
