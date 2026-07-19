package com.adsamcik.tracker.game.ui.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.adsamcik.tracker.game.minigame.FuseRunVisualPayload
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FuseRunVisualizationTest {

	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun waitingState_exposesGoalAndSignalStatus() {
		setPayload(payload(hasStarted = false))

		composeRule.onNodeWithText("Waiting for a GPS fix").assertIsDisplayed()
		composeRule.onNodeWithContentDescription(
			"Fuse Run waiting for a GPS fix. Goal 5 charges.",
			substring = true,
		).assertIsDisplayed()
	}

	@Test
	fun activeState_exposesDistanceTimerAndStreak() {
		setPayload(
			payload(
				distanceFromChargeMeters = 14.0,
				requiredDistanceMeters = 22.0,
				remainingTimeMs = 12_000L,
				currentStreak = 1,
				bestStreak = 2,
			),
		)

		composeRule.onNodeWithText("Escape the blast radius").assertIsDisplayed()
		composeRule.onNodeWithText("14 / 22 m").assertIsDisplayed()
		composeRule.onNodeWithContentDescription(
			"14 of 22 metres from the charge. 12 seconds remaining. Streak 1.",
			substring = true,
		).assertIsDisplayed()
	}

	@Test
	fun criticalFuse_hasNonErrorWarningCopy() {
		setPayload(payload(remainingTimeMs = 7_000L))

		composeRule.onNodeWithText("Fuse critical — move!").assertIsDisplayed()
	}

	@Test
	fun completedState_celebratesAndReportsBestStreak() {
		setPayload(
			payload(
				defusedCharges = 5,
				currentStreak = 5,
				bestStreak = 5,
				remainingTimeMs = 0L,
			),
		)

		composeRule.onNodeWithText("Blast chain complete!").assertIsDisplayed()
		composeRule.onNodeWithContentDescription(
			"Fuse Run complete. 5 of 5 charges defused. Best streak 5.",
			substring = true,
		).assertIsDisplayed()
	}

	private fun setPayload(payload: FuseRunVisualPayload) {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				FuseRunComposable(payload)
			}
		}
	}

	private fun payload(
		hasStarted: Boolean = true,
		defusedCharges: Int = 1,
		targetCharges: Int = 5,
		roundNumber: Int = 2,
		distanceFromChargeMeters: Double = 8.0,
		requiredDistanceMeters: Double = 22.0,
		remainingTimeMs: Long = 20_000L,
		roundDurationMs: Long = 30_000L,
		currentStreak: Int = 1,
		bestStreak: Int = 1,
	): FuseRunVisualPayload = FuseRunVisualPayload(
		hasStarted = hasStarted,
		defusedCharges = defusedCharges,
		targetCharges = targetCharges,
		roundNumber = roundNumber,
		distanceFromChargeMeters = distanceFromChargeMeters,
		requiredDistanceMeters = requiredDistanceMeters,
		remainingTimeMs = remainingTimeMs,
		roundDurationMs = roundDurationMs,
		currentStreak = currentStreak,
		bestStreak = bestStreak,
	)
}
