package com.adsamcik.tracker.game.goals

import com.adsamcik.tracker.game.goals.data.GoalListenable
import com.adsamcik.tracker.game.goals.data.abstraction.Goal
import com.adsamcik.tracker.tracker.data.session.TrackerSessionSnapshot
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [GoalListenable] which wraps a Goal and exposes reactive StateFlow state.
 */
@DisplayName("GoalListenable")
class GoalListenableTest {

	private lateinit var mockGoal: Goal
	private lateinit var listenable: GoalListenable

	// Track captured callbacks
	private var capturedOnValueChanged: ((Int) -> Unit)? = null
	private var capturedOnTargetChanged: ((Int) -> Unit)? = null

	@BeforeEach
	fun setUp() {
		mockGoal = mockk(relaxed = true) {
			every { value } returns 0
			every { target } returns 0
			every { isEnabled } returns false
			every { pointMultiplier } returns 0.01
			every { onValueChanged = any() } answers {
				capturedOnValueChanged = firstArg()
			}
			every { onTargetChanged = any() } answers {
				capturedOnTargetChanged = firstArg()
			}
		}
		listenable = GoalListenable(mockGoal)
	}

	@Nested
	@DisplayName("Initial state")
	inner class InitialState {

		@Test
		fun `value flow starts at zero`() {
			listenable.value.value shouldBe 0
		}

		@Test
		fun `target flow starts at zero`() {
			listenable.target.value shouldBe 0
		}

		@Test
		fun `registers callbacks on goal during init`() {
			verify { mockGoal.onTargetChanged = any() }
			verify { mockGoal.onValueChanged = any() }
		}
	}

	@Nested
	@DisplayName("Callback propagation")
	inner class CallbackPropagation {

		@Test
		fun `value callback updates flow`() {
			capturedOnValueChanged?.invoke(42)
			listenable.value.value shouldBe 42
		}

		@Test
		fun `target callback updates flow`() {
			capturedOnTargetChanged?.invoke(10_000)
			listenable.target.value shouldBe 10_000
		}
	}

	@Nested
	@DisplayName("Session updates")
	inner class SessionUpdates {

		@Test
		fun `presentation session update delegates to goal`() {
			val session = TrackerSessionSnapshot(
				id = 1L,
				start = 1000L,
				end = 2000L,
				isUserInitiated = true,
				collections = 1,
				distanceInM = 100f,
				distanceOnFootInM = 80f,
				distanceInVehicleInM = 0f,
				steps = 500
			)

			listenable.onSessionPresentationUpdated(session, isNewSession = true)

			verify { mockGoal.onSessionPresentationUpdated(session, true) }
		}

		@Test
		fun `value flow updates when goal value changes during session update`() {
			val session = TrackerSessionSnapshot(
				id = 3L,
				start = 1000L,
				end = 2000L,
				isUserInitiated = true,
				collections = 1,
				distanceInM = 0f,
				distanceOnFootInM = 0f,
				distanceInVehicleInM = 0f,
				steps = 200
			)

			// Simulate goal changing its value during the presentation update.
			every { mockGoal.value } returns 0 andThen 200

			listenable.onSessionPresentationUpdated(session, isNewSession = true)

			listenable.value.value shouldBe 200
		}

		@Test
		fun `presentation-only session update delegates exactly once`() {
			val session = TrackerSessionSnapshot(id = 4L, steps = 20_000)

			listenable.onSessionPresentationUpdated(session, isNewSession = true)

			verify(exactly = 1) { mockGoal.onSessionPresentationUpdated(session, true) }
		}

		@Test
		fun `presentation-only cumulative update delegates exactly once`() {
			listenable.onCumulativeStepsPresentationUpdated(20_000)

			verify(exactly = 1) { mockGoal.onCumulativeStepsPresentationUpdated(20_000) }
		}
	}
}
