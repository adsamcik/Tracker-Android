package com.adsamcik.tracker.game.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.minigame.MiniGameCompletionOutcome
import com.adsamcik.tracker.game.minigame.MiniGameDifficulty
import com.adsamcik.tracker.game.minigame.MiniGameGoalProgress
import com.adsamcik.tracker.game.minigame.MiniGameGoalUnit
import com.adsamcik.tracker.game.minigame.MiniGamePersonalBest
import com.adsamcik.tracker.game.minigame.MiniGamePhase
import com.adsamcik.tracker.game.minigame.MiniGameScoreUnit
import com.adsamcik.tracker.game.minigame.MiniGameSignal
import com.adsamcik.tracker.game.minigame.MiniGameSignalQuality
import com.adsamcik.tracker.game.minigame.MiniGameSnapshot
import com.adsamcik.tracker.game.minigame.MiniGameVisualPayload
import com.adsamcik.tracker.game.minigame.OutrunConfiguration
import com.adsamcik.tracker.game.minigame.OutrunGoal
import com.adsamcik.tracker.game.minigame.TerritoryRelativeCell
import com.adsamcik.tracker.game.minigame.ZenPaceZone
import com.adsamcik.tracker.game.session.GameSessionFailureReason
import com.adsamcik.tracker.game.session.GameSessionId
import com.adsamcik.tracker.game.session.GameSessionResult
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MiniGameSessionUiTest {

	@get:Rule
	val composeRule = createComposeRule()

	// --- Visualization semantics -----------------------------------------------

	// --- Completion PB variants -------------------------------------------------

	@Test
	fun completion_firstRunShowsPointsSeparateFromScoreAndGoalReached() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false, reducedMotion = true) {
				MiniGameCompletionPanel(
					result = outrunResult(
						finalScore = 87.0,
						points = 120,
						outcome = MiniGameCompletionOutcome.FirstRun,
					),
					scoreUnit = MiniGameScoreUnit.DISTANCE_METERS,
					onPlayAgain = {},
					onChangeSetup = {},
					onDone = {},
				)
			}
		}

		composeRule.onNodeWithText("First run logged!", useUnmergedTree = true).assertIsDisplayed()
		// Score and points are rendered as distinct lines (never conflated).
		composeRule.onNodeWithText("Result: 87 m", useUnmergedTree = true).assertIsDisplayed()
		composeRule.onNodeWithText("+120 points", useUnmergedTree = true).assertIsDisplayed()
		composeRule.onNodeWithText("Goal reached: 50 m lead", useUnmergedTree = true)
			.assertIsDisplayed()
	}

	@Test
	fun completion_newPersonalBestVariant() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false, reducedMotion = true) {
				MiniGameCompletionPanel(
					result = outrunResult(
						finalScore = 92.0,
						points = 130,
						outcome = MiniGameCompletionOutcome.PersonalBest(improvement = 12.0),
					),
					scoreUnit = MiniGameScoreUnit.DISTANCE_METERS,
					onPlayAgain = {},
					onChangeSetup = {},
					onDone = {},
				)
			}
		}

		composeRule.onNodeWithText("New personal best!", useUnmergedTree = true).assertIsDisplayed()
		composeRule.onNodeWithText("12 m ahead of your previous best", useUnmergedTree = true)
			.assertIsDisplayed()
	}

	@Test
	fun completion_tiedBestVariant() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false, reducedMotion = true) {
				MiniGameCompletionPanel(
					result = outrunResult(
						finalScore = 50.0,
						points = 60,
						outcome = MiniGameCompletionOutcome.TiedBest,
					),
					scoreUnit = MiniGameScoreUnit.DISTANCE_METERS,
					onPlayAgain = {},
					onChangeSetup = {},
					onDone = {},
				)
			}
		}

		composeRule.onNodeWithText("Matched your best", useUnmergedTree = true).assertIsDisplayed()
	}

	@Test
	fun completion_belowBestVariantShowsDeficitAndGoalIncomplete() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false, reducedMotion = true) {
				MiniGameCompletionPanel(
					result = outrunResult(
						finalScore = 30.0,
						points = 32,
						outcome = MiniGameCompletionOutcome.BelowBest(distanceFromBest = 8.0),
					),
					scoreUnit = MiniGameScoreUnit.DISTANCE_METERS,
					onPlayAgain = {},
					onChangeSetup = {},
					onDone = {},
				)
			}
		}

		composeRule.onNodeWithText("Good run", useUnmergedTree = true).assertIsDisplayed()
		composeRule.onNodeWithText("8 m short of your best", useUnmergedTree = true)
			.assertIsDisplayed()
		// Goal not reached is surfaced honestly even on a real, scored run.
		composeRule.onNodeWithText("Goal not reached: 50 m lead", useUnmergedTree = true)
			.assertIsDisplayed()
	}

	@Test
	fun completion_noParticipationShowsNeutralTitleAndNoPoints() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false, reducedMotion = true) {
				MiniGameCompletionPanel(
					result = outrunResult(
						finalScore = 0.0,
						points = 0,
						outcome = MiniGameCompletionOutcome.BelowBest(distanceFromBest = 8.0),
					),
					scoreUnit = MiniGameScoreUnit.DISTANCE_METERS,
					onPlayAgain = {},
					onChangeSetup = {},
					onDone = {},
				)
			}
		}

		// A run with no real participation must never be praised as "Good run".
		composeRule.onNodeWithText("Session ended", useUnmergedTree = true).assertIsDisplayed()
		composeRule.onNodeWithText("Good run", useUnmergedTree = true).assertDoesNotExist()
		composeRule.onNodeWithText("No points this run", useUnmergedTree = true).assertIsDisplayed()
	}

	@Test
	fun completion_actionButtonsMeetTouchTarget() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false, reducedMotion = true) {
				MiniGameCompletionPanel(
					result = outrunResult(
						finalScore = 55.0,
						points = 70,
						outcome = MiniGameCompletionOutcome.FirstRun,
					),
					scoreUnit = MiniGameScoreUnit.DISTANCE_METERS,
					onPlayAgain = {},
					onChangeSetup = {},
					onDone = {},
				)
			}
		}

		composeRule.onNodeWithText("Play again").assertHeightIsAtLeast(48.dp)
		composeRule.onNodeWithText("Change setup").assertHeightIsAtLeast(48.dp)
		composeRule.onNodeWithText("Done").assertHeightIsAtLeast(48.dp)
	}

	// --- Active session ---------------------------------------------------------

	@Test
	fun activeSession_showsControlsBackgroundNoteAndVisualization() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				ActiveSessionPanel(
					configuration = OutrunConfiguration(OutrunGoal.METERS_50, MiniGameDifficulty.NORMAL),
					snapshot = outrunSnapshot(),
					paused = false,
					onPause = {},
					onResume = {},
					onFinish = {},
				)
			}
		}

		// Distinct visualization for the active game.
		composeRule.onNodeWithContentDescription("42 metres ahead", substring = true)
			.assertIsDisplayed()
		// Back leaving the service running is surfaced clearly (may require
		// scrolling past a rich game visualization, same as other supplementary
		// status text below the fold).
		composeRule.onNodeWithText("keeps going", substring = true, useUnmergedTree = true)
			.performScrollTo()
			.assertIsDisplayed()
		// Pause and Finish controls both meet the 48dp minimum touch target.
		composeRule.onNodeWithText("Pause").assertHeightIsAtLeast(48.dp)
		composeRule.onNodeWithText("Finish").assertHeightIsAtLeast(48.dp)
	}

	@Test
	fun pausedSession_offersResumeAndFinish() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				ActiveSessionPanel(
					configuration = OutrunConfiguration(OutrunGoal.METERS_50, MiniGameDifficulty.NORMAL),
					snapshot = outrunSnapshot(phase = MiniGamePhase.PAUSED),
					paused = true,
					onPause = {},
					onResume = {},
					onFinish = {},
				)
			}
		}

		composeRule.onNodeWithText("Paused", useUnmergedTree = true).assertIsDisplayed()
		composeRule.onNodeWithText("Resume").assertHeightIsAtLeast(48.dp)
		composeRule.onNodeWithText("Finish").assertHeightIsAtLeast(48.dp)
	}

	// --- Failure ----------------------------------------------------------------

	@Test
	fun failurePanel_offersRecoveryNotSuccessShapedZeroScore() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				FailurePanel(
					reason = GameSessionFailureReason.LOCATION_UNAVAILABLE,
					onTryAgain = {},
					onBack = {},
				)
			}
		}

		composeRule.onNodeWithText("Run didn't finish", useUnmergedTree = true).assertIsDisplayed()
		composeRule.onNodeWithText("Try again").assertIsDisplayed()
		composeRule.onNodeWithText("Close").assertIsDisplayed()
		// No success-shaped completion strings leak into a failure.
		composeRule.onNodeWithText("Result:", substring = true, useUnmergedTree = true)
			.assertDoesNotExist()
	}

	// --- Large font & reduced motion -------------------------------------------

	@Test
	fun setupPanel_isScrollableAtLargeFontScale() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				val base = LocalDensity.current
				CompositionLocalProvider(
					LocalDensity provides Density(density = base.density, fontScale = 2.0f),
				) {
					Box(Modifier.height(240.dp)) {
						MiniGameSetupPanel(
							state = outrunSetupState(),
							descriptionRes = R.string.minigame_outrun_goal_line,
							onSelectGoal = {},
							onSelectDifficulty = {},
							onSetRemember = {},
							onStart = {},
						)
					}
				}
			}
		}

		// Despite doubled font size and a constrained height, the primary action
		// remains reachable by scrolling — no fixed-height hero clipping it away.
		composeRule.onNodeWithText("Start run").performScrollTo().assertIsDisplayed()
	}

	// --- Fixtures ---------------------------------------------------------------

	private fun outrunResult(
		finalScore: Double,
		points: Int,
		outcome: MiniGameCompletionOutcome,
	): GameSessionResult = GameSessionResult(
		sessionId = GameSessionId("session-1"),
		configuration = OutrunConfiguration(OutrunGoal.METERS_50, MiniGameDifficulty.NORMAL),
		finalScore = finalScore,
		pointsAwarded = points,
		completedAtMs = 1_000L,
		completionOutcome = outcome,
	)

	private fun outrunSnapshot(
		phase: MiniGamePhase = MiniGamePhase.ACTIVE,
	): MiniGameSnapshot = MiniGameSnapshot(
		phase = phase,
		signal = MiniGameSignal(MiniGameSignalQuality.GOOD, ageMs = 1_000L, isStale = false),
		elapsedActiveTimeMs = 65_000L,
		goalProgress = MiniGameGoalProgress.Tracked(current = 30.0, target = 50.0),
		personalBest = MiniGamePersonalBest.compare(30.0, scoreBeforeRun = null),
		latestFeedback = null,
		visualPayload = MiniGameVisualPayload.Outrun(
			currentGapMeters = 42.0,
			warningThresholdMeters = 15.0,
			bestThisRunMeters = 60.0,
			personalBestMeters = 80.0,
		),
	)

	private fun outrunSetupState(): MiniGameSessionUiState.Setup = MiniGameSessionUiState.Setup(
		options = MiniGameSetupOptions(
			gameId = "outrun",
			goalUnit = MiniGameGoalUnit.METERS,
			goalValues = listOf(25, 50, 100),
			difficulties = listOf(
				MiniGameDifficulty.EASY,
				MiniGameDifficulty.NORMAL,
				MiniGameDifficulty.HARD,
			),
		),
		selection = MiniGameSetupSelection(goalValue = 50, difficulty = MiniGameDifficulty.NORMAL),
		rememberSetup = false,
	)
}
