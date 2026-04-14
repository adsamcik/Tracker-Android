package com.adsamcik.tracker.dashboard.ui.compose.cards

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.adsamcik.tracker.dashboard.ui.compose.state.ExplorationUiState
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
	fun withNoExplorationData_rendersNothing() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				ExplorationCard(
					explorationState = ExplorationUiState(hasExplorationData = false),
				)
			}
		}

		composeRule.onAllNodesWithText("Exploration").assertCountEquals(0)
	}

	@Test
	fun withExplorationData_showsTitleAndCells() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				ExplorationCard(
					explorationState = ExplorationUiState(
						newCellsToday = 12,
						totalCells = 345,
						seasonsCovered = 3,
						hasExplorationData = true,
					),
				)
			}
		}

		composeRule.onNodeWithText("Exploration").assertIsDisplayed()
		composeRule.onNodeWithText("12").assertIsDisplayed()
		composeRule.onNodeWithText("New today", substring = true).assertIsDisplayed()
		composeRule.onNodeWithText("345").assertIsDisplayed()
		composeRule.onNodeWithText("Total cells", substring = true).assertIsDisplayed()
	}

	@Test
	fun withSeasons_showsSeasonsSection() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				ExplorationCard(
					explorationState = ExplorationUiState(
						newCellsToday = 5,
						totalCells = 100,
						seasonsCovered = 2,
						hasExplorationData = true,
					),
				)
			}
		}

		composeRule.onNodeWithText("Seasons").assertIsDisplayed()
	}

	@Test
	fun withZeroSeasons_hidesSeasonsSection() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				ExplorationCard(
					explorationState = ExplorationUiState(
						newCellsToday = 5,
						totalCells = 100,
						seasonsCovered = 0,
						hasExplorationData = true,
					),
				)
			}
		}

		composeRule.onAllNodesWithText("Seasons").assertCountEquals(0)
	}
}
