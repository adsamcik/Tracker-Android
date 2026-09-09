package com.adsamcik.tracker.statistics.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.statistics.presenter.TripDetailStepsState
import com.adsamcik.tracker.stats.api.TransportMode
import com.adsamcik.tracker.stats.api.repository.TripSummary
import com.adsamcik.tracker.stats.api.value.DistanceM
import com.adsamcik.tracker.stats.api.value.DurationMs
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.api.value.StepCount
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportedStepsOverviewTest {
	@get:Rule val composeRule = createComposeRule()

	@Test
	fun coveredZeroIsVisibleWithoutOtherSourceOrElapsedClaims() {
		render(TripDetailStepsState.Complete(0))
		composeRule.onNodeWithText("Imported Steps").assertIsDisplayed()
		composeRule.onNodeWithText("0").assertIsDisplayed()
		composeRule.onNodeWithText("Distance").assertDoesNotExist()
		composeRule.onNodeWithText("Duration").assertDoesNotExist()
		composeRule.onNodeWithText("Route", substring = false).assertDoesNotExist()
		composeRule.onNodeWithText("Samples").assertDoesNotExist()
		composeRule.onNodeWithText("Route and other sensor data are not included.").assertIsDisplayed()
	}

	@Test
	fun unavailableStepsDoNotBecomeRawCompatibilityZero() {
		render(TripDetailStepsState.Unavailable)
		composeRule.onNodeWithText("0").assertDoesNotExist()
		composeRule.onNodeWithText("Imported Steps").assertIsDisplayed()
	}

	private fun render(steps: TripDetailStepsState) {
		val trip = TripSummary(
			id = 42L,
			startTimeMs = EpochMs(1_000L),
			endTimeMs = EpochMs(5_000L),
			distance = DistanceM(0f),
			steps = StepCount(0),
			duration = DurationMs(4_000L),
			primaryMode = TransportMode.UNKNOWN,
			sampleCount = 0,
			source = SegmentSource.PORTABLE_STEPS_IMPORT,
		)
		composeRule.setContent {
			MaterialTheme { ImportedStepsOverview(trip, steps, onRetrySteps = {}) }
		}
	}
}
