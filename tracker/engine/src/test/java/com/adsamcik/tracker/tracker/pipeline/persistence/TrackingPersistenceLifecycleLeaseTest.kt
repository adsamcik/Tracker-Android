package com.adsamcik.tracker.tracker.pipeline.persistence

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test

class TrackingPersistenceLifecycleLeaseTest {
	@Test
	fun `live pipeline excludes offline Location and Pressure transition`() = runTest {
		val lease = ExclusiveTrackingPersistenceLifecycleLease()
		val live = lease.acquireLivePipeline()
		val offlineEntered = CompletableDeferred<Unit>()
		val pressureEntered = CompletableDeferred<Unit>()
		val offline = async {
			lease.withOfflineLocationRecovery {
				offlineEntered.complete(Unit)
			}
		}
		val pressure = async {
			lease.withPressureWriterTransition {
				pressureEntered.complete(Unit)
			}
		}

		yield()
		offlineEntered.isCompleted.shouldBeFalse()
		pressureEntered.isCompleted.shouldBeFalse()
		live.release()
		offline.await()
		pressure.await()
		offlineEntered.isCompleted.shouldBeTrue()
		pressureEntered.isCompleted.shouldBeTrue()
	}

	@Test
	fun `offline pending recovery excludes a concurrent live start`() = runTest {
		val lease = ExclusiveTrackingPersistenceLifecycleLease()
		val offlineEntered = CompletableDeferred<Unit>()
		val releaseOffline = CompletableDeferred<Unit>()
		val offline = async {
			lease.withOfflineLocationRecovery {
				offlineEntered.complete(Unit)
				releaseOffline.await()
			}
		}
		offlineEntered.await()
		val liveAcquired = CompletableDeferred<TrackingPersistenceLifecyclePermit>()
		val live = async {
			lease.acquireLivePipeline().also(liveAcquired::complete)
		}

		yield()
		liveAcquired.isCompleted.shouldBeFalse()
		releaseOffline.complete(Unit)
		offline.await()
		live.await().release()
		liveAcquired.isCompleted.shouldBeTrue()
	}

	@Test
	fun `cancelled waiter and cancelled owner release lifecycle authority`() = runTest {
		val lease = ExclusiveTrackingPersistenceLifecycleLease()
		val live = lease.acquireLivePipeline()
		val waiting = launch {
			lease.withOfflineLocationRecovery {
				error("cancelled waiter must not enter")
			}
		}
		yield()
		waiting.cancelAndJoin()
		live.release()
		val entered = CompletableDeferred<Unit>()
		val cancelledOwner = launch {
			lease.withPressureWriterTransition {
				entered.complete(Unit)
				kotlinx.coroutines.awaitCancellation()
			}
		}
		entered.await()
		cancelledOwner.cancelAndJoin()

		lease.withOfflineLocationRecovery {
			"released"
		} shouldBe "released"
	}
}
