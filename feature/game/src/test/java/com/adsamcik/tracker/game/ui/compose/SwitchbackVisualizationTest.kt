package com.adsamcik.tracker.game.ui.compose

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.adsamcik.tracker.game.minigame.switchback.SwitchbackTurnDirection
import com.adsamcik.tracker.game.minigame.SwitchbackVisualPayload
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import com.adsamcik.tracker.shared.utils.style.compose.LocalReducedMotion
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SwitchbackVisualizationTest {

	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun waitingState_explainsHowToSetTheFirstTrailLeg() {
		setContent(
			payload(
				turnsCompleted = 0,
				hasFirstLeg = false,
				expectedTurn = null,
			),
		)

		composeRule.onNodeWithText(
			"Walk 32 m to set your first trail leg",
			useUnmergedTree = true,
		)
			.assertIsDisplayed()
		composeRule.onNodeWithContentDescription(
			"Waiting for the first trail leg. 0 of 6 turns complete.",
			substring = true,
		).assertIsDisplayed()
	}

	@Test
	fun activeState_exposesProgressComboAndNextDirectionAccessibly() {
		setContent(
			payload(
				turnsCompleted = 3,
				currentCombo = 3,
				bestCombo = 3,
				expectedTurn = SwitchbackTurnDirection.LEFT,
				recentTurns = listOf(
					SwitchbackTurnDirection.RIGHT,
					SwitchbackTurnDirection.LEFT,
					SwitchbackTurnDirection.RIGHT,
				),
			),
		)

		composeRule.onNodeWithText("3 / 6 turns", useUnmergedTree = true).assertIsDisplayed()
		composeRule.onNodeWithText("Turn left next", useUnmergedTree = true).assertIsDisplayed()
		composeRule.onNodeWithText("3× flow", useUnmergedTree = true).assertIsDisplayed()
		composeRule.onNodeWithContentDescription(
			"3 of 6 turns complete. Turn left next. Current flow combo 3.",
			substring = true,
		).assertIsDisplayed()
	}

	@Test
	fun completedState_isCelebratoryAndAnnouncesGoalCompletion() {
		setContent(
			payload(
				turnsCompleted = 6,
				currentCombo = 6,
				bestCombo = 6,
				expectedTurn = SwitchbackTurnDirection.LEFT,
				goalReached = true,
				recentTurns = List(6) { index ->
					if (index % 2 == 0) SwitchbackTurnDirection.RIGHT else SwitchbackTurnDirection.LEFT
				},
			),
		)

		composeRule.onNodeWithText("Trail complete!", useUnmergedTree = true).assertIsDisplayed()
		composeRule.onNodeWithText("6 / 6 turns", useUnmergedTree = true).assertIsDisplayed()
		composeRule.onNodeWithContentDescription("Goal complete.", substring = true)
			.assertIsDisplayed()
	}

	private fun setContent(payload: SwitchbackVisualPayload) {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				CompositionLocalProvider(LocalReducedMotion provides true) {
					SwitchbackComposable(payload = payload)
				}
			}
		}
	}

	private fun payload(
		turnsCompleted: Int,
		targetTurns: Int = 6,
		currentCombo: Int = 0,
		bestCombo: Int = 0,
		expectedTurn: SwitchbackTurnDirection?,
		recentTurns: List<SwitchbackTurnDirection> = emptyList(),
		hasFirstLeg: Boolean = true,
		goalReached: Boolean = false,
	) = SwitchbackVisualPayload(
		turnsCompleted = turnsCompleted,
		targetTurns = targetTurns,
		currentCombo = currentCombo,
		bestCombo = bestCombo,
		expectedTurn = expectedTurn,
		recentTurns = recentTurns,
		hasFirstLeg = hasFirstLeg,
		minimumLegMeters = 32.0,
		goalReached = goalReached,
	)
}
