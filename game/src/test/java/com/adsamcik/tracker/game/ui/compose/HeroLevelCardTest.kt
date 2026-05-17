package com.adsamcik.tracker.game.ui.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HeroLevelCardTest {

	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun emptyState_showsStartTrackingHint() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				HeroLevelCard(
					level = 0,
					xpIntoCurrentLevel = 0L,
					xpForNextLevel = 0L,
					streakCount = 0,
					streakBest = 0,
					freezeCount = 0,
				)
			}
		}

		composeRule.onNodeWithText("Start tracking to level up!").assertIsDisplayed()
		composeRule.onNodeWithText("Open Dashboard to start").assertIsDisplayed()
	}

	@Test
	fun withLevel_showsLevelAndXp() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				HeroLevelCard(
					level = 5,
					xpIntoCurrentLevel = 300L,
					xpForNextLevel = 1000L,
					streakCount = 3,
					streakBest = 7,
					freezeCount = 0,
				)
			}
		}

		composeRule.onNodeWithText("5").assertIsDisplayed()
		composeRule.onNodeWithText("Level 5").assertIsDisplayed()
		composeRule.onNodeWithText("300 / 1000 XP").assertIsDisplayed()
	}

	@Test
	fun withStreak_showsStreakInfo() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				HeroLevelCard(
					level = 3,
					xpIntoCurrentLevel = 100L,
					xpForNextLevel = 500L,
					streakCount = 7,
					streakBest = 15,
					freezeCount = 2,
				)
			}
		}

		composeRule.onNodeWithText("Streak", substring = true).assertIsDisplayed()
		composeRule.onNodeWithText("Best: 15").assertIsDisplayed()
		composeRule.onNodeWithText("❄\uFE0F 2").assertIsDisplayed()
	}

	@Test
	fun zeroFreezeCount_hidesFreeze() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				HeroLevelCard(
					level = 2,
					xpIntoCurrentLevel = 50L,
					xpForNextLevel = 200L,
					streakCount = 1,
					streakBest = 3,
					freezeCount = 0,
				)
			}
		}

		composeRule.onAllNodesWithText("❄\uFE0F", substring = true)
			.fetchSemanticsNodes()
			.let { nodes ->
				assert(nodes.isEmpty()) { "Freeze icon should not be visible when freezeCount is 0" }
			}
	}

	@Test
	fun xpForNextLevelZero_showsEmptyState() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				HeroLevelCard(
					level = 5,
					xpIntoCurrentLevel = 100L,
					xpForNextLevel = 0L,
					streakCount = 0,
					streakBest = 0,
					freezeCount = 0,
				)
			}
		}

		composeRule.onNodeWithText("Start tracking to level up!").assertIsDisplayed()
	}
}
