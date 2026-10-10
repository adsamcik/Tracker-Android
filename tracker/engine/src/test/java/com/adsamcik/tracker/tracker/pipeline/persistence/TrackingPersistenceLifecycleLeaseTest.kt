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
	fun `finite offline setup does not wait behind retained cleanup and can retry after release`() = runTest {
		val lease = ExclusiveTrackingPersistenceLifecycleLease()
		val cleanup = lease.acquireOfflineLocationRecovery()
		var entered = false
		lease.tryWithOfflineLocationRecovery { entered = true; "ready" } shouldBe null
		entered.shouldBeFalse()
		cleanup.release()
		lease.tryWithOfflineLocationRecovery { "ready" } shouldBe "ready"
		lease.acquireLivePipeline().release()
	}

	@Test
	fun `cancelled finite offline setup releases the same permit for the next caller`() = runTest {
		val lease = ExclusiveTrackingPersistenceLifecycleLease()
		val entered = CompletableDeferred<Unit>()
		val setup = launch { lease.tryWithOfflineLocationRecovery {
			entered.complete(Unit); kotlinx.coroutines.awaitCancellation()
		} }
		entered.await(); setup.cancelAndJoin()
		lease.tryWithOfflineLocationRecovery { "released" } shouldBe "released"
		lease.acquireLivePipeline().release()
	}

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
	fun `explicit offline ownership remains exclusive until cleanup releases its permit`() = runTest {
		val lease = ExclusiveTrackingPersistenceLifecycleLease()
		val offline = lease.acquireOfflineLocationRecovery()
		offline.mode shouldBe TrackingPersistenceLifecycleMode.OFFLINE_LOCATION_RECOVERY
		val liveAcquired = CompletableDeferred<TrackingPersistenceLifecyclePermit>()
		val live = async {
			lease.acquireLivePipeline().also(liveAcquired::complete)
		}

		yield()
		liveAcquired.isCompleted.shouldBeFalse()
		offline.release()
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
