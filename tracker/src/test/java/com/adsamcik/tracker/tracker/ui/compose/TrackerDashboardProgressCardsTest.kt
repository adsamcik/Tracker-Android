package com.adsamcik.tracker.tracker.ui.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackerDashboardProgressCardsTest {

	@get:Rule
	val composeRule = createComposeRule()

	private val defaultSettings = TrackerSettingsState(
		autoUnitSwitch = false,
		lengthSystem = LengthSystem.Metric,
		speedFormat = SpeedFormat.Hour
	)

	private fun fakeSummaryProvider(summary: DailySummary? = null) = object : DailySummaryProvider {
		override suspend fun fetchTodaySummary(): DailySummary? = summary
		override fun observeTodayLive(): Flow<DailySummary?> = flowOf(summary)
	}

	private fun fakeGoalProvider(
		stepsToday: Int = 0,
		goalSteps: Int = 0,
		gamificationEnabled: Boolean = false
	) = object : GoalProgressProvider {
		override val goalProgressFlow = MutableStateFlow(
			GoalProgress(
				stepsToday = stepsToday,
				goalSteps = goalSteps,
				gamificationEnabled = gamificationEnabled
			)
		)
	}

	// region TodayProgressCard

	@Test
	fun todayProgressCard_notTracking_withSummary_showsTitle() {
		composeRule.setContent {
			AppTheme {
				TodayProgressCard(
					isTracking = false,
					settings = defaultSettings,
					dailySummaryProvider = fakeSummaryProvider(
						DailySummary(
							totalDistanceM = 5000f,
							totalSteps = 7500,
							totalDurationMs = 3600_000L,
							sessionCount = 2
						)
					),
					goalProgressProvider = fakeGoalProvider()
				)
			}
		}

		// Wait for LaunchedEffect to complete
		composeRule.waitForIdle()

		// Title should be visible
		composeRule.onNodeWithText("Today", substring = true).assertIsDisplayed()
	}

	@Test
	fun todayProgressCard_tracking_isHidden() {
		composeRule.setContent {
			AppTheme {
				TodayProgressCard(
					isTracking = true,
					settings = defaultSettings,
					dailySummaryProvider = fakeSummaryProvider(
						DailySummary(
							totalDistanceM = 5000f,
							totalSteps = 7500,
							totalDurationMs = 3600_000L,
							sessionCount = 2
						)
					),
					goalProgressProvider = fakeGoalProvider()
				)
			}
		}

		// Card should not show when tracking
		composeRule.onNodeWithText("Today", substring = true).assertDoesNotExist()
	}

	@Test
	fun todayProgressCard_nullSummary_showsEmptyState() {
		composeRule.setContent {
			AppTheme {
				TodayProgressCard(
					isTracking = false,
					settings = defaultSettings,
					dailySummaryProvider = fakeSummaryProvider(null),
					goalProgressProvider = fakeGoalProvider()
				)
			}
		}

		composeRule.waitForIdle()

		// Title should still show
		composeRule.onNodeWithText("Today", substring = true).assertIsDisplayed()
	}

	// endregion

	// region GoalProgressRing

	@Test
	fun goalProgressRing_gamificationDisabled_doesNotRender() {
		composeRule.setContent {
			AppTheme {
				GoalProgressRing(
					goalProgressProvider = fakeGoalProvider(
						stepsToday = 5000,
						goalSteps = 10000,
						gamificationEnabled = false
					)
				)
			}
		}

		// Should not render percentage when gamification disabled
		composeRule.onNodeWithText("50%").assertDoesNotExist()
	}

	@Test
	fun goalProgressRing_zeroGoal_doesNotRender() {
		composeRule.setContent {
			AppTheme {
				GoalProgressRing(
					goalProgressProvider = fakeGoalProvider(
						stepsToday = 5000,
						goalSteps = 0,
						gamificationEnabled = true
					)
				)
			}
		}

		composeRule.onNodeWithText("%", substring = true).assertDoesNotExist()
	}

	@Test
	fun goalProgressRing_withGoal_showsPercentage() {
		composeRule.setContent {
			AppTheme {
				GoalProgressRing(
					goalProgressProvider = fakeGoalProvider(
						stepsToday = 5000,
						goalSteps = 10000,
						gamificationEnabled = true
					)
				)
			}
		}

		composeRule.onNodeWithText("50%").assertIsDisplayed()
	}

	@Test
	fun goalProgressRing_goalExceeded_shows100Percent() {
		composeRule.setContent {
			AppTheme {
				GoalProgressRing(
					goalProgressProvider = fakeGoalProvider(
						stepsToday = 15000,
						goalSteps = 10000,
						gamificationEnabled = true
					)
				)
			}
		}

		composeRule.onNodeWithText("100%").assertIsDisplayed()
	}

	// endregion

	// region getTripIcon utility

	@Test
	fun getTripIcon_walkingActivity_returnsExpectedIcon() {
		val icon = getTripIcon(-2)
		// Walking icon should be returned for primaryActivity -2
		assertTrue("Walking icon should not be null", icon != null)
	}

	@Test
	fun getTripIcon_nullActivity_returnsFallbackIcon() {
		val icon = getTripIcon(null)
		assertTrue("Fallback icon should not be null", icon != null)
	}

	@Test
	fun getTripIcon_unknownActivity_returnsFallbackIcon() {
		val icon = getTripIcon(999)
		assertTrue("Fallback icon should not be null", icon != null)
	}

	// endregion
}
