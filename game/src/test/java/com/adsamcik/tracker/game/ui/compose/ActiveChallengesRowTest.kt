package com.adsamcik.tracker.game.ui.compose

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
class ActiveChallengesRowTest {

	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun withChallenges_showsTitlesAndProgress() {
		val challenges = listOf(
			ChallengeUi(
				id = 1L,
				title = "Walk 5km",
				description = "Walk five kilometers",
				progress = 0.6f,
				difficulty = "EASY",
				timeRemainingMs = 86_400_000L,
			),
		)

		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				ActiveChallengesRow(challenges = challenges)
			}
		}

		composeRule.onNodeWithText("Walk 5km").assertIsDisplayed()
		composeRule.onNodeWithText("Easy").assertIsDisplayed()
		composeRule.onNodeWithText("60%").assertIsDisplayed()
		composeRule.onNodeWithText("1 day remaining").assertIsDisplayed()
	}

	@Test
	fun emptyChallenges_showsEmptySlots() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				ActiveChallengesRow(challenges = emptyList(), maxSlots = 2)
			}
		}

		composeRule.onAllNodesWithText("?").assertCountEquals(2)
		composeRule.onAllNodesWithText("Start tracking!").assertCountEquals(2)
	}

	@Test
	fun challengeWithHours_showsHoursRemaining() {
		val challenges = listOf(
			ChallengeUi(
				id = 2L,
				title = "Sprint Challenge",
				description = "Run fast",
				progress = 0.3f,
				difficulty = "HARD",
				timeRemainingMs = 7_200_000L,
			),
		)

		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				ActiveChallengesRow(challenges = challenges)
			}
		}

		composeRule.onNodeWithText("2 hours remaining").assertIsDisplayed()
	}

	@Test
	fun contentDescription_includesDetails() {
		val challenges = listOf(
			ChallengeUi(
				id = 3L,
				title = "Step Master",
				description = "Get 10k steps",
				progress = 0.5f,
				difficulty = "MEDIUM",
				timeRemainingMs = 172_800_000L,
			),
		)

		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				ActiveChallengesRow(challenges = challenges)
			}
		}

		composeRule.onNodeWithContentDescription(
			"Challenge: Step Master, 50 percent complete, 2 days remaining",
		).assertIsDisplayed()
	}
}
