package com.adsamcik.tracker.shared.utils.module

import app.cash.turbine.test
import com.adsamcik.tracker.shared.base.data.TrackerSession
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("TrackerSessionChannel")
class TrackerSessionChannelTest {

	private fun createSession(
		id: Long = 0,
		start: Long = 1000L,
		collections: Int = 1,
	): TrackerSession = TrackerSession(
		id = id,
		start = start,
		end = start + 5000,
		isUserInitiated = true,
		collections = collections,
		distanceInM = 100f,
		distanceOnFootInM = 80f,
		distanceInVehicleInM = 20f,
		steps = 150,
	)

	@Nested
	@DisplayName("sessions flow")
	inner class SessionsFlow {

		@Test
		fun `emits session to collector`() = runTest {
			val channel = TrackerSessionChannel()
			val session = createSession(id = 1)

			channel.sessions.test {
				channel.emit(session)
				val received = awaitItem()
				received.id shouldBe 1
				received.start shouldBe 1000L
				cancelAndIgnoreRemainingEvents()
			}
		}

		@Test
		fun `emits multiple sessions in order`() = runTest {
			val channel = TrackerSessionChannel()

			channel.sessions.test {
				channel.emit(createSession(id = 1))
				channel.emit(createSession(id = 2))
				channel.emit(createSession(id = 3))

				awaitItem().id shouldBe 1
				awaitItem().id shouldBe 2
				awaitItem().id shouldBe 3
				cancelAndIgnoreRemainingEvents()
			}
		}

		@Test
		fun `multiple collectors receive the same emission`() = runTest {
			val channel = TrackerSessionChannel()
			val received1 = mutableListOf<Long>()
			val received2 = mutableListOf<Long>()

			val job1 = launch(UnconfinedTestDispatcher(testScheduler)) {
				channel.sessions.collect { received1.add(it.id) }
			}
			val job2 = launch(UnconfinedTestDispatcher(testScheduler)) {
				channel.sessions.collect { received2.add(it.id) }
			}

			channel.emit(createSession(id = 42))

			received1 shouldBe listOf(42L)
			received2 shouldBe listOf(42L)

			job1.cancel()
			job2.cancel()
		}

		@Test
		fun `sessions property returns Flow type`() {
			val channel = TrackerSessionChannel()
			val flow = channel.sessions
			// Verify it is a Flow (compilation check + non-null)
			(flow is kotlinx.coroutines.flow.Flow<TrackerSession>) shouldBe true
		}

		@Test
		fun `emit preserves session data`() = runTest {
			val channel = TrackerSessionChannel()
			val session = TrackerSession(
				id = 7,
				start = 2000L,
				end = 3000L,
				isUserInitiated = false,
				collections = 5,
				distanceInM = 250f,
				distanceOnFootInM = 200f,
				distanceInVehicleInM = 50f,
				steps = 300,
			)

			channel.sessions.test {
				channel.emit(session)
				val received = awaitItem()
				received.id shouldBe 7
				received.start shouldBe 2000L
				received.end shouldBe 3000L
				received.isUserInitiated shouldBe false
				received.collections shouldBe 5
				received.distanceInM shouldBe 250f
				received.distanceOnFootInM shouldBe 200f
				received.distanceInVehicleInM shouldBe 50f
				received.steps shouldBe 300
				cancelAndIgnoreRemainingEvents()
			}
		}

		@Test
		fun `new subscriber does not receive past emissions`() = runTest {
			val channel = TrackerSessionChannel()
			// Emit before any subscriber
			channel.emit(createSession(id = 99))

			channel.sessions.test {
				// Should not receive the emission from before subscription
				expectNoEvents()
				cancelAndIgnoreRemainingEvents()
			}
		}
	}
}
