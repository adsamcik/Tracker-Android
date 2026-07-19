package com.adsamcik.tracker.game.ranking

import com.adsamcik.tracker.feature.game.api.ranking.WeeklyRank
import com.adsamcik.tracker.feature.game.api.ranking.WeeklyRankTier
import io.kotest.matchers.collections.shouldBeSortedBy
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.LocalDate
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("WeeklyRankingCalculator")
class WeeklyRankingCalculatorTest {

	@Test
	fun `generates the same thresholds for the same ISO week`() {
		val monday = LocalDate.of(2026, 7, 13)

		val first = WeeklyRankingCalculator.generateTiers(monday)
		val second = WeeklyRankingCalculator.generateTiers(monday.plusDays(6))

		first shouldBe second
		first.shouldBeSortedBy { it.minimumPoints }
	}

	@Test
	fun `generates different thresholds for adjacent ISO weeks`() {
		val first = WeeklyRankingCalculator.generateTiers(LocalDate.of(2026, 7, 13))
		val second = WeeklyRankingCalculator.generateTiers(LocalDate.of(2026, 7, 20))

		first shouldNotBe second
	}

	@Test
	fun `zero points starts at bronze`() {
		val result = WeeklyRankingCalculator.calculateRanking(points = 0.0, tiers = testTiers)

		result.currentRank shouldBe WeeklyRank.BRONZE
		result.nextTier?.rank shouldBe WeeklyRank.SILVER
		result.progressToNextRank shouldBe 0f
	}

	@Test
	fun `points exactly at a threshold qualify for that rank`() {
		val result = WeeklyRankingCalculator.calculateRanking(points = 250.0, tiers = testTiers)

		result.currentRank shouldBe WeeklyRank.SILVER
		result.nextTier?.rank shouldBe WeeklyRank.GOLD
		result.progressToNextRank shouldBe 0f
	}

	@Test
	fun `points between thresholds report proportional progress`() {
		val result = WeeklyRankingCalculator.calculateRanking(points = 500.0, tiers = testTiers)

		result.currentRank shouldBe WeeklyRank.SILVER
		result.nextTier?.rank shouldBe WeeklyRank.GOLD
		result.progressToNextRank shouldBe 0.5f
	}

	@Test
	fun `points above the maximum threshold stay at the highest rank`() {
		val result = WeeklyRankingCalculator.calculateRanking(points = 10_000.0, tiers = testTiers)

		result.currentRank shouldBe WeeklyRank.DIAMOND
		result.nextTier shouldBe null
		result.progressToNextRank shouldBe 1f
	}

	private companion object {
		val testTiers = listOf(
			WeeklyRankTier(WeeklyRank.BRONZE, 0),
			WeeklyRankTier(WeeklyRank.SILVER, 250),
			WeeklyRankTier(WeeklyRank.GOLD, 750),
			WeeklyRankTier(WeeklyRank.PLATINUM, 1_250),
			WeeklyRankTier(WeeklyRank.DIAMOND, 2_000),
		)
	}
}
