package com.adsamcik.tracker.game.ui.compose

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.adsamcik.tracker.game.viewmodel.ExplorationViewModel.AchievementSummaryState
import com.adsamcik.tracker.game.viewmodel.ExplorationViewModel.NextAchievement
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AchievementCardTest {

	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun emptyState_showsNoAchievementsMessage() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				AchievementCard(
					state = AchievementSummaryState(
						totalUnlocked = 0,
						nextClosest = null,
					),
				)
			}
		}

		composeRule.onNodeWithText("No achievements unlocked yet. Start exploring!")
			.assertIsDisplayed()
	}

	@Test
	fun withAchievements_showsTierCounts() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				AchievementCard(
					state = AchievementSummaryState(
						bronzeCount = 3,
						silverCount = 2,
						goldCount = 1,
						diamondCount = 0,
						totalUnlocked = 6,
					),
				)
			}
		}

		composeRule.onNodeWithText("Achievements").assertIsDisplayed()
		composeRule.onNodeWithText("Bronze").assertIsDisplayed()
		composeRule.onNodeWithText("Silver").assertIsDisplayed()
		composeRule.onNodeWithText("Gold").assertIsDisplayed()
		composeRule.onNodeWithText("Diamond").assertIsDisplayed()
		composeRule.onNodeWithText("3").assertIsDisplayed()
	}

	@Test
	fun withNextClosest_showsProgressPercentage() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				AchievementCard(
					state = AchievementSummaryState(
						totalUnlocked = 2,
						bronzeCount = 2,
						nextClosest = NextAchievement(
							id = "explore_100",
							progress = 0.75f,
						),
					),
				)
			}
		}

		composeRule.onNodeWithText("75%").assertIsDisplayed()
		composeRule.onNodeWithText("Unlocked").assertIsDisplayed()
	}

	@Test
	fun noNextClosest_hidesProgressSection() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				AchievementCard(
					state = AchievementSummaryState(
						totalUnlocked = 1,
						bronzeCount = 1,
						nextClosest = null,
					),
				)
			}
		}

		composeRule.onAllNodesWithText("Unlocked").assertCountEquals(0)
	}
}
