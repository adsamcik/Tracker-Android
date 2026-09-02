package com.adsamcik.tracker.dashboard.ui.compose.visualization

import com.adsamcik.tracker.dashboard.ui.compose.state.GoalProgressState
import com.adsamcik.tracker.shared.base.di.QualifiedStepCount
import com.adsamcik.tracker.shared.base.di.QualifiedStepCountUnavailableReason
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoalProgressRingsStatusTest {

	@Test
	fun zeroProgressEarlyInDay_isMotivating() {
		val status = evaluateGoalProgressStatus(
			goalProgress = baseGoalProgress(dailySteps = 0),
			now = LocalTime.of(8, 30),
		)

		assertEquals(GoalProgressStatus.GET_STARTED, status)
	}

	@Test
	fun zeroProgressMidMorning_stillShowsGetStarted() {
		val status = evaluateGoalProgressStatus(
			goalProgress = baseGoalProgress(dailySteps = 0),
			now = LocalTime.of(10, 0),
		)

		assertEquals(GoalProgressStatus.GET_STARTED, status)
	}

	@Test
	fun lowProgressLateInDay_showsBehind() {
		val status = evaluateGoalProgressStatus(
			goalProgress = baseGoalProgress(dailySteps = 1_000),
			now = LocalTime.of(18, 0),
		)

		assertEquals(GoalProgressStatus.BEHIND, status)
	}

	@Test
	fun progressNearExpectedPace_isOnTrack() {
		val status = evaluateGoalProgressStatus(
			goalProgress = baseGoalProgress(dailySteps = 5_000),
			now = LocalTime.of(14, 0),
		)

		assertEquals(GoalProgressStatus.ON_TRACK, status)
	}

	@Test
	fun progressWellAheadOfPace_isAhead() {
		val status = evaluateGoalProgressStatus(
			goalProgress = baseGoalProgress(dailySteps = 7_000),
			now = LocalTime.of(10, 0),
		)

		assertEquals(GoalProgressStatus.AHEAD, status)
	}

	@Test
	fun unavailableQualifiedSteps_haveNoGoalStatus() {
		val status = evaluateGoalProgressStatus(
			goalProgress = GoalProgressState(
				gamificationEnabled = true,
				dailySteps = QualifiedStepCount.Unavailable(
					QualifiedStepCountUnavailableReason.STORAGE_UNAVAILABLE,
				),
				dailyGoalSteps = 10_000,
			),
			now = LocalTime.NOON,
		)

		assertNull(status)
	}

	private fun baseGoalProgress(
		dailySteps: Int,
	) = GoalProgressState(
		gamificationEnabled = true,
		dailySteps = QualifiedStepCount.Ready(dailySteps),
		dailyGoalSteps = 10_000,
		weeklySteps = QualifiedStepCount.Ready(21_000),
		weeklyGoalSteps = 70_000,
	)
}
