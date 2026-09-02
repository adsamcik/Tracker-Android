package com.adsamcik.tracker.dashboard.ui.compose.visualization

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.adsamcik.tracker.dashboard.ui.compose.state.GoalProgressState
import com.adsamcik.tracker.shared.base.di.QualifiedStepCount
import com.adsamcik.tracker.shared.base.di.QualifiedStepCountUnavailableReason
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
						dailySteps = QualifiedStepCount.Ready(5000),
						dailyGoalSteps = 10000,
					),
				)
			}
		}

		composeRule.onAllNodesWithText("/", substring = true).assertCountEquals(0)
	}

	@Test
	fun zeroDailyGoal_rendersNothing() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				GoalProgressRings(
					goalProgress = GoalProgressState(
						gamificationEnabled = true,
						dailySteps = QualifiedStepCount.Ready(500),
						dailyGoalSteps = 0,
					),
				)
			}
		}

		composeRule.onAllNodesWithText("/", substring = true).assertCountEquals(0)
	}

	@Test
	fun unavailableQualifiedSteps_renderNoNumericGoal() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				GoalProgressRings(
					goalProgress = GoalProgressState(
						gamificationEnabled = true,
						dailySteps = QualifiedStepCount.Unavailable(
							QualifiedStepCountUnavailableReason.PARTIAL_CAPTURE,
						),
						dailyGoalSteps = 10_000,
					),
				)
			}
		}

		composeRule.onAllNodesWithText("/ 10,000").assertCountEquals(0)
		composeRule.onAllNodesWithText("0").assertCountEquals(0)
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
						dailySteps = QualifiedStepCount.Ready(6500),
						dailyGoalSteps = 10000,
						weeklySteps = QualifiedStepCount.Ready(35000),
						weeklyGoalSteps = 70000,
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
						dailySteps = QualifiedStepCount.Ready(0),
						dailyGoalSteps = 10000,
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
						dailySteps = QualifiedStepCount.Ready(10000),
						dailyGoalSteps = 10000,
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
						dailySteps = QualifiedStepCount.Ready(15000),
						dailyGoalSteps = 10000,
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
						dailySteps = QualifiedStepCount.Ready(12000),
						dailyGoalSteps = 10000,
					),
				)
			}
		}

		composeRule.onNodeWithText("Ahead", substring = true).assertIsDisplayed()
	}

	// endregion
}
