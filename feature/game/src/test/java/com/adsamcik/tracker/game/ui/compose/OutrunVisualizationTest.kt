package com.adsamcik.tracker.game.ui.compose

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.adsamcik.tracker.game.minigame.MiniGameGoalProgress
import com.adsamcik.tracker.game.minigame.MiniGamePersonalBest
import com.adsamcik.tracker.game.minigame.MiniGamePhase
import com.adsamcik.tracker.game.minigame.MiniGameSignal
import com.adsamcik.tracker.game.minigame.MiniGameSignalQuality
import com.adsamcik.tracker.game.minigame.MiniGameSnapshot
import com.adsamcik.tracker.game.minigame.MiniGameVisualPayload
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import com.adsamcik.tracker.shared.utils.style.compose.LocalReducedMotion
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutrunVisualizationTest {

	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun outrunChase_exposesRaceMetricsAndGoalProgress() {
		setOutrunContent(
			outrunSnapshot(
				payload = MiniGameVisualPayload.Outrun(
					currentGapMeters = 42.0,
					warningThresholdMeters = 20.0,
					bestThisRunMeters = 60.0,
					personalBestMeters = 80.0,
				),
				goalProgress = MiniGameGoalProgress.Tracked(current = 42.0, target = 100.0),
			),
		)

		composeRule.onNodeWithContentDescription("42 metres ahead", substring = true)
			.assertIsDisplayed()
		composeRule.onNodeWithContentDescription("42 percent complete", substring = true)
			.assertIsDisplayed()
		composeRule.onNodeWithText("42 m lead", useUnmergedTree = true)
			.assertIsDisplayed()
		composeRule.onNodeWithText("42 / 100 m", useUnmergedTree = true)
			.assertIsDisplayed()
		composeRule.onNodeWithText("Personal best", useUnmergedTree = true)
			.assertIsDisplayed()
	}

	@Test
	fun outrunChase_warningMakesDangerUnmistakable() {
		setOutrunContent(
			outrunSnapshot(
				payload = MiniGameVisualPayload.Outrun(
					currentGapMeters = 6.0,
					warningThresholdMeters = 20.0,
					bestThisRunMeters = 40.0,
					personalBestMeters = null,
				),
				phase = MiniGamePhase.WARNING,
			),
		)

		composeRule.onNodeWithText("Ghost closing in", useUnmergedTree = true)
			.assertIsDisplayed()
		composeRule.onNodeWithContentDescription("Danger", substring = true)
			.assertIsDisplayed()
	}

	@Test
	fun outrunChase_closeButGrowingLeadDoesNotClaimGhostIsClosing() {
		setOutrunContent(
			outrunSnapshot(
				payload = MiniGameVisualPayload.Outrun(
					currentGapMeters = 6.0,
					warningThresholdMeters = 20.0,
					bestThisRunMeters = 6.0,
					personalBestMeters = null,
				),
				phase = MiniGamePhase.ACTIVE,
			),
		)

		composeRule.onNodeWithText("Build your lead", useUnmergedTree = true)
			.assertIsDisplayed()
		composeRule.onNodeWithText("Ghost closing in", useUnmergedTree = true)
			.assertDoesNotExist()
		composeRule.onNodeWithContentDescription("Danger", substring = true)
			.assertDoesNotExist()
	}

	@Test
	fun outrunChase_aheadOfPersonalBestUsesSteadyStateCopy() {
		setOutrunContent(
			outrunSnapshot(
				payload = MiniGameVisualPayload.Outrun(
					currentGapMeters = 30.0,
					warningThresholdMeters = 20.0,
					bestThisRunMeters = 90.0,
					personalBestMeters = 80.0,
				),
			),
		)

		composeRule.onNodeWithText("Ahead of your personal best", useUnmergedTree = true)
			.assertIsDisplayed()
		composeRule.onNodeWithText("New personal best", useUnmergedTree = true)
			.assertDoesNotExist()
	}

	@Test
	fun outrunChase_waitsForMeaningfulMovement() {
		setOutrunContent(
			outrunSnapshot(
				payload = MiniGameVisualPayload.Outrun(
					currentGapMeters = 0.0,
					warningThresholdMeters = 20.0,
					bestThisRunMeters = 0.0,
					personalBestMeters = null,
					hasRaceStarted = false,
				),
			),
		)

		composeRule.onNodeWithText("Move to start the race", useUnmergedTree = true)
			.assertIsDisplayed()
		composeRule.onNodeWithContentDescription(
			"Move more than two metres to start the chase",
			substring = true,
		).assertIsDisplayed()
		composeRule.onNodeWithText("Ghost closing in", useUnmergedTree = true)
			.assertDoesNotExist()
	}

	@Test
	fun outrunChase_caughtStateNeverLooksLikeAWin() {
		setOutrunContent(
			outrunSnapshot(
				payload = MiniGameVisualPayload.Outrun(
					currentGapMeters = -4.0,
					warningThresholdMeters = 20.0,
					bestThisRunMeters = 35.0,
					personalBestMeters = 50.0,
				),
				phase = MiniGamePhase.COMPLETED,
			),
		)

		composeRule.onNodeWithText("Caught by the ghost", useUnmergedTree = true)
			.assertIsDisplayed()
		composeRule.onNodeWithText("Ghost leads by 4 m", useUnmergedTree = true)
			.assertIsDisplayed()
		composeRule.onNodeWithContentDescription("ghost passed you by 4 metres", substring = true)
			.assertIsDisplayed()
		composeRule.onNodeWithText("You escaped!", useUnmergedTree = true)
			.assertDoesNotExist()
	}

	@Test
	fun outrunChase_goalReachedHasDistinctTriumphState() {
		setOutrunContent(
			outrunSnapshot(
				payload = MiniGameVisualPayload.Outrun(
					currentGapMeters = 28.0,
					warningThresholdMeters = 20.0,
					bestThisRunMeters = 100.0,
					personalBestMeters = 80.0,
				),
				phase = MiniGamePhase.COMPLETED,
				goalProgress = MiniGameGoalProgress.Tracked(current = 100.0, target = 100.0),
			),
		)

		composeRule.onNodeWithText("You escaped!", useUnmergedTree = true)
			.assertIsDisplayed()
		composeRule.onNodeWithText("Goal reached", useUnmergedTree = true)
			.assertIsDisplayed()
		composeRule.onNodeWithContentDescription("Goal reached", substring = true)
			.assertIsDisplayed()
		composeRule.onNodeWithText("Caught by the ghost", useUnmergedTree = true)
			.assertDoesNotExist()
	}

	private fun setOutrunContent(snapshot: MiniGameSnapshot) {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				CompositionLocalProvider(LocalReducedMotion provides true) {
					OutrunGapRail(snapshot = snapshot)
				}
			}
		}
	}

	private fun outrunSnapshot(
		payload: MiniGameVisualPayload.Outrun,
		phase: MiniGamePhase = MiniGamePhase.ACTIVE,
		goalProgress: MiniGameGoalProgress = MiniGameGoalProgress.NotConfigured,
	): MiniGameSnapshot = MiniGameSnapshot(
		phase = phase,
		signal = MiniGameSignal(MiniGameSignalQuality.GOOD, ageMs = 1_000L, isStale = false),
		elapsedActiveTimeMs = 0L,
		goalProgress = goalProgress,
		personalBest = MiniGamePersonalBest.compare(
			currentScore = payload.bestThisRunMeters,
			scoreBeforeRun = payload.personalBestMeters,
		),
		latestFeedback = null,
		visualPayload = payload,
	)
}
