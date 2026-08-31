package com.adsamcik.tracker.dashboard.ui.compose.tracking

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardLiveSessionPresentation
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardLiveStepsValue
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import com.adsamcik.tracker.tracker.data.session.TrackerSessionSnapshot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StepsOnlyTrackingContentTest {
	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun completeSteps_areAboveFoldWithoutLocationControls() {
		setContent(DashboardLiveStepsValue.Complete(42L))

		composeRule.onNodeWithTag("dashboard_steps_only_tracking_card")
			.assertIsDisplayed()
			.assertHasNoClickAction()
		composeRule.onNodeWithTag("dashboard_live_steps_value").assertTextEquals("42")
		composeRule.onAllNodesWithText("Awaiting GPS signal", substring = true)
			.assertCountEquals(0)
		composeRule.onAllNodesWithText("Distance", substring = true).assertCountEquals(0)
		composeRule.onAllNodesWithText("Speed", substring = true).assertCountEquals(0)
		composeRule.onAllNodesWithText("Accuracy", substring = true).assertCountEquals(0)
		composeRule.onAllNodesWithText("Sensor Details", substring = true).assertCountEquals(0)
	}

	@Test
	fun completeCoveredZero_isTheOnlyZeroPresentation() {
		setContent(DashboardLiveStepsValue.CoveredZero)

		composeRule.onNodeWithTag("dashboard_live_steps_value").assertTextEquals("0")
		composeRule.onNodeWithText("Covered interval with no recorded steps").assertIsDisplayed()
	}

	@Test
	fun positivePartial_isPresentedAsALowerBound() {
		setContent(DashboardLiveStepsValue.Partial(lowerBound = 17L))

		composeRule.onNodeWithTag("dashboard_live_steps_value")
			.assertTextEquals("At least 17")
		composeRule.onNodeWithText("Partial session coverage").assertIsDisplayed()
	}

	@Test
	fun nonNumericStates_neverFabricateZero() {
		setContent(DashboardLiveStepsValue.Missing)

		composeRule.onNodeWithTag("dashboard_live_steps_value").assertTextEquals("—")
		composeRule.onNodeWithText("Waiting for a qualifying step observation").assertIsDisplayed()
		composeRule.onAllNodesWithText("0").assertCountEquals(0)
	}

	private fun setContent(steps: DashboardLiveStepsValue) {
		val session = TrackerSessionSnapshot(
			id = SEGMENT_ID,
			start = System.currentTimeMillis() - 60_000L,
			isUserInitiated = true,
		)
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				StepsOnlyTrackingContent(
					sessionData = session,
					presentation = DashboardLiveSessionPresentation.StepsOnly(
						segmentId = SEGMENT_ID,
						steps = steps,
					),
				)
			}
		}
	}

	private companion object {
		const val SEGMENT_ID = 42L
	}
}
