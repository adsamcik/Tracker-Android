package com.adsamcik.tracker.game.ui.compose

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MiniGamesGridTest {

	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun unlockedGame_showsNameAndDescription() {
		val games = listOf(
			MiniGameUi(
				id = "quiz",
				name = "Geo Quiz",
				description = "Test your knowledge",
				unlockLevel = 3,
				isUnlocked = true,
			),
		)

		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				MiniGamesGrid(games = games, onPlayClick = {})
			}
		}

		composeRule.onNodeWithText("Geo Quiz").assertIsDisplayed()
		composeRule.onNodeWithText("Test your knowledge").assertIsDisplayed()
	}

	@Test
	fun lockedGame_showsUnlockLevel() {
		val games = listOf(
			MiniGameUi(
				id = "runner",
				name = "Speed Runner",
				description = "Race against time",
				unlockLevel = 10,
				isUnlocked = false,
			),
		)

		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				MiniGamesGrid(games = games, onPlayClick = {})
			}
		}

		composeRule.onNodeWithText("Speed Runner").assertIsDisplayed()
		composeRule.onNodeWithText("Unlocks at Level 10").assertIsDisplayed()
	}

	@Test
	fun unlockedGame_cardDescribesPlayableContent() {
		val games = listOf(
			MiniGameUi(
				id = "quiz",
				name = "Geo Quiz",
				description = "Test your knowledge",
				unlockLevel = 3,
				isUnlocked = true,
			),
		)

		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				MiniGamesGrid(games = games, onPlayClick = {})
			}
		}

		composeRule.onNodeWithContentDescription("Geo Quiz: Test your knowledge")
			.assertIsDisplayed()
	}

	@Test
	fun lockedGame_cardHasAccessibleStateAndTouchTarget() {
		val games = listOf(
			MiniGameUi(
				id = "runner",
				name = "Speed Runner",
				description = "Race against time",
				unlockLevel = 10,
				isUnlocked = false,
			),
		)

		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				MiniGamesGrid(games = games, onPlayClick = {})
			}
		}

		composeRule.onNodeWithContentDescription("Speed Runner: Unlocks at Level 10")
			.assertIsDisplayed()
			.assertHasClickAction()
			.assertWidthIsAtLeast(48.dp)
			.assertHeightIsAtLeast(48.dp)
	}

	@Test
	fun mixedGames_showsBothStates() {
		val games = listOf(
			MiniGameUi(
				id = "quiz",
				name = "Geo Quiz",
				description = "Test your knowledge",
				unlockLevel = 3,
				isUnlocked = true,
			),
			MiniGameUi(
				id = "runner",
				name = "Speed Runner",
				description = "Race against time",
				unlockLevel = 10,
				isUnlocked = false,
			),
		)

		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				MiniGamesGrid(games = games, onPlayClick = {})
			}
		}

		composeRule.onNodeWithText("Test your knowledge").assertIsDisplayed()
		composeRule.onNodeWithText("Unlocks at Level 10").assertIsDisplayed()
	}

	@Test
	fun unavailableUnlockedGame_showsComingSoonState() {
		val games = listOf(
			MiniGameUi(
				id = "outrun",
				name = "Outrun",
				description = "Race a ghost",
				unlockLevel = 1,
				isUnlocked = true,
				isAvailable = false,
			),
		)

		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				MiniGamesGrid(games = games, onPlayClick = {})
			}
		}

		composeRule.onNodeWithText("Outrun").assertIsDisplayed()
		composeRule.onNodeWithText("Coming soon").assertIsDisplayed()
	}
}

