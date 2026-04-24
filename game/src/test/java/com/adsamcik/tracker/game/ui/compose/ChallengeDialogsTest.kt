package com.adsamcik.tracker.game.ui.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChallengeDialogsTest {

	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun detailsDialog_showsChallengeInfo() {
		val challenge = ChallengeUi(
			id = 1L,
			title = "Walk 5km",
			description = "Walk five kilometers today",
			progress = 0.65f,
			difficulty = "EASY",
			timeRemainingMs = 3_600_000L,
		)

		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				ChallengeDetailsDialog(
					challenge = challenge,
					onDismiss = {},
					onViewTrophyCase = {},
				)
			}
		}

		composeRule.onNodeWithText("Walk 5km").assertIsDisplayed()
		composeRule.onNodeWithText("Walk five kilometers today").assertIsDisplayed()
		composeRule.onNodeWithText("65% complete").assertIsDisplayed()
		composeRule.onNodeWithText("Easy").assertIsDisplayed()
		composeRule.onNodeWithText("1h 0m remaining").assertIsDisplayed()
	}

	@Test
	fun detailsDialog_showsButtons() {
		val challenge = ChallengeUi(
			id = 2L,
			title = "Sprint",
			description = "Run fast",
			progress = 0.5f,
		)

		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				ChallengeDetailsDialog(
					challenge = challenge,
					onDismiss = {},
					onViewTrophyCase = {},
				)
			}
		}

		composeRule.onNodeWithText("View Trophy Case").assertIsDisplayed()
		composeRule.onNodeWithText("Close").assertIsDisplayed()
	}

	@Test
	fun pickerDialog_withChallenges_showsList() {
		val challenges = listOf(
			ChallengeUi(
				id = 1L,
				title = "Walk 5km",
				description = "Walk five km",
				progress = 0.0f,
			),
			ChallengeUi(
				id = 2L,
				title = "Run 10km",
				description = "Run ten km",
				progress = 0.0f,
			),
		)

		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				ChallengePickerDialog(
					challenges = challenges,
					onDismiss = {},
					onChallengeSelected = {},
					onOpenTrophyCase = {},
				)
			}
		}

		composeRule.onNodeWithText("Start a challenge").assertIsDisplayed()
		composeRule.onNodeWithText("Walk 5km").assertIsDisplayed()
		composeRule.onNodeWithText("Run 10km").assertIsDisplayed()
	}

	@Test
	fun pickerDialog_empty_showsEmptyMessage() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				ChallengePickerDialog(
					challenges = emptyList(),
					onDismiss = {},
					onChallengeSelected = {},
					onOpenTrophyCase = {},
				)
			}
		}

		composeRule.onNodeWithText("No challenge is ready yet", substring = true)
			.assertIsDisplayed()
	}

	@Test
	fun pickerDialog_empty_showsTrophyCaseButton() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				ChallengePickerDialog(
					challenges = emptyList(),
					onDismiss = {},
					onChallengeSelected = {},
					onOpenTrophyCase = {},
				)
			}
		}

		composeRule.onNodeWithText("View Trophy Case").assertIsDisplayed()
	}

	@Test
	fun detailsDialog_dismiss_callsCallback() {
		var dismissed = false
		val challenge = ChallengeUi(
			id = 1L,
			title = "Test",
			description = "Test desc",
			progress = 0.0f,
		)

		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				ChallengeDetailsDialog(
					challenge = challenge,
					onDismiss = { dismissed = true },
					onViewTrophyCase = {},
				)
			}
		}

		composeRule.onNodeWithText("Close").performClick()
		assertTrue(dismissed)
	}

	@Test
	fun detailsDialog_viewTrophyCase_callsCallback() {
		var trophyCaseClicked = false
		val challenge = ChallengeUi(
			id = 1L,
			title = "Test",
			description = "Test desc",
			progress = 0.0f,
		)

		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				ChallengeDetailsDialog(
					challenge = challenge,
					onDismiss = {},
					onViewTrophyCase = { trophyCaseClicked = true },
				)
			}
		}

		composeRule.onNodeWithText("View Trophy Case").performClick()
		assertTrue(trophyCaseClicked)
	}

	@Test
	fun pickerDialog_selectingChallenge_callsCallback() {
		var selectedId: Long? = null
		val challenges = listOf(
			ChallengeUi(
				id = 42L,
				title = "Walk 5km",
				description = "Walk five km",
				progress = 0.0f,
			),
		)

		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				ChallengePickerDialog(
					challenges = challenges,
					onDismiss = {},
					onChallengeSelected = { selectedId = it.id },
					onOpenTrophyCase = {},
				)
			}
		}

		composeRule.onNodeWithText("Walk 5km").performClick()
		assertTrue(selectedId == 42L)
	}

	@Test
	fun detailsDialog_withDaysRemaining_showsFormattedTime() {
		val challenge = ChallengeUi(
			id = 1L,
			title = "Long challenge",
			description = "Takes days",
			progress = 0.1f,
			timeRemainingMs = 90_000_000L, // 1d 1h
		)

		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				ChallengeDetailsDialog(
					challenge = challenge,
					onDismiss = {},
					onViewTrophyCase = {},
				)
			}
		}

		composeRule.onNodeWithText("1d 1h remaining").assertIsDisplayed()
	}
}
