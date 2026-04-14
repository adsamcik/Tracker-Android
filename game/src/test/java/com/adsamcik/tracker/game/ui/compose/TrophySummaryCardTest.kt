package com.adsamcik.tracker.game.ui.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrophySummaryCardTest {

	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun emptyState_showsNoTrophiesMessage() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				TrophySummaryCard(
					totalCompleted = 0,
					goldCount = 0,
					silverCount = 0,
					bronzeCount = 0,
				)
			}
		}

		composeRule.onNodeWithText("Complete challenges to earn trophies!")
			.assertIsDisplayed()
	}

	@Test
	fun withTrophies_showsSummaryAndMedals() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				TrophySummaryCard(
					totalCompleted = 10,
					goldCount = 3,
					silverCount = 4,
					bronzeCount = 3,
				)
			}
		}

		composeRule.onNodeWithText("Trophies").assertIsDisplayed()
		composeRule.onNodeWithText("10 completed").assertIsDisplayed()
		composeRule.onNodeWithText("View Trophy Case").assertIsDisplayed()
	}

	@Test
	fun contentDescription_includesSummary() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				TrophySummaryCard(
					totalCompleted = 5,
					goldCount = 2,
					silverCount = 2,
					bronzeCount = 1,
				)
			}
		}

		composeRule.onNodeWithContentDescription(
			"5 completed, 2 gold, 2 silver, 1 bronze",
			substring = true,
		).assertIsDisplayed()
	}
}
