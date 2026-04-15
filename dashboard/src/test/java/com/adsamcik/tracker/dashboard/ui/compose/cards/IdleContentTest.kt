package com.adsamcik.tracker.dashboard.ui.compose.cards

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.adsamcik.tracker.dashboard.data.DashboardWidget
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardUiState
import com.adsamcik.tracker.dashboard.ui.compose.state.ExplorationUiState
import com.adsamcik.tracker.dashboard.ui.compose.state.StreakState
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IdleContentTest {

	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun withEmptyWidgets_rendersMotivationalText() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				IdleContent(
					state = DashboardUiState(),
					widgets = emptyList(),
					onMapClick = {},
					onGameClick = null,
					onSessionDetailClick = null,
					onToggleTracking = {},
					onRequestPermission = {},
				)
			}
		}

		// MotivationalText shows a time-of-day greeting — one of these must be present
		val greetings = listOf(
			"Good morning",
			"Good afternoon",
			"Good evening",
			"Night owl",
		)
		val found = greetings.any { greeting ->
			composeRule.onAllNodes(
				androidx.compose.ui.test.hasText(greeting, substring = true)
			).fetchSemanticsNodes().isNotEmpty()
		}
		assert(found) { "MotivationalText should display a time-of-day greeting" }
	}

	@Test
	fun withExplorationWidget_showsExploration() {
		val stateWithExploration = DashboardUiState(
			explorationState = ExplorationUiState(
				newCellsToday = 5,
				totalCells = 128,
				seasonsCovered = 2,
				hasExplorationData = true,
			),
		)
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				IdleContent(
					state = stateWithExploration,
					widgets = listOf(DashboardWidget.Exploration),
					onMapClick = {},
					onGameClick = null,
					onSessionDetailClick = null,
					onToggleTracking = {},
					onRequestPermission = {},
				)
			}
		}

		// ExplorationCard renders title and data labels
		composeRule.onNodeWithText("Exploration").assertIsDisplayed()
		composeRule.onNodeWithText("New today").assertIsDisplayed()
		composeRule.onNodeWithText("Total cells").assertIsDisplayed()
	}

	@Test
	fun withStreakWidget_showsStreak() {
		val stateWithStreak = DashboardUiState(
			streakState = StreakState(
				currentStreak = 3,
				bestStreak = 7,
			),
		)
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				IdleContent(
					state = stateWithStreak,
					widgets = listOf(DashboardWidget.Streak),
					onMapClick = {},
					onGameClick = null,
					onSessionDetailClick = null,
					onToggleTracking = {},
					onRequestPermission = {},
				)
			}
		}

		// StreakBanner has a content description with streak count
		composeRule.onNodeWithContentDescription("Streak", substring = true).assertIsDisplayed()
	}

	@Test
	fun withMultipleWidgets_showsAll() {
		val state = DashboardUiState(
			streakState = StreakState(currentStreak = 5, bestStreak = 10),
			explorationState = ExplorationUiState(
				newCellsToday = 12,
				totalCells = 200,
				seasonsCovered = 1,
				hasExplorationData = true,
			),
		)
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				IdleContent(
					state = state,
					widgets = listOf(
						DashboardWidget.TodayProgress,
						DashboardWidget.Streak,
						DashboardWidget.Challenges,
						DashboardWidget.Exploration,
					),
					onMapClick = {},
					onGameClick = null,
					onSessionDetailClick = null,
					onToggleTracking = {},
					onRequestPermission = {},
				)
			}
		}

		// Streak widget has content description with streak info
		composeRule.onNodeWithContentDescription("Streak", substring = true).assertExists()

		// Challenges widget renders title even with no challenges
		composeRule.onNodeWithText("Challenges", substring = true).assertExists()
	}
}
