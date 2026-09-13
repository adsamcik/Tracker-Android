package com.adsamcik.tracker.dashboard.ui.compose.tracking

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardLiveActivityValue
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardLiveSessionPresentation
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryProductState
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActivityOnlyTrackingContentTest {
	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun retainedBandShowsRecordingAndOnlyCapturedActivityValues() {
		setContent(
			DashboardLiveActivityValue(
				state = ActivityHistoryProductState.PARTIAL,
				coverage = ActivityHistoryCoverage.PARTIAL,
				knownActiveDurationNanos = 90_000_000_000L,
				latestMovementBand = ActivityHistoryType.WALKING,
				gapCount = 1,
				hasRetainedQualifiedEvidence = true,
			),
		)

		composeRule.onNodeWithTag("dashboard_activity_only_tracking_card")
			.assertIsDisplayed()
			.assertHasNoClickAction()
		composeRule.onNodeWithTag("dashboard_live_activity_header").assertTextEquals("Recording")
		composeRule.onNodeWithTag("dashboard_live_activity_band").assertTextEquals("Walking")
		composeRule.onNodeWithTag("dashboard_live_activity_active_time").assertTextEquals("1 m 30 s")
		composeRule.onNodeWithTag("dashboard_live_activity_coverage").assertTextEquals("Partial coverage")
		assertNoLocationPresentation()
	}

	@Test
	fun materializingWithoutFactShowsNoRecordingAndNoFabricatedZero() {
		setContent(
			DashboardLiveActivityValue(
				state = ActivityHistoryProductState.MATERIALIZING,
				coverage = ActivityHistoryCoverage.NONE,
				knownActiveDurationNanos = null,
				latestMovementBand = null,
				gapCount = 0,
				hasRetainedQualifiedEvidence = false,
			),
		)

		composeRule.onNodeWithTag("dashboard_live_activity_header")
			.assertTextEquals("Preparing Activity history…")
		composeRule.onNodeWithTag("dashboard_live_activity_band").assertTextEquals("—")
		composeRule.onNodeWithTag("dashboard_live_activity_active_time").assertTextEquals("—")
		composeRule.onAllNodesWithText("Recording").assertCountEquals(0)
		composeRule.onAllNodesWithText("0").assertCountEquals(0)
		assertNoLocationPresentation()
	}

	@Test
	fun failedHistoryNeverClaimsRecordingOrRetainedValues() {
		setContent(
			DashboardLiveActivityValue(
				state = ActivityHistoryProductState.FAILED,
				coverage = ActivityHistoryCoverage.NONE,
				knownActiveDurationNanos = null,
				latestMovementBand = null,
				gapCount = 0,
				hasRetainedQualifiedEvidence = false,
			),
		)

		composeRule.onNodeWithTag("dashboard_live_activity_header")
			.assertTextEquals("Activity history could not be verified")
		composeRule.onAllNodesWithText("Recording").assertCountEquals(0)
		composeRule.onNodeWithTag("dashboard_live_activity_band").assertTextEquals("—")
		composeRule.onNodeWithTag("dashboard_live_activity_active_time").assertTextEquals("—")
		assertNoLocationPresentation()
	}

	private fun assertNoLocationPresentation() {
		composeRule.onAllNodesWithText("Distance", substring = true).assertCountEquals(0)
		composeRule.onAllNodesWithText("Speed", substring = true).assertCountEquals(0)
		composeRule.onAllNodesWithText("Elevation", substring = true).assertCountEquals(0)
		composeRule.onAllNodesWithText("Awaiting GPS", substring = true).assertCountEquals(0)
		composeRule.onAllNodesWithText("View Map", substring = true).assertCountEquals(0)
	}

	private fun setContent(activity: DashboardLiveActivityValue) {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				ActivityOnlyTrackingContent(
					presentation = DashboardLiveSessionPresentation.ActivityOnly(
						segmentId = 42L,
						activity = activity,
					),
				)
			}
		}
	}
}
