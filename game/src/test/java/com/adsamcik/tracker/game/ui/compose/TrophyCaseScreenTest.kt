package com.adsamcik.tracker.game.ui.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.adsamcik.tracker.game.repository.LifetimeStatsUi
import com.adsamcik.tracker.game.repository.TrophyItemUi
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrophyCaseScreenTest {

	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun showsTitle() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				TrophyCaseScreen(
					activeChallenges = emptyList(),
					trophies = emptyList(),
					personalRecords = emptyList(),
					lifetimeStats = null,
					currentFilter = TrophyFilter.ALL,
					onFilterChanged = {},
					onChallengeClick = {},
					onBack = {},
				)
			}
		}

		composeRule.onNodeWithText("Trophy Case").assertIsDisplayed()
	}

	@Test
	fun showsFilterChips() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				TrophyCaseScreen(
					activeChallenges = emptyList(),
					trophies = emptyList(),
					personalRecords = emptyList(),
					lifetimeStats = null,
					currentFilter = TrophyFilter.ALL,
					onFilterChanged = {},
					onChallengeClick = {},
					onBack = {},
				)
			}
		}

		composeRule.onNodeWithText("All").assertIsDisplayed()
		composeRule.onNodeWithText("Gold").assertIsDisplayed()
		composeRule.onNodeWithText("Silver").assertIsDisplayed()
		composeRule.onNodeWithText("Bronze").assertIsDisplayed()
	}

	@Test
	fun emptyTrophies_showsEmptyState() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				TrophyCaseScreen(
					activeChallenges = emptyList(),
					trophies = emptyList(),
					personalRecords = emptyList(),
					lifetimeStats = null,
					currentFilter = TrophyFilter.ALL,
					onFilterChanged = {},
					onChallengeClick = {},
					onBack = {},
				)
			}
		}

		composeRule.onNodeWithText("No trophies yet").assertIsDisplayed()
	}

	@Test
	fun withTrophies_showsTrophyItems() {
		val trophies = listOf(
			TrophyItemUi(
				id = 1L,
				challengeType = "WALK_DISTANCE",
				difficulty = "EASY",
				medal = "GOLD",
				completedAt = 1_700_000_000_000L,
				xpAwarded = 100,
				progressValue = 5000.0,
				targetValue = 5000.0,
			),
		)

		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				TrophyCaseScreen(
					activeChallenges = emptyList(),
					trophies = trophies,
					personalRecords = emptyList(),
					lifetimeStats = null,
					currentFilter = TrophyFilter.ALL,
					onFilterChanged = {},
					onChallengeClick = {},
					onBack = {},
				)
			}
		}

		composeRule.onNodeWithText("Walk distance", substring = true, ignoreCase = true).assertIsDisplayed()
	}

	@Test
	fun withLifetimeStats_showsStats() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				TrophyCaseScreen(
					activeChallenges = emptyList(),
					trophies = emptyList(),
					personalRecords = emptyList(),
					lifetimeStats = LifetimeStatsUi(
						totalChallenges = 10,
						completedCount = 8,
						completionRate = 0.8f,
						goldCount = 3,
						silverCount = 3,
						bronzeCount = 2,
						totalXpEarned = 4000L,
					),
					currentFilter = TrophyFilter.ALL,
					onFilterChanged = {},
					onChallengeClick = {},
					onBack = {},
				)
			}
		}

		composeRule.onNodeWithText("Lifetime Stats").assertIsDisplayed()
	}
}
