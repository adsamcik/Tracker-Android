package com.adsamcik.tracker.game.ui.compose

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
import com.adsamcik.tracker.game.minigame.ZenPaceZone
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for Zen Walk's live visualization only. Owned exclusively by the Zen
 * Walk work — no other game's tests live here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ZenWalkVisualizationTest {

	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun zenFlow_describesPaceTargetAndAccumulatedFlow() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				ZenPaceGauge(
					snapshot = zenSnapshot(
						MiniGameVisualPayload.ZenWalk(
							calibrationProgress = 1.0,
							currentSmoothedPaceMetersPerSecond = 1.2,
							targetPaceZone = ZenPaceZone(1.0, 1.5),
							timeInZoneMs = 5_000L,
						),
					),
				)
			}
		}

		composeRule.onNodeWithContentDescription("Zen pace flow", substring = true)
			.assertIsDisplayed()
		composeRule.onNodeWithContentDescription("Target 3.6", substring = true)
			.assertIsDisplayed()
		composeRule.onNodeWithText("Settled in your rhythm")
			.assertIsDisplayed()
		composeRule.onNodeWithText("Flow 0:05")
			.assertIsDisplayed()
	}

	@Test
	fun zenFlow_usesGentleDriftLanguageOutsideTheZone() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				ZenPaceGauge(
					snapshot = zenSnapshot(
						MiniGameVisualPayload.ZenWalk(
							calibrationProgress = 1.0,
							currentSmoothedPaceMetersPerSecond = 0.7,
							targetPaceZone = ZenPaceZone(1.0, 1.5),
							timeInZoneMs = 65_000L,
						),
					),
				)
			}
		}

		composeRule.onNodeWithText("Drifting gently slower")
			.assertIsDisplayed()
		composeRule.onNodeWithText("Flow 1:05")
			.assertIsDisplayed()
	}

	@Test
	fun zenCalibration_exposesGuidedSettlingProgress() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				ZenPaceGauge(
					snapshot = zenSnapshot(
						MiniGameVisualPayload.ZenWalk(
							calibrationProgress = 0.4,
							currentSmoothedPaceMetersPerSecond = null,
							targetPaceZone = null,
							timeInZoneMs = 0L,
						),
					),
				)
			}
		}

		composeRule.onNodeWithContentDescription("Pace calibration", substring = true)
			.assertIsDisplayed()
		composeRule.onNodeWithContentDescription("40 percent settled", substring = true)
			.assertIsDisplayed()
	}

	@Test
	fun reducedMotion_visualizationRendersStaticallyWithoutHanging() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false, reducedMotion = true) {
				ZenPaceGauge(
					snapshot = zenSnapshot(
						MiniGameVisualPayload.ZenWalk(
							calibrationProgress = 1.0,
							currentSmoothedPaceMetersPerSecond = 1.2,
							targetPaceZone = ZenPaceZone(1.0, 1.5),
							timeInZoneMs = 5_000L,
						),
					),
				)
			}
		}

		// With reduced motion the composition idles immediately (no animation
		// keeps the frame clock busy) and still exposes its accessible state.
		composeRule.onNodeWithContentDescription("Zen pace flow", substring = true)
			.assertIsDisplayed()
	}

	@Test
	fun flowDuration_formatsMinutesAndSeconds() {
		assertEquals("0:05", formatFlowDuration(5_000L))
		assertEquals("12:34", formatFlowDuration(754_000L))
	}

	private fun zenSnapshot(
		payload: MiniGameVisualPayload.ZenWalk,
		phase: MiniGamePhase = MiniGamePhase.ACTIVE,
	): MiniGameSnapshot = MiniGameSnapshot(
		phase = phase,
		signal = MiniGameSignal(MiniGameSignalQuality.GOOD, ageMs = 1_000L, isStale = false),
		elapsedActiveTimeMs = 0L,
		goalProgress = MiniGameGoalProgress.NotConfigured,
		personalBest = MiniGamePersonalBest.compare(
			payload.timeInZoneMs / MILLIS_PER_SECOND,
			scoreBeforeRun = null,
		),
		latestFeedback = null,
		visualPayload = payload,
	)

	private companion object {
		const val MILLIS_PER_SECOND = 1_000.0
	}
}
