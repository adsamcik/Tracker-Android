package com.adsamcik.tracker.game.ranking

import com.adsamcik.tracker.feature.game.api.ranking.WeeklyRank
import com.adsamcik.tracker.feature.game.api.ranking.WeeklyRankTier
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.Random

internal object WeeklyRankingCalculator {

	fun generateTiers(referenceDate: LocalDate): List<WeeklyRankTier> {
		val weekStart = referenceDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
		val random = Random(weekStart.toEpochDay())
		val silver = random.nextIntInclusive(200, 400)
		val gold = silver + random.nextIntInclusive(300, 500)
		val platinum = gold + random.nextIntInclusive(400, 700)
		val diamond = platinum + random.nextIntInclusive(600, 1_000)

		return listOf(
			WeeklyRankTier(WeeklyRank.BRONZE, 0),
			WeeklyRankTier(WeeklyRank.SILVER, silver),
			WeeklyRankTier(WeeklyRank.GOLD, gold),
			WeeklyRankTier(WeeklyRank.PLATINUM, platinum),
			WeeklyRankTier(WeeklyRank.DIAMOND, diamond),
		)
	}

	fun calculateRanking(
		points: Double,
		tiers: List<WeeklyRankTier>,
	): RankingProgress {
		val normalizedPoints = points.coerceAtLeast(0.0)
		val orderedTiers = tiers.sortedBy { it.minimumPoints }
		val currentTier = orderedTiers.lastOrNull { normalizedPoints >= it.minimumPoints }
			?: WeeklyRankTier(WeeklyRank.BRONZE, 0)
		val nextTier = orderedTiers.firstOrNull { it.minimumPoints > normalizedPoints }
		val progress = if (nextTier == null) {
			1f
		} else {
			val tierRange = nextTier.minimumPoints - currentTier.minimumPoints
			if (tierRange <= 0) {
				0f
			} else {
				((normalizedPoints - currentTier.minimumPoints) / tierRange)
					.toFloat()
					.coerceIn(0f, 1f)
			}
		}

		return RankingProgress(
			currentRank = currentTier.rank,
			nextTier = nextTier,
			progressToNextRank = progress,
		)
	}

	private fun Random.nextIntInclusive(from: Int, through: Int): Int =
		from + nextInt(through - from + 1)
}

internal data class RankingProgress(
	val currentRank: WeeklyRank,
	val nextTier: WeeklyRankTier?,
	val progressToNextRank: Float,
)
