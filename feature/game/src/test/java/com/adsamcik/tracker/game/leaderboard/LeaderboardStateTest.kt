package com.adsamcik.tracker.game.leaderboard

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("LeaderboardState")
class LeaderboardStateTest {

	@Nested
	@DisplayName("ghosts")
	inner class Ghosts {
		@Test
		fun `filters out current user`() {
			val state = createState(
				competitors = listOf(
					createCompetitor("best_week", GhostType.BEST_WEEK, 100.0),
					createCompetitor("current_user", GhostType.CURRENT_USER, 80.0),
					createCompetitor("avg", GhostType.AVERAGE_WEEK, 50.0),
				),
			)
			state.ghosts shouldHaveSize 2
			state.ghosts.none { it.isCurrentUser } shouldBe true
		}

		@Test
		fun `empty when only current user`() {
			val state = createState(
				competitors = listOf(
					createCompetitor("current_user", GhostType.CURRENT_USER, 80.0),
				),
			)
			state.ghosts shouldHaveSize 0
		}
	}

	@Nested
	@DisplayName("topGhostValue")
	inner class TopGhostValue {
		@Test
		fun `returns first ghost value`() {
			val state = createState(
				competitors = listOf(
					createCompetitor("best_week", GhostType.BEST_WEEK, 200.0),
					createCompetitor("current_user", GhostType.CURRENT_USER, 80.0),
					createCompetitor("avg", GhostType.AVERAGE_WEEK, 50.0),
				),
			)
			state.topGhostValue shouldBe 200.0
		}

		@Test
		fun `returns 0 when no ghosts`() {
			val state = createState(
				competitors = listOf(
					createCompetitor("current_user", GhostType.CURRENT_USER, 80.0),
				),
			)
			state.topGhostValue shouldBe 0.0
		}
	}

	@Nested
	@DisplayName("progressAgainstTop")
	inner class ProgressAgainstTop {
		@Test
		fun `returns fraction of current week value vs top ghost`() {
			val state = createState(
				currentWeekValue = 50.0,
				competitors = listOf(
					createCompetitor("best_week", GhostType.BEST_WEEK, 100.0),
					createCompetitor("current_user", GhostType.CURRENT_USER, 50.0),
				),
			)
			state.progressAgainstTop shouldBe 0.5f
		}

		@Test
		fun `capped at 1 when exceeding top ghost`() {
			val state = createState(
				currentWeekValue = 200.0,
				competitors = listOf(
					createCompetitor("best_week", GhostType.BEST_WEEK, 100.0),
					createCompetitor("current_user", GhostType.CURRENT_USER, 200.0),
				),
			)
			state.progressAgainstTop shouldBe 1.0f
		}

		@Test
		fun `returns 0 when no ghosts`() {
			val state = createState(
				currentWeekValue = 100.0,
				competitors = listOf(
					createCompetitor("current_user", GhostType.CURRENT_USER, 100.0),
				),
			)
			state.progressAgainstTop shouldBe 0f
		}
	}

	private fun createState(
		currentWeekValue: Double = 0.0,
		competitors: List<GhostCompetitor> = emptyList(),
	): LeaderboardState {
		return LeaderboardState(
			metric = LeaderboardMetric.DISTANCE,
			currentWeekValue = currentWeekValue,
			competitors = competitors,
			currentRank = 1,
			weekProgressFraction = 0.5f,
		)
	}

	private fun createCompetitor(
		id: String,
		type: GhostType,
		value: Double,
	): GhostCompetitor {
		return GhostCompetitor(
			id = id,
			type = type,
			nameRes = type.labelRes,
			value = value,
		)
	}
}
