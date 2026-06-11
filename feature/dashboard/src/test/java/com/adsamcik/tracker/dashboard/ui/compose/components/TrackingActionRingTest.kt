package com.adsamcik.tracker.dashboard.ui.compose.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.adsamcik.tracker.dashboard.ui.compose.state.GoalProgressState
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackingActionRingTest {

	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun idleWithPermission_showsStartTrackingText() {
		setContent(isTracking = false, hasPermission = true)

		composeRule.onNodeWithText("Start tracking").assertIsDisplayed()
	}

	@Test
	fun tracking_showsStopTrackingText() {
		setContent(isTracking = true, hasPermission = true)

		composeRule.onNodeWithText("Stop tracking").assertIsDisplayed()
	}

	@Test
	fun noPermission_showsEnableLocationText() {
		setContent(isTracking = false, hasPermission = false)

		composeRule.onNodeWithText("Enable location").assertIsDisplayed()
	}

	@Test
	fun clickWithoutPermission_callsOnRequestPermission() {
		var permissionRequested = false
		var toggleCalled = false
		setContent(
			isTracking = false,
			hasPermission = false,
			onToggleTracking = { toggleCalled = true },
			onRequestPermission = { permissionRequested = true },
		)

		composeRule.onNodeWithTag("tracking_action_ring").performClick()

		permissionRequested shouldBe true
		toggleCalled shouldBe false
	}

	@Test
	fun clickWithPermission_callsOnToggleTracking() {
		var toggleCalled = false
		var permissionRequested = false
		setContent(
			isTracking = false,
			hasPermission = true,
			onToggleTracking = { toggleCalled = true },
			onRequestPermission = { permissionRequested = true },
		)

		composeRule.onNodeWithTag("tracking_action_ring").performClick()

		toggleCalled shouldBe true
		permissionRequested shouldBe false
	}

	@Test
	fun gamificationEnabled_showsRingTag() {
		setContent(
			isTracking = false,
			hasPermission = true,
			goalProgress = GoalProgressState(
				gamificationEnabled = true,
				dailyGoalSteps = 10_000,
				dailySteps = 5_000,
				dailyProgress = 0.5f,
			),
		)

		composeRule.onNodeWithTag("tracking_action_ring").assertExists()
	}

	@Test
	fun gamificationDisabled_showsStandaloneButton() {
		setContent(
			isTracking = false,
			hasPermission = true,
			goalProgress = GoalProgressState(gamificationEnabled = false),
		)

		composeRule.onNodeWithTag("tracking_action_ring").assertExists()
		composeRule.onNodeWithText("Start tracking").assertIsDisplayed()
	}

	private fun setContent(
		isTracking: Boolean,
		hasPermission: Boolean,
		goalProgress: GoalProgressState = GoalProgressState(),
		onToggleTracking: () -> Unit = {},
		onRequestPermission: () -> Unit = {},
	) {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				TrackingActionRing(
					isTracking = isTracking,
					hasPermission = hasPermission,
					goalProgress = goalProgress,
					onToggleTracking = onToggleTracking,
					onRequestPermission = onRequestPermission,
				)
			}
		}
	}
}
