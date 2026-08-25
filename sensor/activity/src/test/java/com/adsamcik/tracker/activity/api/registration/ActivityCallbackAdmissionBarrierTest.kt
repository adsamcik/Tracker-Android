package com.adsamcik.tracker.activity.api.registration

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ActivityCallbackAdmissionBarrierTest {
	@Test
	fun `fence denies later entry drains prior permit and supports same generation reopen`() = runTest {
		val barrier = ActivityCallbackAdmissionBarrier()
		val identity = identity(generation = 1L)
		val firstPermit = checkNotNull(barrier.tryEnter(identity))
		val fence = async(start = CoroutineStart.UNDISPATCHED) {
			barrier.fenceAndAwait(identity)
		}

		fence.isCompleted shouldBe false
		barrier.tryEnter(identity) shouldBe null

		firstPermit.complete()
		firstPermit.complete()
		runCurrent()
		fence.isCompleted shouldBe true
		barrier.tryEnter(identity) shouldBe null

		barrier.reopen(identity) shouldBe true
		checkNotNull(barrier.tryEnter(identity)).complete()
	}

	@Test
	fun `abandoned permit keeps fence closed after its waiter is cancelled`() = runTest {
		val barrier = ActivityCallbackAdmissionBarrier()
		val identity = identity(generation = 2L)
		checkNotNull(barrier.tryEnter(identity))
		val fence = async(start = CoroutineStart.UNDISPATCHED) {
			barrier.fenceAndAwait(identity)
		}

		fence.isCompleted shouldBe false
		fence.cancelAndJoin()

		barrier.reopen(identity) shouldBe false
		barrier.tryEnter(identity) shouldBe null
	}

	@Test
	fun `cancelling one fence waiter does not corrupt another waiter or reopen admission`() = runTest {
		val barrier = ActivityCallbackAdmissionBarrier()
		val identity = identity(generation = 3L)
		val permit = checkNotNull(barrier.tryEnter(identity))
		val survivingFence = async(start = CoroutineStart.UNDISPATCHED) {
			barrier.fenceAndAwait(identity)
		}
		val cancelledFence = async(start = CoroutineStart.UNDISPATCHED) {
			barrier.fenceAndAwait(identity)
		}

		cancelledFence.cancelAndJoin()
		barrier.tryEnter(identity) shouldBe null
		permit.complete()
		runCurrent()

		survivingFence.isCompleted shouldBe true
		barrier.tryEnter(identity) shouldBe null
		barrier.reopen(identity) shouldBe true
		checkNotNull(barrier.tryEnter(identity)).complete()
	}

	@Test
	fun `tombstone prevents an old generation from becoming implicitly open again`() = runTest {
		val barrier = ActivityCallbackAdmissionBarrier()
		val retired = identity(generation = 4L)
		checkNotNull(barrier.tryEnter(retired)).complete()

		barrier.tombstoneAndAwait(retired)

		barrier.tryEnter(retired) shouldBe null
		barrier.reopen(retired) shouldBe false
		checkNotNull(barrier.tryEnter(identity(generation = 5L))).complete()
	}

	private fun identity(generation: Long) = ActivityRegistrationIdentity(
		sourceInstanceId = "activity-source",
		registrationGeneration = generation,
		collectedDataEpoch = 7L,
		clockDomainId = "boot-1",
		physicalConfigurationFingerprint = "fingerprint-$generation",
	)
}
