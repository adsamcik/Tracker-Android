package com.adsamcik.tracker.dashboard.ui.compose.tracking

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.runtime.mutableStateOf
import com.adsamcik.tracker.dashboard.R
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardLiveStepsValue
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardLiveSessionPresentation
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardLiveActivityValue
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardLivePressureMetrics
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardLivePressureValue
import com.adsamcik.tracker.shared.base.extension.formatReadable
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import com.adsamcik.tracker.tracker.data.session.TrackerSessionSnapshot
import com.adsamcik.tracker.tracker.data.collection.TrackerActivityGroup
import com.adsamcik.tracker.tracker.data.collection.TrackerActivitySnapshot
import com.adsamcik.tracker.tracker.data.collection.TrackerActivityType
import com.adsamcik.tracker.tracker.data.collection.TrackerCollectionSnapshot
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryProductState
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryType
import com.adsamcik.tracker.stats.api.repository.PressureHistoryCoverage
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackingStatsGridTest {

	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun mixedNativeSensors_replaceLegacyActivityAndKeepHealthySiblingsVisible() {
		val activity = DashboardLiveActivityValue(ActivityHistoryProductState.MATERIALIZING,
			ActivityHistoryCoverage.NONE, null, ActivityHistoryType.STILL, 0, true, 1_500L)
		val pressure = DashboardLivePressureValue.Partial(DashboardLivePressureMetrics(
			1001.5f, 999.5f, 1002f, 1.5f, PressureHistoryCoverage.PARTIAL, listOf("UTC")))
		val presentation = mutableStateOf(DashboardLiveSessionPresentation.Standard(1L,
			activity = activity, pressure = pressure))
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				TrackingStatsGrid(TrackerSessionSnapshot(id = 1L, start = 1L),
					TrackerCollectionSnapshot(activity = TrackerActivitySnapshot(
						TrackerActivityType.RUNNING, TrackerActivityGroup.ON_FOOT, 99)), presentation.value)
			}
		}
		composeRule.onNodeWithText("Still").assertIsDisplayed()
		composeRule.onNodeWithText("1001.5 hPa").assertIsDisplayed()
		composeRule.onNodeWithText("Partial Pressure history").assertIsDisplayed()
		composeRule.onNodeWithText("Running").assertDoesNotExist()
		composeRule.runOnIdle {
			presentation.value = presentation.value.copy(
				pressure = DashboardLivePressureValue.Materializing(pressure.metrics))
		}
		composeRule.onNodeWithText("1001.5 hPa").assertIsDisplayed()
		composeRule.onNodeWithText("Preparing Pressure history…").assertDoesNotExist()
		composeRule.onNodeWithText("Partial Pressure history").assertDoesNotExist()

		composeRule.runOnIdle {
			presentation.value = presentation.value.copy(activity = DashboardLiveActivityValue(
				ActivityHistoryProductState.UNAVAILABLE, ActivityHistoryCoverage.NONE, null, null, 0, false))
		}
		composeRule.onNodeWithText("Activity history unavailable").assertIsDisplayed()
		composeRule.onNodeWithText("Still").assertDoesNotExist()
		composeRule.onNodeWithText("1001.5 hPa").assertIsDisplayed()
		composeRule.runOnIdle {
			presentation.value = presentation.value.copy(activity = activity,
				pressure = DashboardLivePressureValue.Unavailable)
		}
		composeRule.onNodeWithText("Still").assertIsDisplayed()
		composeRule.onNodeWithText("Pressure history unavailable").assertIsDisplayed()
		composeRule.onNodeWithText("1001.5 hPa").assertDoesNotExist()
	}

	@Test
	fun withBasicSession_showsDistanceLabel() {
		val now = System.currentTimeMillis()

		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				TrackingStatsGrid(
					sessionData = TrackerSessionSnapshot(
						id = 1L,
						start = now - 120_000L,
						end = now,
						distanceInM = 1500f,
						collections = 10,
					),
					collectionSnapshot = null,
					presentation = null,
				)
			}
		}

		// The grid should show the distance label from TrackerR
		composeRule.onNodeWithText("Distance", substring = true).assertIsDisplayed()
	}

	@Test
	fun qualifiedSteps_showDurableValueInsteadOfRawSnapshot() {
		val now = System.currentTimeMillis()

		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				TrackingStatsGrid(
					sessionData = TrackerSessionSnapshot(
						id = 1L,
						start = now - 120_000L,
						end = now,
						distanceInM = 500f,
						steps = 9_999,
						collections = 10,
					),
					collectionSnapshot = null,
					presentation = DashboardLiveSessionPresentation.Standard(1L,
						steps = DashboardLiveStepsValue.Complete(2_500L)),
				)
			}
		}

		composeRule.onNodeWithText("Steps", substring = true).assertIsDisplayed()
		composeRule.onNodeWithText(2_500L.formatReadable()).assertIsDisplayed()
		composeRule.onNodeWithText(9_999L.formatReadable()).assertDoesNotExist()
	}

	@Test
	fun withNullCollectionSnapshot_rendersWithoutCrash() {
		val now = System.currentTimeMillis()

		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				TrackingStatsGrid(
					sessionData = TrackerSessionSnapshot(
						id = 1L,
						start = now - 60_000L,
						end = now,
						distanceInM = 0f,
					),
					collectionSnapshot = null,
					presentation = null,
				)
			}
		}

		// Should render without crash even with null collectionSnapshot
		composeRule.onNodeWithText("Distance", substring = true).assertIsDisplayed()
	}

	@Test
	fun coveredZero_showsNumericZero() {
		val now = System.currentTimeMillis()

		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				TrackingStatsGrid(
					sessionData = TrackerSessionSnapshot(
						id = 1L,
						start = now - 60_000L,
						end = now,
						distanceInM = 100f,
						steps = 0,
					),
					collectionSnapshot = null,
					presentation = DashboardLiveSessionPresentation.Standard(1L,
						steps = DashboardLiveStepsValue.CoveredZero),
				)
			}
		}

		composeRule.onNodeWithText("Steps").assertIsDisplayed()
		composeRule.onNodeWithText("0").assertIsDisplayed()
	}

	@Test
	fun cellState_preservesEveryTypedNonnumericBoundary() {
		DashboardLiveStepsValue.Complete(42L).toTrackingStepsCell() shouldBe
			TrackingStepsCell.Numeric(42L)
		DashboardLiveStepsValue.CoveredZero.toTrackingStepsCell() shouldBe
			TrackingStepsCell.Numeric(0L)
		DashboardLiveStepsValue.Partial(12L).toTrackingStepsCell() shouldBe
			TrackingStepsCell.Status(R.string.dashboard_live_steps_partial)
		DashboardLiveStepsValue.Materializing.toTrackingStepsCell() shouldBe
			TrackingStepsCell.Status(R.string.dashboard_live_steps_materializing)
		DashboardLiveStepsValue.Missing.toTrackingStepsCell() shouldBe
			TrackingStepsCell.Status(R.string.dashboard_live_steps_missing)
		DashboardLiveStepsValue.Unavailable.toTrackingStepsCell() shouldBe
			TrackingStepsCell.Status(R.string.dashboard_live_steps_unavailable)
		(null as DashboardLiveStepsValue?).toTrackingStepsCell() shouldBe TrackingStepsCell.Omitted
	}
}
