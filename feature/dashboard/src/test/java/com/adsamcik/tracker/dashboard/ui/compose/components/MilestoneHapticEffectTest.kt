package com.adsamcik.tracker.dashboard.ui.compose.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.test.junit4.createComposeRule
import com.adsamcik.tracker.tracker.data.session.TrackerSessionSnapshot
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MilestoneHapticEffectTest {

	@get:Rule
	val composeRule = createComposeRule()

	private val noOpHaptics = object : HapticFeedback {
		override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
			// no-op for testing
		}
	}

	@Test
	fun withNullSession_composesWithoutCrash() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				MilestoneHapticEffect(
					sessionData = null,
					isTracking = false,
					haptics = noOpHaptics,
				)
			}
		}

		// Pure side-effect composable — verify it composes without crash
	}

	@Test
	fun withValidSession_composesWithoutCrash() {
		val now = System.currentTimeMillis()

		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				MilestoneHapticEffect(
					sessionData = TrackerSessionSnapshot(
						id = 1L,
						start = now - 120_000L,
						end = now,
						distanceInM = 500f,
						steps = 800,
					),
					isTracking = true,
					haptics = noOpHaptics,
				)
			}
		}

		// Verify composable runs without crash when tracking is active
	}

	@Test
	fun withNotTracking_composesWithoutCrash() {
		val now = System.currentTimeMillis()

		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				MilestoneHapticEffect(
					sessionData = TrackerSessionSnapshot(
						id = 1L,
						start = now - 60_000L,
						end = now,
						distanceInM = 2000f,
						steps = 3000,
					),
					isTracking = false,
					haptics = noOpHaptics,
				)
			}
		}

		// When not tracking, the LaunchedEffect resets state — should not crash
	}

	@Test
	fun disabledMilestones_preserveHighWaterWithoutReplayingFeedback() {
		val now = System.currentTimeMillis()
		val milestonesEnabled = mutableStateOf(false)
		val session = mutableStateOf(
			TrackerSessionSnapshot(
				id = 1L,
				start = now - 120_000L,
				end = now,
				distanceInM = 1500f,
				steps = 1500,
			),
		)
		val feedback = mutableListOf<HapticFeedbackType>()
		val recordingHaptics = object : HapticFeedback {
			override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
				feedback += hapticFeedbackType
			}
		}

		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				MilestoneHapticEffect(
					sessionData = session.value,
					isTracking = true,
					haptics = recordingHaptics,
					milestonesEnabled = milestonesEnabled.value,
				)
			}
		}
		composeRule.runOnIdle {
			session.value = session.value.copy(distanceInM = 2500f, steps = 2500)
		}
		composeRule.waitForIdle()
		composeRule.runOnIdle {
			milestonesEnabled.value = true
		}
		composeRule.waitForIdle()

		feedback.shouldBeEmpty()
	}

	@Test
	fun rawStepsWithoutQualification_doNotTriggerFeedback() {
		val now = System.currentTimeMillis()
		val session = mutableStateOf(
			TrackerSessionSnapshot(
				id = 1L,
				start = now - 120_000L,
				distanceInM = 1500f,
				steps = 1500,
			),
		)
		val feedback = mutableListOf<HapticFeedbackType>()
		val recordingHaptics = object : HapticFeedback {
			override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
				feedback += hapticFeedbackType
			}
		}

		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				MilestoneHapticEffect(
					sessionData = session.value,
					isTracking = true,
					haptics = recordingHaptics,
					qualifiedSteps = null,
				)
			}
		}
		composeRule.runOnIdle {
			session.value = session.value.copy(steps = 2500)
		}
		composeRule.waitForIdle()

		feedback.shouldBeEmpty()
	}

	@Test
	fun completeQualifiedSteps_triggerStepFeedbackAfterCrossing() {
		val now = System.currentTimeMillis()
		val qualifiedSteps = mutableStateOf<Long?>(1500L)
		val feedback = mutableListOf<HapticFeedbackType>()
		val recordingHaptics = object : HapticFeedback {
			override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
				feedback += hapticFeedbackType
			}
		}

		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				MilestoneHapticEffect(
					sessionData = TrackerSessionSnapshot(
						id = 1L,
						start = now - 120_000L,
						distanceInM = 1500f,
						steps = 0,
					),
					isTracking = true,
					haptics = recordingHaptics,
					qualifiedSteps = qualifiedSteps.value,
				)
			}
		}
		composeRule.runOnIdle {
			qualifiedSteps.value = 2500L
		}
		composeRule.waitForIdle()

		feedback.shouldContainExactly(HapticFeedbackType.TextHandleMove)
	}

	@Test
	fun qualifiedStepCorrectionDoesNotReplayFeedback() {
		val now = System.currentTimeMillis()
		val qualifiedSteps = mutableStateOf<Long?>(1500L)
		val feedback = mutableListOf<HapticFeedbackType>()
		val recordingHaptics = object : HapticFeedback {
			override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
				feedback += hapticFeedbackType
			}
		}

		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				MilestoneHapticEffect(
					sessionData = TrackerSessionSnapshot(
						id = 1L,
						start = now - 120_000L,
						distanceInM = 500f,
					),
					isTracking = true,
					haptics = recordingHaptics,
					qualifiedSteps = qualifiedSteps.value,
				)
			}
		}
		listOf<Long?>(2500L, null, 1500L, 2500L).forEach { correctedValue ->
			composeRule.runOnIdle { qualifiedSteps.value = correctedValue }
			composeRule.waitForIdle()
		}

		feedback.shouldContainExactly(HapticFeedbackType.TextHandleMove)
	}
}
