package com.adsamcik.tracker.tracker.ui.compose

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Sailing
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.di.DailySummary
import com.adsamcik.tracker.shared.base.di.DailySummaryProvider
import com.adsamcik.tracker.shared.base.di.GoalProgress
import com.adsamcik.tracker.shared.base.di.GoalProgressProvider
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsState
import com.adsamcik.tracker.shared.preferences.type.LengthSystem
import com.adsamcik.tracker.shared.preferences.type.SpeedFormat
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Additional tests for TrackerDashboardProgressCards composables
 * covering gaps in the existing TrackerDashboardProgressCardsTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackerDashboardProgressCardsAdditionalTest {

	@get:Rule
	val composeRule = createComposeRule()

	private val defaultSettings = TrackerSettingsState(
		autoUnitSwitch = false,
		lengthSystem = LengthSystem.Metric,
		speedFormat = SpeedFormat.Hour,
	)

	private fun fakeSummaryProvider(summary: DailySummary? = null) = object : DailySummaryProvider {
		override suspend fun fetchTodaySummary(): DailySummary? = summary
		override fun observeTodayLive(): Flow<DailySummary?> = flowOf(summary)
	}

	private fun fakeGoalProvider(
		stepsToday: Int = 0,
		goalSteps: Int = 0,
		gamificationEnabled: Boolean = false,
	) = object : GoalProgressProvider {
		override val goalProgressFlow = MutableStateFlow(
			GoalProgress(
				stepsToday = stepsToday,
				goalSteps = goalSteps,
				gamificationEnabled = gamificationEnabled,
			),
		)
	}

	// region TodayProgressCard - with summary data showing steps

	@Test
	fun todayProgressCard_withSteps_showsStepsSection() {
		composeRule.setContent {
			AppTheme {
				TodayProgressCard(
					isTracking = false,
					settings = defaultSettings,
					dailySummaryProvider = fakeSummaryProvider(
						DailySummary(
							totalDistanceM = 3200f,
							totalSteps = 4500,
							totalDurationMs = 2400_000L,
							sessionCount = 1,
						),
					),
					goalProgressProvider = fakeGoalProvider(),
				)
			}
		}

		composeRule.waitForIdle()
		composeRule.onNodeWithText("Steps", substring = true).assertIsDisplayed()
		composeRule.onNodeWithText("4,500", substring = true).assertIsDisplayed()
	}

	@Test
	fun todayProgressCard_zeroSteps_hidesStepsSection() {
		composeRule.setContent {
			AppTheme {
				TodayProgressCard(
					isTracking = false,
					settings = defaultSettings,
					dailySummaryProvider = fakeSummaryProvider(
						DailySummary(
							totalDistanceM = 1000f,
							totalSteps = 0,
							totalDurationMs = 600_000L,
							sessionCount = 1,
						),
					),
					goalProgressProvider = fakeGoalProvider(),
				)
			}
		}

		composeRule.waitForIdle()
		// Steps label should NOT be visible when totalSteps == 0
		composeRule.onNodeWithText("Steps", substring = true).assertDoesNotExist()
	}

	@Test
	fun todayProgressCard_showsDurationLabel() {
		composeRule.setContent {
			AppTheme {
				TodayProgressCard(
					isTracking = false,
					settings = defaultSettings,
					dailySummaryProvider = fakeSummaryProvider(
						DailySummary(
							totalDistanceM = 5000f,
							totalSteps = 7500,
							totalDurationMs = 7200_000L,
							sessionCount = 3,
						),
					),
					goalProgressProvider = fakeGoalProvider(),
				)
			}
		}

		composeRule.waitForIdle()
		composeRule.onNodeWithText("Duration", substring = true).assertIsDisplayed()
	}

	@Test
	fun todayProgressCard_imperialSettings_rendersWithoutCrash() {
		val imperialSettings = TrackerSettingsState(
			autoUnitSwitch = false,
			lengthSystem = LengthSystem.Imperial,
			speedFormat = SpeedFormat.Hour,
		)

		composeRule.setContent {
			AppTheme {
				TodayProgressCard(
					isTracking = false,
					settings = imperialSettings,
					dailySummaryProvider = fakeSummaryProvider(
						DailySummary(
							totalDistanceM = 8000f,
							totalSteps = 10000,
							totalDurationMs = 5400_000L,
							sessionCount = 2,
						),
					),
					goalProgressProvider = fakeGoalProvider(),
				)
			}
		}

		composeRule.waitForIdle()
		composeRule.onNodeWithText("Today", substring = true).assertIsDisplayed()
	}

	// endregion

	// region GoalProgressRing - edge cases

	@Test
	fun goalProgressRing_1percentProgress_shows1Percent() {
		composeRule.setContent {
			AppTheme {
				GoalProgressRing(
					goalProgressProvider = fakeGoalProvider(
						stepsToday = 100,
						goalSteps = 10000,
						gamificationEnabled = true,
					),
				)
			}
		}

		composeRule.onNodeWithText("1%").assertIsDisplayed()
	}

	@Test
	fun goalProgressRing_99percentProgress_shows99Percent() {
		composeRule.setContent {
			AppTheme {
				GoalProgressRing(
					goalProgressProvider = fakeGoalProvider(
						stepsToday = 9900,
						goalSteps = 10000,
						gamificationEnabled = true,
					),
				)
			}
		}

		composeRule.onNodeWithText("99%").assertIsDisplayed()
	}

	// endregion

	// region getTripIcon - all activity types

	@Test
	fun getTripIcon_running_returnsRunIcon() {
		assertEquals(Icons.AutoMirrored.Filled.DirectionsRun, getTripIcon(-3))
	}

	@Test
	fun getTripIcon_biking_returnsBikeIcon() {
		assertEquals(Icons.AutoMirrored.Filled.DirectionsBike, getTripIcon(-4))
	}

	@Test
	fun getTripIcon_driving_returnsCarIcon() {
		assertEquals(Icons.Filled.DirectionsCar, getTripIcon(-5))
	}

	@Test
	fun getTripIcon_inVehicleAlternate_returnsCarIcon() {
		assertEquals(Icons.Filled.DirectionsCar, getTripIcon(-34))
	}

	@Test
	fun getTripIcon_sailing_returnsSailingIcon() {
		assertEquals(Icons.Filled.Sailing, getTripIcon(-26))
	}

	@Test
	fun getTripIcon_flying_returnsFlightIcon() {
		assertEquals(Icons.Filled.Flight, getTripIcon(-31))
	}

	@Test
	fun getTripIcon_walking_returnsWalkIcon() {
		assertEquals(Icons.AutoMirrored.Filled.DirectionsWalk, getTripIcon(-2))
	}

	@Test
	fun getTripIcon_zeroActivity_returnsFallbackIcon() {
		assertEquals(Icons.Filled.Route, getTripIcon(0))
	}

	@Test
	fun getTripIcon_positiveUnknown_returnsFallbackIcon() {
		assertEquals(Icons.Filled.Route, getTripIcon(42))
	}

	@Test
	fun getTripIcon_negativeUnknown_returnsFallbackIcon() {
		assertEquals(Icons.Filled.Route, getTripIcon(-99))
	}

	// endregion
}
