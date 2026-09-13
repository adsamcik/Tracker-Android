package com.adsamcik.tracker.dashboard.ui.compose.tracking

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardLivePressureMetrics
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardLivePressureValue
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardLiveSessionPresentation
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import com.adsamcik.tracker.stats.api.repository.PressureHistoryCoverage
import com.adsamcik.tracker.tracker.data.session.TrackerSessionSnapshot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PressureOnlyTrackingContentTest {
	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun readyPressureShowsOnlyDirectPressureMetrics() {
		setContent(
			DashboardLivePressureValue.Ready(
				DashboardLivePressureMetrics(
					latestHectopascals = 1001.5f,
					minimumHectopascals = 999.5f,
					maximumHectopascals = 1002.0f,
					changeHectopascals = 1.5f,
					coverage = PressureHistoryCoverage.COMPLETE,
					zoneAuthorities = listOf("Europe/Prague"),
				),
			),
		)

		composeRule.onNodeWithTag("dashboard_pressure_only_tracking_card")
			.assertIsDisplayed()
			.assertHasNoClickAction()
		composeRule.onNodeWithTag("dashboard_live_pressure_header").assertTextEquals("Recording")
		composeRule.onNodeWithTag("dashboard_live_pressure_value").assertTextEquals("1001.5 hPa")
		composeRule.onAllNodesWithText("Range 999.5–1002.0 hPa").assertCountEquals(1)
		composeRule.onAllNodesWithText("Change 1.5 hPa").assertCountEquals(1)
		composeRule.onAllNodesWithText("Complete coverage").assertCountEquals(1)
		composeRule.onAllNodesWithText("Stored zone: Europe/Prague").assertCountEquals(1)
		composeRule.onAllNodesWithText("Distance", substring = true).assertCountEquals(0)
		composeRule.onAllNodesWithText("Speed", substring = true).assertCountEquals(0)
		composeRule.onAllNodesWithText("Elevation", substring = true).assertCountEquals(0)
		composeRule.onAllNodesWithText("Ascent", substring = true).assertCountEquals(0)
		composeRule.onAllNodesWithText("Awaiting GPS", substring = true).assertCountEquals(0)
	}

	@Test
	fun unavailablePressureNeverFabricatesANumericValue() {
		setContent(DashboardLivePressureValue.Unavailable)

		composeRule.onNodeWithTag("dashboard_live_pressure_value").assertTextEquals("—")
		composeRule.onNodeWithTag("dashboard_live_pressure_status").assertIsDisplayed()
		composeRule.onNodeWithTag("dashboard_live_pressure_header")
			.assertTextEquals("Pressure history unavailable")
		composeRule.onAllNodesWithText("Recording").assertCountEquals(0)
		composeRule.onAllNodesWithText("hPa", substring = true).assertCountEquals(0)
		composeRule.onAllNodesWithText("Range", substring = true).assertCountEquals(0)
		composeRule.onAllNodesWithText("Change", substring = true).assertCountEquals(0)
	}

	@Test
	fun materializingPressureKeepsMissingValueNonnumeric() {
		setContent(DashboardLivePressureValue.Materializing(null))

		composeRule.onNodeWithTag("dashboard_live_pressure_value").assertTextEquals("—")
		composeRule.onNodeWithTag("dashboard_live_pressure_status")
			.assertTextEquals("Preparing Pressure history…")
		composeRule.onNodeWithTag("dashboard_live_pressure_header")
			.assertTextEquals("Preparing Pressure history…")
		composeRule.onAllNodesWithText("Recording").assertCountEquals(0)
		composeRule.onAllNodesWithText("hPa", substring = true).assertCountEquals(0)
	}

	@Test
	fun materializingPressureWithRetainedEvidenceKeepsRecordingHeader() {
		setContent(
			DashboardLivePressureValue.Materializing(
				DashboardLivePressureMetrics(
					latestHectopascals = 1000.5f,
					minimumHectopascals = 999.5f,
					maximumHectopascals = 1001.0f,
					changeHectopascals = 1.0f,
					coverage = PressureHistoryCoverage.PARTIAL,
					zoneAuthorities = listOf("Europe/Prague"),
				),
			),
		)

		composeRule.onNodeWithTag("dashboard_live_pressure_header").assertTextEquals("Recording")
		composeRule.onNodeWithTag("dashboard_live_pressure_value").assertTextEquals("1000.5 hPa")
		composeRule.onNodeWithTag("dashboard_live_pressure_status")
			.assertTextEquals("Preparing Pressure history…")
	}

	@Test
	fun failedPressureUsesTypedNonRecordingHeader() {
		setContent(DashboardLivePressureValue.Failed)

		composeRule.onNodeWithTag("dashboard_live_pressure_header")
			.assertTextEquals("Pressure history could not be prepared")
		composeRule.onNodeWithTag("dashboard_live_pressure_value").assertTextEquals("—")
		composeRule.onAllNodesWithText("Recording").assertCountEquals(0)
	}

	private fun setContent(pressure: DashboardLivePressureValue) {
		val session = TrackerSessionSnapshot(
			id = SEGMENT_ID,
			start = System.currentTimeMillis() - 60_000L,
			isUserInitiated = true,
		)
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				PressureOnlyTrackingContent(
					sessionData = session,
					presentation = DashboardLiveSessionPresentation.PressureOnly(
						segmentId = SEGMENT_ID,
						pressure = pressure,
					),
				)
			}
		}
	}

	private companion object {
		const val SEGMENT_ID = 42L
	}
}
