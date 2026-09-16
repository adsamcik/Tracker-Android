package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.tracker.di.SourcePipelineModule
import com.adsamcik.tracker.tracker.pipeline.persistence.ExclusiveTrackingPersistenceLifecycleLease
import com.adsamcik.tracker.tracker.pipeline.persistence.PersistenceProcessor
import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test

class PersistenceLegacySourceWriterTransitionBoundaryTest {
	@Test
	fun `Activity transition waits for live lifecycle and drains recovery before operation`() =
		runTest {
			val lease = ExclusiveTrackingPersistenceLifecycleLease()
			val persistence = mockk<PersistenceProcessor>()
			every { persistence.isPipelineActiveForPersistenceLifecycle() } returns false
			every { persistence.hasUnrecoverablePersistenceStateForLifecycleFence() } returns false
			every { persistence.hasUnsettledPersistenceStateForPressureFence() } returns false
			coEvery { persistence.drainOrphanedSignals() } returns true
			val boundary = PersistenceLegacySourceWriterTransitionBoundary(persistence, lease)
			val live = lease.acquireLivePipeline()
			var operationRan = false
			val transition = async {
				boundary.runIfQuiescent(SourceKind.ACTIVITY) {
					operationRan = true
					"activated"
				}
			}

			yield()
			operationRan shouldBe false
			live.release()
			transition.await() shouldBe "activated"
			coVerify(exactly = 1) { persistence.drainOrphanedSignals() }
		}

	@Test
	fun `Pressure transition holds shared lease through pending recovery and owner operation`() =
		runTest {
			val lease = ExclusiveTrackingPersistenceLifecycleLease()
			val persistence = mockk<PersistenceProcessor>()
			every { persistence.isPipelineActiveForPersistenceLifecycle() } returns false
			every { persistence.hasUnrecoverablePersistenceStateForLifecycleFence() } returns false
			every { persistence.hasUnsettledPersistenceStateForPressureFence() } returns false
			val recoveryEntered = CompletableDeferred<Unit>()
			val releaseRecovery = CompletableDeferred<Unit>()
			coEvery { persistence.drainOrphanedSignals() } coAnswers {
				recoveryEntered.complete(Unit)
				releaseRecovery.await()
				true
			}
			val boundary = PersistenceLegacySourceWriterTransitionBoundary(persistence, lease)
			var operationRan = false
			val transition = async {
				boundary.runIfQuiescent(SourceKind.PRESSURE) {
					operationRan = true
					"transitioned"
				}
			}
			recoveryEntered.await()
			val offlineEntered = CompletableDeferred<Unit>()
			val offline = async {
				lease.withOfflineLocationRecovery {
					offlineEntered.complete(Unit)
				}
			}

			yield()
			operationRan shouldBe false
			offlineEntered.isCompleted shouldBe false
			releaseRecovery.complete(Unit)
			transition.await() shouldBe "transitioned"
			offline.await()
			offlineEntered.isCompleted shouldBe true
		}

	@Test
	fun `incomplete pending recovery blocks transition and unsupported sources remain unavailable`() =
		runTest {
			val lease = ExclusiveTrackingPersistenceLifecycleLease()
			val persistence = mockk<PersistenceProcessor>()
			every { persistence.isPipelineActiveForPersistenceLifecycle() } returns false
			every { persistence.hasUnrecoverablePersistenceStateForLifecycleFence() } returns false
			every { persistence.hasUnsettledPersistenceStateForPressureFence() } returns false
			coEvery { persistence.drainOrphanedSignals() } returns false
			val boundary = PersistenceLegacySourceWriterTransitionBoundary(persistence, lease)
			var operationRan = false

			boundary.runIfQuiescent(SourceKind.PRESSURE) {
				operationRan = true
				"unexpected"
			} shouldBe null
			boundary.runIfQuiescent(SourceKind.WIFI) {
				operationRan = true
				"unexpected"
			} shouldBe null

			operationRan shouldBe false
			coVerify(exactly = 1) { persistence.drainOrphanedSignals() }
			SourcePipelineModule.provideLegacySourceWriterTransitionBoundary(boundary) shouldBe boundary
		}

	@Test
	fun `retained in-memory persistence fails closed without destructive orphan recovery`() =
		runTest {
			val lease = ExclusiveTrackingPersistenceLifecycleLease()
			val persistence = mockk<PersistenceProcessor>()
			every { persistence.hasUnrecoverablePersistenceStateForLifecycleFence() } returns true
			val boundary = PersistenceLegacySourceWriterTransitionBoundary(persistence, lease)
			var operationRan = false

			boundary.runIfQuiescent(SourceKind.ACTIVITY) {
				operationRan = true
				"unexpected"
			} shouldBe null

			operationRan shouldBe false
			coVerify(exactly = 0) { persistence.drainOrphanedSignals() }
		}
}
