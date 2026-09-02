package com.adsamcik.tracker.game.ui.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.adsamcik.tracker.shared.base.di.QualifiedStepCount
import com.adsamcik.tracker.shared.base.di.QualifiedStepCountUnavailableReason
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StepsCardTest {
	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun dailyReady_isVisibleWhenWeeklyCoverageIsPartial() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				StepsCard(
					steps = StepsSummaryUi(
						stepsToday = QualifiedStepCount.Ready(1_234),
						stepsWeek = QualifiedStepCount.Unavailable(
							QualifiedStepCountUnavailableReason.PARTIAL_CAPTURE,
						),
						goalDay = 10_000,
						goalWeek = 70_000,
					),
					stepCounterSupported = true,
				)
			}
		}

		composeRule.onNodeWithText("1,234 / 10,000").assertIsDisplayed()
		composeRule.onNodeWithText("Loading…").assertDoesNotExist()
		composeRule.onNodeWithText("Step sensor unavailable").assertDoesNotExist()
	}

	@Test
	fun unavailableDailyAndWeekly_neverRenderFabricatedZero() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				StepsCard(
					steps = StepsSummaryUi(
						stepsToday = QualifiedStepCount.Unavailable(
							QualifiedStepCountUnavailableReason.STORAGE_UNAVAILABLE,
						),
						stepsWeek = QualifiedStepCount.Unavailable(
							QualifiedStepCountUnavailableReason.STORAGE_UNAVAILABLE,
						),
						goalDay = 10_000,
						goalWeek = 70_000,
					),
					stepCounterSupported = true,
				)
			}
		}

		composeRule.onNodeWithText("0", substring = true).assertDoesNotExist()
		composeRule.onNodeWithText("Loading…").assertDoesNotExist()
		composeRule.onNodeWithText("Step sensor unavailable").assertDoesNotExist()
	}

	@Test
	fun materializingCoverage_usesExistingLocalizedLoadingCopyWithoutRenderingZero() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				StepsCard(
					steps = StepsSummaryUi(
						stepsToday = QualifiedStepCount.Unavailable(
							QualifiedStepCountUnavailableReason.MATERIALIZING,
						),
						stepsWeek = QualifiedStepCount.Unavailable(
							QualifiedStepCountUnavailableReason.MATERIALIZING,
						),
						goalDay = 10_000,
						goalWeek = 70_000,
					),
					stepCounterSupported = true,
				)
			}
		}

		composeRule.onNodeWithText("Loading…").assertIsDisplayed()
		composeRule.onNodeWithText("0", substring = true).assertDoesNotExist()
		composeRule.onNodeWithText("Step sensor unavailable").assertDoesNotExist()
	}

	@Test
	fun qualifiedHistory_remainsVisibleWhenCurrentHardwareHasNoStepCounter() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				StepsCard(
					steps = StepsSummaryUi(
						stepsToday = QualifiedStepCount.Ready(1_234),
						stepsWeek = QualifiedStepCount.Ready(5_678),
						goalDay = 10_000,
						goalWeek = 70_000,
					),
					stepCounterSupported = false,
				)
			}
		}

		composeRule.onNodeWithText("1,234 / 10,000").assertIsDisplayed()
		composeRule.onNodeWithText("5,678 / 70,000").assertIsDisplayed()
	}
}
