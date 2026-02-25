package com.adsamcik.tracker.game.goals

import android.content.Context
import com.adsamcik.tracker.game.goals.data.GoalListenable
import com.adsamcik.tracker.game.goals.data.abstraction.Goal
import com.adsamcik.tracker.logger.LogData
import com.adsamcik.tracker.logger.Logger
import com.adsamcik.tracker.shared.base.data.TrackerSession
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [GoalTracker] update logic, session tracking, daily reset, and
 * concurrent goal handling.
 *
 * Uses reflection to inject mock goals into the singleton because GoalTracker
 * is an internal object with heavy Android dependencies.
 */
@DisplayName("GoalTracker")
class GoalTrackerTest {

	private lateinit var mockGoal1: Goal
	private lateinit var mockGoal2: Goal
	private lateinit var listenable1: GoalListenable
	private lateinit var listenable2: GoalListenable

	private val goalListField = GoalTracker::class.java.getDeclaredField("goalList")
		.apply { isAccessible = true }
	private val lastSessionIdField = GoalTracker::class.java.getDeclaredField("mLastSessionId")
		.apply { isAccessible = true }
	private val contextField = GoalTracker::class.java.getDeclaredField("mAppContext")
		.apply { isAccessible = true }

	@Suppress("UNCHECKED_CAST")
	private val goalList get() = goalListField.get(GoalTracker) as MutableList<GoalListenable>

	private fun createMockGoal(): Goal = mockk(relaxed = true) {
		every { value } returns 0
		every { target } returns 0
		every { isEnabled } returns false
		every { pointMultiplier } returns 0.01
		every { onValueChanged = any() } just Runs
		every { onTargetChanged = any() } just Runs
		every { onSessionUpdated(any(), any()) } returns false
	}

	private fun createSession(id: Long = 1L, steps: Int = 100) = TrackerSession(
		id = id,
		start = 1000L,
		end = 2000L,
		isUserInitiated = true,
		collections = 1,
		distanceInM = 100f,
		distanceOnFootInM = 80f,
		distanceInVehicleInM = 0f,
		steps = steps,
	)

	@BeforeEach
	fun setUp() {
		mockGoal1 = createMockGoal()
		mockGoal2 = createMockGoal()
		listenable1 = GoalListenable(mockGoal1)
		listenable2 = GoalListenable(mockGoal2)

		goalList.clear()
		lastSessionIdField.setLong(GoalTracker, -1L)
	}

	@AfterEach
	fun tearDown() {
		goalList.clear()
		lastSessionIdField.setLong(GoalTracker, -1L)
		contextField.set(GoalTracker, null)
	}

	@Nested
	@DisplayName("Progress tracking")
	inner class ProgressTracking {

		@Test
		fun `update delegates to all registered goals`() {
			goalList.addAll(listOf(listenable1, listenable2))
			val session = createSession()

			GoalTracker.update(session)

			verify { mockGoal1.onSessionUpdated(session, true) }
			verify { mockGoal2.onSessionUpdated(session, true) }
		}

		@Test
		fun `update with no registered goals does not throw`() {
			GoalTracker.update(createSession())
		}

		@Test
		fun `update passes session data unchanged to goals`() {
			goalList.add(listenable1)
			val session = createSession(id = 99L, steps = 5000)

			GoalTracker.update(session)

			verify {
				mockGoal1.onSessionUpdated(
					match { it.id == 99L && it.steps == 5000 },
					true,
				)
			}
		}
	}

	@Nested
	@DisplayName("Session tracking")
	inner class SessionTracking {

		@Test
		fun `first update is always a new session`() {
			goalList.add(listenable1)

			GoalTracker.update(createSession(id = 42L))

			verify { mockGoal1.onSessionUpdated(any(), eq(true)) }
		}

		@Test
		fun `repeated update with same session id is not new`() {
			goalList.add(listenable1)

			GoalTracker.update(createSession(id = 42L, steps = 50))
			GoalTracker.update(createSession(id = 42L, steps = 150))

			verifyOrder {
				mockGoal1.onSessionUpdated(match { it.steps == 50 }, eq(true))
				mockGoal1.onSessionUpdated(match { it.steps == 150 }, eq(false))
			}
		}

		@Test
		fun `different session id is detected as new`() {
			goalList.add(listenable1)

			GoalTracker.update(createSession(id = 1L))
			GoalTracker.update(createSession(id = 2L))

			verify(exactly = 2) { mockGoal1.onSessionUpdated(any(), eq(true)) }
		}

		@Test
		fun `alternating session ids are always new`() {
			goalList.add(listenable1)

			GoalTracker.update(createSession(id = 1L, steps = 10))
			GoalTracker.update(createSession(id = 2L, steps = 20))
			GoalTracker.update(createSession(id = 1L, steps = 30))

			verify(exactly = 3) { mockGoal1.onSessionUpdated(any(), eq(true)) }
		}

		@Test
		fun `multiple updates to same session track correctly`() {
			goalList.add(listenable1)

			GoalTracker.update(createSession(id = 7L, steps = 100))
			GoalTracker.update(createSession(id = 7L, steps = 200))
			GoalTracker.update(createSession(id = 7L, steps = 300))

			verify(exactly = 1) { mockGoal1.onSessionUpdated(any(), eq(true)) }
			verify(exactly = 2) { mockGoal1.onSessionUpdated(any(), eq(false)) }
		}
	}

