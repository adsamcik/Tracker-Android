package com.adsamcik.tracker.game.ranking

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.adsamcik.tracker.feature.game.api.ranking.WeeklyRank
import com.adsamcik.tracker.feature.game.api.ranking.WeeklyRankTier
import com.adsamcik.tracker.feature.game.api.ranking.WeeklyRankingSnapshot
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WeeklyRankingCardTest {

	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun showsCurrentRankProgressAndFullLadder() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				WeeklyRankingCard(
					ranking = WeeklyRankingSnapshot(
						weekStartEpochDay = 20_000,
						accumulatedPoints = 500.0,
						tiers = listOf(
							WeeklyRankTier(WeeklyRank.BRONZE, 0),
							WeeklyRankTier(WeeklyRank.SILVER, 250),
							WeeklyRankTier(WeeklyRank.GOLD, 750),
							WeeklyRankTier(WeeklyRank.PLATINUM, 1_250),
							WeeklyRankTier(WeeklyRank.DIAMOND, 2_000),
						),
						currentRank = WeeklyRank.SILVER,
						nextTier = WeeklyRankTier(WeeklyRank.GOLD, 750),
						progressToNextRank = 0.5f,
					),
				)
			}
		}

		composeRule.onNodeWithText("Weekly rank").assertIsDisplayed()
		composeRule.onAllNodesWithText("Silver").assertCountEquals(2)
		composeRule.onNodeWithText("250 points to Gold").assertIsDisplayed()
		composeRule.onNodeWithText("Bronze").fetchSemanticsNode()
		composeRule.onNodeWithText("Diamond").fetchSemanticsNode()
	}
}
