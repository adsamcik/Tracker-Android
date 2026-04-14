package com.adsamcik.tracker.game.ui.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GameScreenTest {

	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun showsTitle() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				GameScreen()
			}
		}

		composeRule.onNodeWithText("Game").assertIsDisplayed()
	}

	@Test
	fun nullState_showsLoadingCards() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				GameScreen(
					heroLevelState = null,
					pointsToday = null,
				)
			}
		}

		// Should render without crashing - loading state
		composeRule.onNodeWithText("Game").assertIsDisplayed()
	}

	@Test
	fun withPointsToday_showsPoints() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				GameScreen(
					pointsToday = 42,
				)
			}
		}

		composeRule.onNodeWithText("42").assertIsDisplayed()
	}
}
