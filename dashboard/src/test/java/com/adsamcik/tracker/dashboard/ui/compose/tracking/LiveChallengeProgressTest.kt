package com.adsamcik.tracker.dashboard.ui.compose.tracking

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.adsamcik.tracker.dashboard.ui.compose.state.ChallengeUiModel
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveChallengeProgressTest {

	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun emptyChallenges_rendersNothing() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				LiveChallengeProgress(challenges = emptyList())
			}
		}

		composeRule.onAllNodesWithText("Challenge", substring = true).assertCountEquals(0)
	}

	@Test
	fun withChallenges_showsTitlesAndPercentages() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				LiveChallengeProgress(
					challenges = listOf(
						ChallengeUiModel(
							id = 1,
							title = "Walk 5km",
							description = "Walk 5 kilometers",
							progress = 0.65f,
							iconResName = "ic_walk",
							difficulty = "easy",
							timeRemainingMs = 86_400_000L,
							rewardPoints = 50,
						),
					),
				)
			}
		}

		composeRule.onNodeWithText("Walk 5km").assertIsDisplayed()
		composeRule.onNodeWithText("65%").assertIsDisplayed()
	}
}
