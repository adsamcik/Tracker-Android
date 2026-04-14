package com.adsamcik.tracker.tracker.service

import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
import com.adsamcik.tracker.tracker.policy.TrackingPolicyManager
import io.kotest.matchers.shouldBe
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("TrackerPolicyFeeder")
class TrackerPolicyFeederTest {

	private lateinit var feeder: TrackerPolicyFeeder
	private lateinit var policyManager: TrackingPolicyManager

	@BeforeEach
	fun setup() {
		feeder = TrackerPolicyFeeder()
		policyManager = mockk(relaxed = true)
	}

	private fun createCycle(
		timestampMs: Long = 1000L,
		stepDelta: Int? = null,
	): TrackingCycle = TrackingCycle(
		timestampMs = timestampMs,
		elapsedRealtimeNanos = 0L,
		stepDelta = stepDelta,
	)

	private fun createCollectionData(
		activity: ActivityInfo? = null,
		location: Location? = null,
	): CollectionData {
		val data = mockk<CollectionData>(relaxed = true)
		every { data.activity } returns activity
		every { data.location } returns location
		return data
	}

	@Nested
	@DisplayName("Activity transition feeding")
	inner class ActivityTransition {

		@Test
		fun `first activity does not trigger transition`() = runTest {
			val activity = ActivityInfo(activityType = 7, confidence = 80)
			val collectionData = createCollectionData(activity = activity)
			val cycle = createCycle()

			feeder.feed(policyManager, collectionData, cycle, this)

			coVerify(exactly = 0) { policyManager.onActivityTransition(any(), any(), any()) }
		}

		@Test
		fun `same activity type does not trigger transition`() = runTest {
			val activity1 = ActivityInfo(activityType = 7, confidence = 80)
			val activity2 = ActivityInfo(activityType = 7, confidence = 90)
			val cycle = createCycle()

			feeder.feed(policyManager, createCollectionData(activity = activity1), cycle, this)
			feeder.feed(policyManager, createCollectionData(activity = activity2), cycle, this)

			coVerify(exactly = 0) { policyManager.onActivityTransition(any(), any(), any()) }
		}

		@Test
		fun `different activity type triggers transition`() = runTest {
			val dispatcher = UnconfinedTestDispatcher(testScheduler)
			val scope = kotlinx.coroutines.CoroutineScope(dispatcher)
			val activity1 = ActivityInfo(activityType = 3, confidence = 80) // STILL
			val activity2 = ActivityInfo(activityType = 7, confidence = 85) // WALKING

			feeder.feed(policyManager, createCollectionData(activity = activity1), createCycle(1000L), scope)
			feeder.feed(policyManager, createCollectionData(activity = activity2), createCycle(2000L), scope)

			coVerify(exactly = 1) {
				policyManager.onActivityTransition(
					activityType = 7,
					confidence = 85,
					timeMs = 2000L,
				)
			}
		}
	}

	@Nested
	@DisplayName("Step feeding")
	inner class StepFeeding {

		@Test
		fun `null stepDelta does not trigger update`() = runTest {
			val cycle = createCycle(stepDelta = null)
			feeder.feed(policyManager, createCollectionData(), cycle, this)
			coVerify(exactly = 0) { policyManager.onStepUpdate(any(), any()) }
		}

		@Test
		fun `zero stepDelta does not trigger update`() = runTest {
			val cycle = createCycle(stepDelta = 0)
			feeder.feed(policyManager, createCollectionData(), cycle, this)
			coVerify(exactly = 0) { policyManager.onStepUpdate(any(), any()) }
		}

		@Test
		fun `positive stepDelta triggers step update`() = runTest {
			val dispatcher = UnconfinedTestDispatcher(testScheduler)
			val scope = kotlinx.coroutines.CoroutineScope(dispatcher)
			val cycle = createCycle(stepDelta = 10, timestampMs = 5000L)

			feeder.feed(policyManager, createCollectionData(), cycle, scope)

			coVerify(exactly = 1) {
				policyManager.onStepUpdate(stepCount = 10, timeMs = 5000L)
			}
		}

		@Test
		fun `step count accumulates across calls`() = runTest {
			val dispatcher = UnconfinedTestDispatcher(testScheduler)
			val scope = kotlinx.coroutines.CoroutineScope(dispatcher)

			feeder.feed(policyManager, createCollectionData(), createCycle(stepDelta = 5, timestampMs = 1000L), scope)
			feeder.feed(policyManager, createCollectionData(), createCycle(stepDelta = 8, timestampMs = 2000L), scope)

			coVerify(exactly = 1) { policyManager.onStepUpdate(stepCount = 5, timeMs = 1000L) }
			coVerify(exactly = 1) { policyManager.onStepUpdate(stepCount = 13, timeMs = 2000L) }
		}
	}

	@Nested
	@DisplayName("Location feeding")
	inner class LocationFeeding {

		@Test
		fun `null location does not trigger change`() = runTest {
			feeder.feed(policyManager, createCollectionData(location = null), createCycle(), this)
			coVerify(exactly = 0) { policyManager.onLocationChange(any(), any()) }
		}

		@Test
		fun `first location sets speed but does not trigger displacement`() = runTest {
			val location = mockk<Location>(relaxed = true)
			every { location.speed } returns 1.5f
			feeder.feed(policyManager, createCollectionData(location = location), createCycle(), this)

			verify(exactly = 1) { policyManager.escalationEngine?.updateSpeed(1.5f) }
			coVerify(exactly = 0) { policyManager.onLocationChange(any(), any()) }
		}
	}

	@Nested
	@DisplayName("reset")
	inner class Reset {

		@Test
		fun `reset clears accumulated state`() = runTest {
			val dispatcher = UnconfinedTestDispatcher(testScheduler)
			val scope = kotlinx.coroutines.CoroutineScope(dispatcher)

			// Accumulate steps
			feeder.feed(policyManager, createCollectionData(), createCycle(stepDelta = 10, timestampMs = 1000L), scope)

			feeder.reset()

			// After reset, steps should start from 0
			feeder.feed(policyManager, createCollectionData(), createCycle(stepDelta = 5, timestampMs = 2000L), scope)
			coVerify(exactly = 1) { policyManager.onStepUpdate(stepCount = 5, timeMs = 2000L) }
		}

		@Test
		fun `reset clears activity state`() = runTest {
			val dispatcher = UnconfinedTestDispatcher(testScheduler)
			val scope = kotlinx.coroutines.CoroutineScope(dispatcher)
			val activity = ActivityInfo(activityType = 7, confidence = 80)

			feeder.feed(policyManager, createCollectionData(activity = activity), createCycle(1000L), scope)
			feeder.reset()

			// After reset, first activity should not trigger transition
			feeder.feed(policyManager, createCollectionData(activity = activity), createCycle(2000L), scope)
			coVerify(exactly = 0) { policyManager.onActivityTransition(any(), any(), any()) }
		}
	}
}
