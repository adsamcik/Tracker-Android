package com.adsamcik.tracker.dashboard.ui.compose.components

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import com.adsamcik.tracker.stats.api.PolicyTier
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DashboardTopBarTest {

	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun idle_showsDashboardTitle() {
		setContent(isTracking = false)

		composeRule.onNodeWithText("Dashboard").assertIsDisplayed()
	}

	@Test
	fun tracking_showsTrackingTitle() {
		setContent(isTracking = true)

		composeRule.onNodeWithText("Tracking").assertIsDisplayed()
	}

	@Test
	fun pointsGreaterThanZeroWithGameClick_showsPointsChip() {
		setContent(isTracking = false, pointsToday = 150, onGameClick = {})

		composeRule.onNodeWithText("150").assertIsDisplayed()
	}

	@Test
	fun pointsZero_noPointsChip() {
		setContent(isTracking = false, pointsToday = 0, onGameClick = {})

		composeRule.onAllNodesWithText("0").assertCountEquals(0)
	}

	@Test
	fun settingsClick_callsCallback() {
		var settingsClicked = false
		setContent(
			isTracking = false,
			onSettingsClick = { settingsClicked = true },
		)

		composeRule.onNodeWithContentDescription("Open settings").performClick()

		settingsClicked shouldBe true
	}

	@Test
	fun settingsButton_hasMinimumTouchTarget() {
		setContent(isTracking = false)

		composeRule.onNodeWithContentDescription("Open settings")
			.assertWidthIsAtLeast(48.dp)
			.assertHeightIsAtLeast(48.dp)
	}

	@Test
	fun customizeButtonShows_whenCallbackProvided() {
		setContent(isTracking = false, onCustomizeClick = {})

		composeRule.onNodeWithContentDescription("Customize Dashboard").assertIsDisplayed()
	}

	@Test
	fun trackingMenuSeparatesOffFromAutomaticPauseWithoutALiveSession() {
		var off = 0
		var pause = 0
		setContent(isTracking = false, onTurnOffTracking = { off++ }, onPauseAutomaticTracking = { pause++ })
		composeRule.onNodeWithTag("trackingActionsMenu").performClick()
		composeRule.onNodeWithTag("pauseAutomaticTracking").performClick()
		pause shouldBe 1
		off shouldBe 0
		composeRule.onNodeWithTag("trackingActionsMenu").performClick()
		composeRule.onNodeWithTag("turnOffTracking").performClick()
		off shouldBe 1
		pause shouldBe 1
	}

	private fun setContent(
		isTracking: Boolean,
		isLocked: Boolean = false,
		policyTier: PolicyTier = PolicyTier.OFF,
		pointsToday: Int = 0,
		onSettingsClick: () -> Unit = {},
		onGameClick: (() -> Unit)? = null,
		onCustomizeClick: (() -> Unit)? = null,
		onTurnOffTracking: (() -> Unit)? = null,
		onPauseAutomaticTracking: (() -> Unit)? = null,
	) {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				DashboardTopBar(
					isTracking = isTracking,
					isLocked = isLocked,
					policyTier = policyTier,
					pointsToday = pointsToday,
					onSettingsClick = onSettingsClick,
					onGameClick = onGameClick,
					onCustomizeClick = onCustomizeClick,
					onTurnOffTracking = onTurnOffTracking,
					onPauseAutomaticTracking = onPauseAutomaticTracking,
				)
			}
		}
	}
}
