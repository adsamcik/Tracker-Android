package com.adsamcik.tracker.dashboard.ui.compose.visualization

import com.adsamcik.tracker.dashboard.ui.compose.state.GoalProgressState
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Test

class GoalProgressRingsStatusTest {

	@Test
	fun zeroProgressEarlyInDay_isMotivating() {
		val status = evaluateGoalProgressStatus(
			goalProgress = baseGoalProgress(dailySteps = 0, dailyProgress = 0f),
			now = LocalTime.of(8, 30),
		)

		assertEquals(GoalProgressStatus.GET_STARTED, status)
	}

	@Test
	fun zeroProgressMidMorning_stillShowsGetStarted() {
		val status = evaluateGoalProgressStatus(
			goalProgress = baseGoalProgress(dailySteps = 0, dailyProgress = 0f),
			now = LocalTime.of(10, 0),
		)

		assertEquals(GoalProgressStatus.GET_STARTED, status)
	}

	@Test
	fun lowProgressLateInDay_showsBehind() {
		val status = evaluateGoalProgressStatus(
			goalProgress = baseGoalProgress(dailySteps = 1_000, dailyProgress = 0.1f),
			now = LocalTime.of(18, 0),
		)

		assertEquals(GoalProgressStatus.BEHIND, status)
	}

	@Test
	fun progressNearExpectedPace_isOnTrack() {
		val status = evaluateGoalProgressStatus(
			goalProgress = baseGoalProgress(dailySteps = 5_000, dailyProgress = 0.5f),
			now = LocalTime.of(14, 0),
		)

		assertEquals(GoalProgressStatus.ON_TRACK, status)
	}

	@Test
	fun progressWellAheadOfPace_isAhead() {
		val status = evaluateGoalProgressStatus(
			goalProgress = baseGoalProgress(dailySteps = 7_000, dailyProgress = 0.7f),
			now = LocalTime.of(10, 0),
		)

		assertEquals(GoalProgressStatus.AHEAD, status)
	}

	private fun baseGoalProgress(
		dailySteps: Int,
		dailyProgress: Float,
	) = GoalProgressState(
		gamificationEnabled = true,
		dailySteps = dailySteps,
		dailyGoalSteps = 10_000,
		dailyProgress = dailyProgress,
		weeklySteps = 21_000,
		weeklyGoalSteps = 70_000,
		weeklyProgress = 0.3f,
	)
}