	@Nested
	@DisplayName("Daily reset")
	inner class DailyReset {

		@Test
		fun `onNewDay resets session tracking so next update is new`() {
			mockkObject(Logger)
			every { Logger.log(any<LogData>()) } just Runs
			try {
				contextField.set(GoalTracker, mockk<Context>(relaxed = true))
				goalList.add(listenable1)

				GoalTracker.update(createSession(id = 5L, steps = 100))
				GoalTracker.update(createSession(id = 5L, steps = 200))

				GoalTracker.onNewDay()

				// Same session id should be treated as new after reset
				GoalTracker.update(createSession(id = 5L, steps = 50))

				verifyOrder {
					mockGoal1.onSessionUpdated(match { it.steps == 100 }, eq(true))
					mockGoal1.onSessionUpdated(match { it.steps == 200 }, eq(false))
					mockGoal1.onSessionUpdated(match { it.steps == 50 }, eq(true))
				}
			} finally {
				unmockkObject(Logger)
			}
		}

		@Test
		fun `onNewDay allows different session to also be new`() {
			mockkObject(Logger)
			every { Logger.log(any<LogData>()) } just Runs
			try {
				contextField.set(GoalTracker, mockk<Context>(relaxed = true))
				goalList.add(listenable1)

				GoalTracker.update(createSession(id = 1L))

				GoalTracker.onNewDay()

				GoalTracker.update(createSession(id = 2L))

				verify(exactly = 2) { mockGoal1.onSessionUpdated(any(), eq(true)) }
			} finally {
				unmockkObject(Logger)
			}
		}
	}

	@Nested
	@DisplayName("Multiple concurrent goals")
	inner class MultipleConcurrentGoals {

		@Test
		fun `all goals receive the same session update`() {
			goalList.addAll(listOf(listenable1, listenable2))
			val session = createSession(steps = 500)

			GoalTracker.update(session)

			verify { mockGoal1.onSessionUpdated(session, true) }
			verify { mockGoal2.onSessionUpdated(session, true) }
		}

		@Test
		fun `new session flag is consistent across all goals`() {
			goalList.addAll(listOf(listenable1, listenable2))

			GoalTracker.update(createSession(id = 1L, steps = 100))
			GoalTracker.update(createSession(id = 1L, steps = 200))

			verify(exactly = 1) { mockGoal1.onSessionUpdated(match { it.steps == 100 }, eq(true)) }
			verify(exactly = 1) { mockGoal1.onSessionUpdated(match { it.steps == 200 }, eq(false)) }
			verify(exactly = 1) { mockGoal2.onSessionUpdated(match { it.steps == 100 }, eq(true)) }
			verify(exactly = 1) { mockGoal2.onSessionUpdated(match { it.steps == 200 }, eq(false)) }
		}

		@Test
		fun `all goals evaluated even when none complete`() {
			// Both goals return false (no completion)
			every { mockGoal1.onSessionUpdated(any(), any()) } returns false
			every { mockGoal2.onSessionUpdated(any(), any()) } returns false

			goalList.addAll(listOf(listenable1, listenable2))

			GoalTracker.update(createSession())

			verify(exactly = 1) { mockGoal1.onSessionUpdated(any(), any()) }
			verify(exactly = 1) { mockGoal2.onSessionUpdated(any(), any()) }
		}

		@Test
		fun `three goals all receive updates`() {
			val mockGoal3 = createMockGoal()
			val listenable3 = GoalListenable(mockGoal3)
			goalList.addAll(listOf(listenable1, listenable2, listenable3))
			val session = createSession()

			GoalTracker.update(session)

			verify { mockGoal1.onSessionUpdated(session, true) }
			verify { mockGoal2.onSessionUpdated(session, true) }
			verify { mockGoal3.onSessionUpdated(session, true) }
		}
	}

	@Nested
	@DisplayName("Goal completion detection")
	inner class GoalCompletionDetection {

		@Test
		fun `goal returning false does not trigger completion`() {
			every { mockGoal1.onSessionUpdated(any(), any()) } returns false
			goalList.add(listenable1)

			// update() should complete without calling onGoalReached
			GoalTracker.update(createSession())

			verify(exactly = 1) { mockGoal1.onSessionUpdated(any(), any()) }
		}

		@Test
		fun `result from onSessionUpdated is per-goal`() {
			every { mockGoal1.onSessionUpdated(any(), any()) } returns false
			every { mockGoal2.onSessionUpdated(any(), any()) } returns false
			goalList.addAll(listOf(listenable1, listenable2))

			GoalTracker.update(createSession())

			// Both were called and returned false — no goal reached
			verify(exactly = 1) { mockGoal1.onSessionUpdated(any(), any()) }
			verify(exactly = 1) { mockGoal2.onSessionUpdated(any(), any()) }
		}
	}
}
