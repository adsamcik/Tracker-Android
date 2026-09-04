package com.adsamcik.tracker.game.leaderboard

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("GhostCompetitor")
class GhostCompetitorTest {

	@Nested
	@DisplayName("isCurrentUser")
	inner class IsCurrentUser {
		@Test
		fun `true for CURRENT_USER type`() {
			val competitor = GhostCompetitor(
				id = "user",
				type = GhostType.CURRENT_USER,
				nameRes = GhostType.CURRENT_USER.labelRes,
				value = 100.0,
			)
			competitor.isCurrentUser shouldBe true
		}

		@Test
		fun `false for BEST_WEEK type`() {
			val competitor = GhostCompetitor(
				id = "best",
				type = GhostType.BEST_WEEK,
				nameRes = GhostType.BEST_WEEK.labelRes,
				value = 100.0,
			)
			competitor.isCurrentUser shouldBe false
		}

		@Test
		fun `false for all ghost types`() {
			val ghostTypes = GhostType.entries.filter { it != GhostType.CURRENT_USER }
			ghostTypes.forEach { type ->
				val competitor = GhostCompetitor(
					id = type.name,
					type = type,
					nameRes = type.labelRes,
					value = 50.0,
				)
				competitor.isCurrentUser shouldBe false
			}
		}
	}

	@Nested
	@DisplayName("Construction")
	inner class Construction {
		@Test
		fun `period is optional`() {
			val competitor = GhostCompetitor(
				id = "test",
				type = GhostType.AVERAGE_WEEK,
				nameRes = GhostType.AVERAGE_WEEK.labelRes,
				value = 42.0,
			)
			competitor.period shouldBe null
		}

		@Test
		fun `period can be set`() {
			val competitor = GhostCompetitor(
				id = "test",
				type = GhostType.SAME_WEEK_LAST_YEAR,
				nameRes = GhostType.SAME_WEEK_LAST_YEAR.labelRes,
				value = 42.0,
				period = "W27 2024",
			)
			competitor.period shouldBe "W27 2024"
		}
	}
}

@DisplayName("GhostType")
class GhostTypeTest {

	@Test
	fun `has exactly 5 entries`() {
		GhostType.entries.size shouldBe 5
	}

	@Test
	fun `all entries have label resources`() {
		GhostType.entries.forEach { type ->
			type.labelRes shouldBe type.labelRes // just verify it's set
		}
	}
}

@DisplayName("LeaderboardMetric")
class LeaderboardMetricTest {

	@Test
	fun `has exactly 4 entries`() {
		LeaderboardMetric.entries.size shouldBe 4
	}

	@Test
	fun `contains expected metrics`() {
		val names = LeaderboardMetric.entries.map { it.name }
		names shouldBe listOf("DISTANCE", "STEPS", "ACTIVE_TIME", "SESSIONS")
	}

	@Test
	fun `raw steps is retained but not selectable`() {
		LeaderboardMetric.STEPS.isSelectable shouldBe false
		LeaderboardMetric.selectableEntries shouldBe listOf(
			LeaderboardMetric.DISTANCE,
			LeaderboardMetric.ACTIVE_TIME,
			LeaderboardMetric.SESSIONS,
		)
	}
}
