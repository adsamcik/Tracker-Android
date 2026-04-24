package com.adsamcik.tracker.game.ui.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import com.adsamcik.tracker.game.repository.PlayerProfileUi
import com.adsamcik.tracker.game.repository.StreakUi
import com.adsamcik.tracker.game.repository.TrophySummaryUi
import com.adsamcik.tracker.game.viewmodel.ExplorationViewModel.AchievementSummaryState
import com.adsamcik.tracker.game.viewmodel.ExplorationViewModel.ExplorationState
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GameScreenTest {

	@get:Rule
	val composeRule = createComposeRule()

	private fun scrollTo(text: String) {
		val isVerticalScrollable = hasScrollToNodeAction() and
			SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
		composeRule.onNode(isVerticalScrollable)
			.performScrollToNode(hasText(text))
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

		// Loading cards are shown when data is null
		composeRule.onAllNodesWithText("Loading…")[0].assertIsDisplayed()
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

		scrollTo("42")
		composeRule.onNodeWithText("42").assertIsDisplayed()
		composeRule.onNodeWithText("points earned today").assertIsDisplayed()
	}

	@Test
	fun withSteps_showsStepCounts() {
		// Enable step counter feature for this test
		val pm = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
			.packageManager
		val shadowPm = org.robolectric.Shadows.shadowOf(pm)
		shadowPm.setSystemFeature(android.content.pm.PackageManager.FEATURE_SENSOR_STEP_COUNTER, true)

		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				GameScreen(
					steps = StepsSummaryUi(
						stepsToday = 5000,
						stepsWeek = 30000,
						goalDay = 10000,
						goalWeek = 70000,
					),
				)
			}
		}

		composeRule.waitForIdle()
		scrollTo("Steps goals")
		composeRule.onNodeWithText("Steps goals").assertIsDisplayed()
		composeRule.onNodeWithText("5000 / 10000").assertIsDisplayed()
		composeRule.onNodeWithText("30000 / 70000").assertIsDisplayed()
	}

	@Test
	fun withExplorationState_showsExplorationCard() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				GameScreen(
					explorationState = ExplorationState(
						totalCells = 150,
						dailyStreak = 3,
						bestStreak = 7,
					),
				)
			}
		}

		composeRule.waitForIdle()
		scrollTo("Exploration")
		composeRule.onNodeWithText("Exploration").assertIsDisplayed()
		composeRule.onNodeWithText("150").assertIsDisplayed()
	}

	@Test
	fun withAchievementState_showsAchievementCard() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				GameScreen(
					achievementState = AchievementSummaryState(
						bronzeCount = 3,
						silverCount = 2,
						goldCount = 1,
						diamondCount = 0,
						totalUnlocked = 6,
					),
				)
			}
		}

		composeRule.waitForIdle()
		scrollTo("Achievements")
		composeRule.onNodeWithText("Achievements").assertIsDisplayed()
		// Verify tier badge counts are displayed
		composeRule.onNodeWithText("3").assertIsDisplayed()
		composeRule.onNodeWithText("Bronze").assertIsDisplayed()
	}

	@Test
	fun withHeroLevelState_showsHeroCard() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				GameScreen(
					heroLevelState = HeroLevelUiState(
						playerProfile = PlayerProfileUi(
							level = 5,
							totalXp = 2500L,
							xpIntoCurrentLevel = 300L,
							xpForNextLevel = 1000L,
						),
						streak = StreakUi(
							currentCount = 3,
							bestCount = 10,
							freezeCount = 1,
						),
					),
				)
			}
		}

		composeRule.waitForIdle()
		composeRule.onNodeWithText("Level 5").assertIsDisplayed()
	}

	@Test
	fun withTrophySummary_showsTrophyCard() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				GameScreen(
					trophySummary = TrophySummaryUi(
						totalCompleted = 10,
						goldCount = 3,
						silverCount = 4,
						bronzeCount = 3,
					),
				)
			}
		}

		composeRule.waitForIdle()
		scrollTo("View Trophy Case")
		composeRule.onNodeWithText("10 completed").assertIsDisplayed()
		composeRule.onNodeWithText("View Trophy Case").assertIsDisplayed()
	}

	@Test
	fun withChallenges_showsChallengeContent() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				GameScreen(
					challenges = listOf(
						ChallengeUi(
							id = 1L,
							title = "Walk 10km",
							description = "Walk a total of 10 kilometers",
							progress = 0.5f,
							difficulty = "Medium",
							timeRemainingMs = 86400000L,
						),
					),
				)
			}
		}

		scrollTo("Active Challenges")
		composeRule.onNodeWithText("Active Challenges").assertIsDisplayed()
		scrollTo("Walk 10km")
		composeRule.onNodeWithText("Walk 10km").assertIsDisplayed()
		composeRule.onNodeWithText("50%").assertIsDisplayed()
	}

	@Test
	fun loadingChallenges_showsLoadingIndicator() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				GameScreen(
					isLoadingChallenges = true,
					challenges = null,
				)
			}
		}

		scrollTo("Active Challenges")
		composeRule.onNodeWithText("Active Challenges").assertIsDisplayed()
	}

	@Test
	fun settingsButton_callsOnOpenSettings() {
		var settingsClicked = false
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				GameScreen(
					onOpenSettings = { settingsClicked = true },
				)
			}
		}

		composeRule.onNodeWithContentDescription("Open settings").performClick()
		assertTrue(settingsClicked)
	}

	@Test
	fun miniGamesSection_showsSectionHeader() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				GameScreen()
			}
		}

		scrollTo("Mini-Games")
		composeRule.onNodeWithText("Mini-Games").assertIsDisplayed()
	}

	@Test
	fun allSectionsPopulated_showsKeyContent() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				GameScreen(
					pointsToday = 100,
					steps = StepsSummaryUi(
						stepsToday = 8000,
						stepsWeek = 50000,
						goalDay = 10000,
						goalWeek = 70000,
					),
					challenges = listOf(
						ChallengeUi(
							id = 1L,
							title = "Test Challenge",
							description = "Description",
							progress = 0.75f,
						),
					),
					explorationState = ExplorationState(totalCells = 42),
					achievementState = AchievementSummaryState(totalUnlocked = 5),
					heroLevelState = HeroLevelUiState(
						playerProfile = PlayerProfileUi(
							level = 3,
							totalXp = 1000L,
							xpIntoCurrentLevel = 200L,
							xpForNextLevel = 500L,
						),
						streak = StreakUi(currentCount = 2, bestCount = 5, freezeCount = 0),
					),
					trophySummary = TrophySummaryUi(
						totalCompleted = 5,
						goldCount = 1,
						silverCount = 2,
						bronzeCount = 2,
					),
				)
			}
		}

		composeRule.waitForIdle()
		// Top items visible on screen — the hero level card sits at the top of the content now
		// that we dropped the redundant "Game" app-bar title.
		composeRule.onNodeWithText("Level 3").assertIsDisplayed()

		// Scroll to verify points card
		scrollTo("points earned today")
		composeRule.onNodeWithText("100").assertIsDisplayed()
		composeRule.onNodeWithText("points earned today").assertIsDisplayed()

		// Scroll to verify challenge
		scrollTo("Test Challenge")
		composeRule.onNodeWithText("Test Challenge").assertIsDisplayed()
		composeRule.onNodeWithText("75%").assertIsDisplayed()

		// Scroll to verify exploration
		scrollTo("Exploration")
		composeRule.onNodeWithText("Exploration").assertIsDisplayed()

		// Scroll to verify achievements
		scrollTo("Achievements")
		composeRule.onNodeWithText("Achievements").assertIsDisplayed()
	}
}
