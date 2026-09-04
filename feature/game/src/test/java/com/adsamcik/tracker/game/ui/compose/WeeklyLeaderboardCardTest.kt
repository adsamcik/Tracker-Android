package com.adsamcik.tracker.game.ui.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.adsamcik.tracker.game.leaderboard.GhostCompetitor
import com.adsamcik.tracker.game.leaderboard.GhostType
import com.adsamcik.tracker.game.leaderboard.LeaderboardMetric
import com.adsamcik.tracker.game.leaderboard.LeaderboardState
import com.adsamcik.tracker.game.leaderboard.WeeklyLeaderboardCard
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WeeklyLeaderboardCardTest {

	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun showsTitle() {
		val state = LeaderboardState(
			metric = LeaderboardMetric.DISTANCE,
			currentWeekValue = 5000.0,
			competitors = listOf(
				GhostCompetitor(
					id = "current_user",
					type = GhostType.CURRENT_USER,
					nameRes = com.adsamcik.tracker.game.R.string.leaderboard_you,
					value = 5000.0,
				),
			),
			currentRank = 1,
			weekProgressFraction = 0.5f,
		)

		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				WeeklyLeaderboardCard(
					state = state,
					onMetricSelected = {},
				)
			}
		}

		composeRule.onNodeWithText("Weekly Challenge").assertIsDisplayed()
	}

	@Test
	fun showsRank() {
		val competitors = listOf(
			GhostCompetitor(
				id = "best_week",
				type = GhostType.BEST_WEEK,
				nameRes = com.adsamcik.tracker.game.R.string.leaderboard_ghost_best_week,
				value = 10000.0,
			),
			GhostCompetitor(
				id = "current_user",
				type = GhostType.CURRENT_USER,
				nameRes = com.adsamcik.tracker.game.R.string.leaderboard_you,
				value = 5000.0,
			),
		)
		val state = LeaderboardState(
			metric = LeaderboardMetric.DISTANCE,
			currentWeekValue = 5000.0,
			competitors = competitors,
			currentRank = 2,
			weekProgressFraction = 0.3f,
		)

		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				WeeklyLeaderboardCard(
					state = state,
					onMetricSelected = {},
				)
			}
		}

		composeRule.onNodeWithText("#2 of 2").assertIsDisplayed()
	}

	@Test
	fun showsWeekProgressLabels() {
		val state = LeaderboardState(
			metric = LeaderboardMetric.DISTANCE,
			currentWeekValue = 15.0,
			competitors = listOf(
				GhostCompetitor(
					id = "current_user",
					type = GhostType.CURRENT_USER,
					nameRes = com.adsamcik.tracker.game.R.string.leaderboard_you,
					value = 15.0,
				),
			),
			currentRank = 1,
			weekProgressFraction = 0.7f,
		)

		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				WeeklyLeaderboardCard(
					state = state,
					onMetricSelected = {},
				)
			}
		}

		composeRule.onNodeWithText("Mon").assertIsDisplayed()
		composeRule.onNodeWithText("Sun").assertIsDisplayed()
		composeRule.onNodeWithText("Week progress").assertIsDisplayed()
	}

	@Test
	fun showsMetricChips() {
		val state = LeaderboardState(
			metric = LeaderboardMetric.DISTANCE,
			currentWeekValue = 1000.0,
			competitors = listOf(
				GhostCompetitor(
					id = "current_user",
					type = GhostType.CURRENT_USER,
					nameRes = com.adsamcik.tracker.game.R.string.leaderboard_you,
					value = 1000.0,
				),
			),
			currentRank = 1,
			weekProgressFraction = 0.1f,
		)

		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				WeeklyLeaderboardCard(
					state = state,
					onMetricSelected = {},
				)
			}
		}

		composeRule.onNodeWithText("Distance").assertIsDisplayed()
		composeRule.onNodeWithText("Steps").assertDoesNotExist()
		composeRule.onNodeWithText("Active").assertIsDisplayed()
		composeRule.onNodeWithText("Sessions").assertIsDisplayed()
	}
}
