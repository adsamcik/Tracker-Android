package com.adsamcik.tracker.game.ui.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.adsamcik.tracker.game.repository.LifetimeStatsUi
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LifetimeStatsSectionTest {

	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun showsTitle() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				LifetimeStatsSection(
					stats = LifetimeStatsUi(
						totalChallenges = 20,
						completedCount = 15,
						completionRate = 0.75f,
						goldCount = 5,
						silverCount = 6,
						bronzeCount = 4,
						totalXpEarned = 5000L,
					),
				)
			}
		}

		composeRule.onNodeWithText("Lifetime Stats").assertIsDisplayed()
	}

	@Test
	fun showsStatValues() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				LifetimeStatsSection(
					stats = LifetimeStatsUi(
						totalChallenges = 20,
						completedCount = 15,
						completionRate = 0.75f,
						goldCount = 5,
						silverCount = 6,
						bronzeCount = 4,
						totalXpEarned = 5000L,
					),
				)
			}
		}

		composeRule.onNodeWithText("20").assertIsDisplayed()
		composeRule.onNodeWithText("75%").assertIsDisplayed()
		composeRule.onNodeWithText("5000").assertIsDisplayed()
	}

	@Test
	fun showsMedalBreakdown() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				LifetimeStatsSection(
					stats = LifetimeStatsUi(
						totalChallenges = 10,
						completedCount = 6,
						completionRate = 0.6f,
						goldCount = 2,
						silverCount = 2,
						bronzeCount = 2,
						totalXpEarned = 3000L,
					),
				)
			}
		}

		composeRule.onNodeWithText("Medal Breakdown").assertIsDisplayed()
		composeRule.onNodeWithText("2 🥇").assertIsDisplayed()
		composeRule.onNodeWithText("2 🥈").assertIsDisplayed()
		composeRule.onNodeWithText("2 🥉").assertIsDisplayed()
	}

	@Test
	fun showsLabels() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				LifetimeStatsSection(
					stats = LifetimeStatsUi(
						totalChallenges = 5,
						completedCount = 3,
						completionRate = 0.6f,
						goldCount = 1,
						silverCount = 1,
						bronzeCount = 1,
						totalXpEarned = 1500L,
					),
				)
			}
		}

		composeRule.onNodeWithText("Total Challenges").assertIsDisplayed()
		composeRule.onNodeWithText("Completion Rate").assertIsDisplayed()
		composeRule.onNodeWithText("Total XP Earned").assertIsDisplayed()
	}
}
