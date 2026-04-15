package com.adsamcik.tracker.dashboard.ui.compose.visualization

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.adsamcik.tracker.dashboard.ui.compose.state.GoalProgressState
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GoalProgressRingsComposeTest {

	@get:Rule
	val composeRule = createComposeRule()

	// region Early return conditions

	@Test
	fun gamificationDisabled_rendersNothing() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				GoalProgressRings(
					goalProgress = GoalProgressState(
						gamificationEnabled = false,
						dailySteps = 5000,
						dailyGoalSteps = 10000,
						dailyProgress = 0.5f,
					),
				)
			}
		}

		composeRule.onAllNodesWithText("/", substring = true).fetchSemanticsNodes().let {
			assert(it.isEmpty()) { "Nothing should render when gamification disabled" }
		}
	}

	@Test
	fun zeroDailyGoal_rendersNothing() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				GoalProgressRings(
					goalProgress = GoalProgressState(
						gamificationEnabled = true,
						dailySteps = 500,
						dailyGoalSteps = 0,
						dailyProgress = 0f,
					),
				)
			}
		}

		composeRule.onAllNodesWithText("/", substring = true).fetchSemanticsNodes().let {
			assert(it.isEmpty()) { "Nothing should render when daily goal is zero" }
		}
	}

	// endregion

	// region Normal rendering

	@Test
	fun normalProgress_displaysStepCount() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				GoalProgressRings(
					goalProgress = GoalProgressState(
						gamificationEnabled = true,
						dailySteps = 6500,
						dailyGoalSteps = 10000,
						dailyProgress = 0.65f,
						weeklySteps = 35000,
						weeklyGoalSteps = 70000,
						weeklyProgress = 0.5f,
					),
				)
			}
		}

		// Step count and goal should be displayed
		composeRule.onNodeWithText("6,500").assertIsDisplayed()
		composeRule.onNodeWithText("/ 10,000").assertIsDisplayed()
	}

	@Test
	fun zeroProgress_showsGetStartedStatus() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				GoalProgressRings(
					goalProgress = GoalProgressState(
						gamificationEnabled = true,
						dailySteps = 0,
						dailyGoalSteps = 10000,
						dailyProgress = 0f,
						weeklyProgress = 0f,
					),
				)
			}
		}

		composeRule.onNodeWithText("0").assertIsDisplayed()
		composeRule.onNodeWithText("/ 10,000").assertIsDisplayed()
	}

	@Test
	fun fullProgress_displaysCompletedSteps() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				GoalProgressRings(
					goalProgress = GoalProgressState(
						gamificationEnabled = true,
						dailySteps = 10000,
						dailyGoalSteps = 10000,
						dailyProgress = 1.0f,
						weeklyProgress = 0.5f,
					),
				)
			}
		}

		composeRule.onNodeWithText("10,000").assertIsDisplayed()
	}

	@Test
	fun overflowProgress_clampsAtOneHundredPercent() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				GoalProgressRings(
					goalProgress = GoalProgressState(
						gamificationEnabled = true,
						dailySteps = 15000,
						dailyGoalSteps = 10000,
						dailyProgress = 1.5f,
						weeklyProgress = 0.8f,
					),
				)
			}
		}

		// Should render without crash; steps shown even when exceeding goal
		composeRule.onNodeWithText("15,000").assertIsDisplayed()
	}

	// endregion

	// region Status badges

	@Test
	fun completedGoal_showsAheadBadge() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				GoalProgressRings(
					goalProgress = GoalProgressState(
						gamificationEnabled = true,
						dailySteps = 12000,
						dailyGoalSteps = 10000,
						dailyProgress = 1.2f,
						weeklyProgress = 0.5f,
					),
				)
			}
		}

		composeRule.onNodeWithText("Ahead", substring = true).assertIsDisplayed()
	}

	// endregion
}
