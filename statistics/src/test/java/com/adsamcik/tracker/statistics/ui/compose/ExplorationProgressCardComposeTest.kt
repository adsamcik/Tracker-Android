package com.adsamcik.tracker.statistics.ui.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.adsamcik.tracker.statistics.ui.ExplorationProgressCard
import com.adsamcik.tracker.statistics.viewmodel.ExplorationStats
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose UI tests for [ExplorationProgressCard].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExplorationProgressCardComposeTest {

	@get:Rule
	val composeTestRule = createComposeRule()

	@Test
	fun `card displays all stat values`() {
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				ExplorationProgressCard(
					stats = ExplorationStats(
						totalCells = 42,
						currentStreak = 5,
						bestStreak = 12,
					),
				)
			}
		}
		composeTestRule.onNodeWithText("42").assertIsDisplayed()
		composeTestRule.onNodeWithText("5d").assertIsDisplayed()
		composeTestRule.onNodeWithText("12d").assertIsDisplayed()
	}

	@Test
	fun `card displays labels`() {
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				ExplorationProgressCard(
					stats = ExplorationStats(totalCells = 0, currentStreak = 0, bestStreak = 0),
				)
			}
		}
		composeTestRule.onNodeWithText("Cells").assertIsDisplayed()
		composeTestRule.onNodeWithText("Streak").assertIsDisplayed()
		composeTestRule.onNodeWithText("Best").assertIsDisplayed()
	}

	@Test
	fun `card shows title`() {
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				ExplorationProgressCard(
					stats = ExplorationStats(totalCells = 100, currentStreak = 3, bestStreak = 7),
				)
			}
		}
		composeTestRule.onNodeWithText("Exploration").assertIsDisplayed()
	}

	@Test
	fun `card with zero values renders correctly`() {
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				ExplorationProgressCard(
					stats = ExplorationStats(totalCells = 0, currentStreak = 0, bestStreak = 0),
				)
			}
		}
		composeTestRule.onNodeWithText("0").assertIsDisplayed()
		// Both streak and best show "0d" - just verify at least one exists
		composeTestRule.onAllNodesWithText("0d").assertCountEquals(2)
	}

	@Test
	fun `card with large values renders without crash`() {
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				ExplorationProgressCard(
					stats = ExplorationStats(
						totalCells = 999_999,
						currentStreak = 365,
						bestStreak = 730,
					),
				)
			}
		}
		composeTestRule.onNodeWithText("999999").assertIsDisplayed()
		composeTestRule.onNodeWithText("365d").assertIsDisplayed()
		composeTestRule.onNodeWithText("730d").assertIsDisplayed()
	}
}
