package com.adsamcik.tracker.game.ui.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.adsamcik.tracker.game.viewmodel.ExplorationViewModel.ExplorationState
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExplorationCardTest {

	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun showsTitle() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				ExplorationCard(
					state = ExplorationState(totalCells = 100),
				)
			}
		}

		composeRule.onNodeWithText("Exploration").assertIsDisplayed()
	}

	@Test
	fun showsCellCount() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				ExplorationCard(
					state = ExplorationState(totalCells = 256),
				)
			}
		}

		composeRule.onNodeWithText("256").assertIsDisplayed()
		composeRule.onNodeWithText("Cells Discovered").assertIsDisplayed()
	}

	@Test
	fun showsStreakInfo() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				ExplorationCard(
					state = ExplorationState(
						totalCells = 50,
						dailyStreak = 3,
						bestStreak = 10,
					),
				)
			}
		}

		composeRule.onNodeWithText("3").assertIsDisplayed()
		composeRule.onNodeWithText("10").assertIsDisplayed()
	}

	@Test
	fun showsSeasonsCount() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				ExplorationCard(
					state = ExplorationState(
						totalCells = 50,
						seasonsBitmask = 0b0101,
					),
				)
			}
		}

		composeRule.onNodeWithText("Seasons Explored").assertIsDisplayed()
		composeRule.onNodeWithText("2 of 4").assertIsDisplayed()
	}

	@Test
	fun allSeasons_showsFourOfFour() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				ExplorationCard(
					state = ExplorationState(
						totalCells = 200,
						seasonsBitmask = 0b1111,
					),
				)
			}
		}

		composeRule.onNodeWithText("4 of 4").assertIsDisplayed()
	}
}
