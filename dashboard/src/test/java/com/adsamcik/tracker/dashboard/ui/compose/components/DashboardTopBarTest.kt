package com.adsamcik.tracker.dashboard.ui.compose.components

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
	fun customizeButtonShows_whenCallbackProvided() {
		setContent(isTracking = false, onCustomizeClick = {})

		composeRule.onNodeWithContentDescription("Customize Dashboard").assertIsDisplayed()
	}

	private fun setContent(
		isTracking: Boolean,
		isLocked: Boolean = false,
		policyTier: PolicyTier = PolicyTier.OFF,
		pointsToday: Int = 0,
		onSettingsClick: () -> Unit = {},
		onGameClick: (() -> Unit)? = null,
		onCustomizeClick: (() -> Unit)? = null,
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
				)
			}
		}
	}
}
