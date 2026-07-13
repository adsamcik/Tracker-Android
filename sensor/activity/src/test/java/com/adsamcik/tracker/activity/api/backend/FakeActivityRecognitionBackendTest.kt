package com.adsamcik.tracker.activity.api.backend

import app.cash.turbine.test
import com.adsamcik.tracker.activity.ActivityTransitionType
import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.testing.fake.FakeActivityRecognitionBackend
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("FakeActivityRecognitionBackend")
class FakeActivityRecognitionBackendTest {

	private lateinit var backend: FakeActivityRecognitionBackend

	@BeforeEach
	fun setUp() {
		backend = FakeActivityRecognitionBackend()
	}

	@Nested
	@DisplayName("initial state")
	inner class InitialState {

		@Test
		fun `name is Fake`() {
			backend.name shouldBe "Fake"
		}

		@Test
		fun `isAvailable defaults to true`() {
			backend.isAvailable shouldBe true
		}

		@Test
		fun `isRunning defaults to false`() {
			backend.isRunning shouldBe false
		}

		@Test
		fun `lastConfig defaults to null`() {
			backend.lastConfig shouldBe null
		}
	}

	@Nested
	@DisplayName("startUpdates / stopUpdates")
	inner class Lifecycle {

		@Test
		fun `startUpdates sets isRunning to true`() = runTest {
			backend.startUpdates(RecognitionConfig(intervalSeconds = 10))

			backend.isRunning shouldBe true
			backend.startCount shouldBe 1
		}

		@Test
		fun `stopUpdates sets isRunning to false`() = runTest {
			backend.startUpdates(RecognitionConfig(intervalSeconds = 10))
			backend.stopUpdates()

			backend.isRunning shouldBe false
			backend.stopCount shouldBe 1
		}

		@Test
		fun `startUpdates stores config`() = runTest {
			val config = RecognitionConfig(intervalSeconds = 5)
			backend.startUpdates(config)

			backend.lastConfig shouldBe config
		}

		@Test
		fun `startUpdates returns false when unavailable`() = runTest {
			val unavailable = FakeActivityRecognitionBackend(isAvailable = false)

			val result = unavailable.startUpdates(RecognitionConfig(intervalSeconds = 10))

			result shouldBe false
			unavailable.isRunning shouldBe false
		}
	}

	@Nested
	@DisplayName("flow emissions")
	inner class Flows {

		@Test
		fun `emitUpdate delivers to activityUpdates flow`() = runTest {
			val update = ActivityUpdate(
				activity = RecognizedActivity(DetectedActivityType.WALKING, 85),
				elapsedTimeMillis = 1000L,
			)

			// Use Turbine for reliable SharedFlow testing
			backend.activityUpdates.test {
				backend.emitUpdate(update)
				awaitItem() shouldBe update
				cancelAndIgnoreRemainingEvents()
			}
		}

		@Test
		fun `emitUpdate updates lastActivity`() = runTest {
			val activity = RecognizedActivity(DetectedActivityType.RUNNING, 90)
			backend.emitUpdate(ActivityUpdate(activity, 2000L))

			backend.lastActivity shouldBe activity
			backend.lastActivityElapsedTimeMillis shouldBe 2000L
		}

		@Test
		fun `emitTransitions delivers a batch to transitionUpdates flow`() = runTest {
			val transition = TransitionUpdate(
				activityType = DetectedActivityType.WALKING,
				transitionType = ActivityTransitionType.ENTER,
				elapsedRealTimeNanos = 5000L,
			)

			backend.transitionUpdates.test {
				backend.emitTransitions(listOf(transition))
				awaitItem() shouldBe listOf(transition)
				cancelAndIgnoreRemainingEvents()
			}
		}
	}

	@Nested
	@DisplayName("reset")
	inner class Reset {

		@Test
		fun `reset clears all state`() = runTest {
			backend.startUpdates(RecognitionConfig(intervalSeconds = 10))
			backend.reset()

			backend.isRunning shouldBe false
			backend.lastConfig shouldBe null
			backend.startCount shouldBe 0
			backend.stopCount shouldBe 0
		}
	}
}
