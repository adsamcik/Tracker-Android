package com.adsamcik.tracker.dashboard.ui.compose.tracking

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.adsamcik.tracker.dashboard.R
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardLiveStepsValue
import com.adsamcik.tracker.shared.base.extension.formatReadable
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import com.adsamcik.tracker.tracker.data.session.TrackerSessionSnapshot
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
					steps = null,
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
					steps = DashboardLiveStepsValue.Complete(2_500L),
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
					steps = null,
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
					steps = DashboardLiveStepsValue.CoveredZero,
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
