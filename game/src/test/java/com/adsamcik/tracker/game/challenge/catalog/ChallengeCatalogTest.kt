package com.adsamcik.tracker.game.challenge.catalog

import com.adsamcik.tracker.game.challenge.ChallengeDifficulty
import com.adsamcik.tracker.game.challenge.data.ChallengeType
import com.adsamcik.tracker.stats.api.metric.MetricKeys
import com.adsamcik.tracker.stats.api.metric.TimeWindow
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ChallengeCatalog")
class ChallengeCatalogTest {

	@Test
	fun `all ChallengeType values have a definition`() {
		ChallengeType.values().forEach { type ->
			ChallengeCatalog.byType(type).type shouldBe type
		}
	}

	@Test
	fun `definitions are unique by type`() {
		val types = ChallengeCatalog.definitions.map { it.type }
		types.size shouldBe types.toSet().size
	}

	@Test
	fun `definitions cover every ChallengeType exactly once`() {
		ChallengeCatalog.definitions.size shouldBe ChallengeType.values().size
	}

	@Test
	fun `all definitions reference known MetricKeys`() {
		val knownMetrics = setOf(
			MetricKeys.STEPS,
			MetricKeys.DISTANCE_M,
			MetricKeys.DISTANCE_ON_FOOT_M,
			MetricKeys.ACTIVE_MINUTES,
			MetricKeys.CELLS_DISCOVERED,
			MetricKeys.UNIQUE_AREAS,
			MetricKeys.TOTAL_DISTANCE_KM,
			MetricKeys.TOTAL_STEPS,
			MetricKeys.DAILY_STREAK,
			MetricKeys.WEEKLY_STREAK,
			MetricKeys.WALKING_TRIPS,
			MetricKeys.CYCLING_TRIPS,
			MetricKeys.BEST_DAILY_STEPS,
			MetricKeys.LONGEST_TRIP_KM,
			MetricKeys.TOTAL_TRIPS,
			MetricKeys.TRANSPORT_MODE_COUNT,
			MetricKeys.SEASONS_EXPLORED,
			MetricKeys.TOTAL_EXPORTS,
			MetricKeys.ACTIVE_DAYS,
		)
		ChallengeCatalog.definitions.forEach { def ->
			(def.metric in knownMetrics) shouldBe true
		}
	}

	@Test
	fun `consistency definition uses ACTIVE_DAYS metric`() {
		val def = ChallengeCatalog.byType(ChallengeType.Consistency)
		def.metric shouldBe MetricKeys.ACTIVE_DAYS
	}

	@Test
	fun `default values are positive and durations are positive`() {
		ChallengeCatalog.definitions.forEach { def ->
			def.defaultRequiredValue shouldBeGreaterThan 0.0
			def.defaultDurationMs shouldBeGreaterThan 0L
			def.minDurationMultiplier shouldBeGreaterThan 0.0
			def.maxDurationMultiplier shouldBeGreaterThan def.minDurationMultiplier
		}
	}

	@Test
	fun `windowFor returns Interval bound by entity timestamps`() {
		val def = ChallengeCatalog.byType(ChallengeType.Step)
		val win = def.windowFor(1000L, 2000L)
		win shouldBe TimeWindow.Interval(1000L, 2000L)
	}

	@Test
	fun `MEDIUM difficulty returns 1_0 for all definitions (preserves median balance)`() {
		ChallengeCatalog.definitions.forEach { def ->
			def.difficultyTargetMultiplier(ChallengeDifficulty.MEDIUM) shouldBe 1.0
		}
	}

	@Test
	fun `difficulty curves are monotonic non-decreasing across bands`() {
		ChallengeCatalog.definitions.forEach { def ->
			val bands = ChallengeDifficulty.values().sortedBy { it.ordinal }
			val multipliers = bands.map { def.difficultyTargetMultiplier(it) }
			for (i in 1 until multipliers.size) {
				(multipliers[i] >= multipliers[i - 1]) shouldBe true
			}
		}
	}
}
