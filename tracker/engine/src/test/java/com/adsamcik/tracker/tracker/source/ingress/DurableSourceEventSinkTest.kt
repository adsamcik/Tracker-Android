package com.adsamcik.tracker.tracker.source.ingress

import com.adsamcik.tracker.tracker.source.coordinator.SourcePipelineRecovery
import com.adsamcik.tracker.tracker.source.control.CollectionMotionController
import com.adsamcik.tracker.tracker.source.model.ActivityTransitionPayload
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourceQuality
import com.adsamcik.tracker.tracker.source.runtime.SourceAdmissionHandoff
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DurableSourceEventSinkTest {
	@Test
	fun `durable live admission requests projection drain before returning handoff`() = runTest {
		val ingress = mockk<DurableSourceIngress>()
		val recovery = mockk<SourcePipelineRecovery>(relaxed = true)
		val motionController = mockk<CollectionMotionController>(relaxed = true)
		coEvery { ingress.admit(any()) } returns AdmissionResult.Admitted(SourceEventId("event"), 7L)
		val subject = DurableSourceEventSinkFactory(ingress, recovery, motionController)

		subject.unbound.admit(candidate()).shouldBeInstanceOf<SourceAdmissionHandoff.Durable>()

		verify(exactly = 1) { motionController.onDurableEvidence(any()) }
		coVerify(exactly = 1) { recovery.drainCommittedWork() }
	}

	@Test
	fun `failed admission does not run projections`() = runTest {
		val ingress = mockk<DurableSourceIngress>()
		val recovery = mockk<SourcePipelineRecovery>(relaxed = true)
		coEvery { ingress.admit(any()) } returns AdmissionResult.RetryableFailure(
			AdmissionFailureCode.STORAGE_UNAVAILABLE,
		)
		val subject = DurableSourceEventSinkFactory(ingress, recovery)

		subject.unbound.admit(candidate()).shouldBeInstanceOf<SourceAdmissionHandoff.RetryableFailure>()

		coVerify(exactly = 0) { recovery.drainCommittedWork() }
	}

	private fun candidate() = SourceEvidenceCandidate(
		providerDedupKey = null,
		logicalTrackingId = null,
		serviceRunId = null,
		source = SourceKind.ACTIVITY,
		sourceInstanceId = SourceInstanceId("activity"),
		registrationGeneration = 1,
		sourceSequence = 1,
		configRevision = 1,
		planAttribution = PlanAttribution.CAPTURED_REGISTRATION,
		clockDomainId = "boot",
		observedElapsedRealtimeNanos = 1,
		receivedElapsedRealtimeNanos = 2,
		wallTimeMs = 1,
		wallTimeUncertaintyMs = 0,
		capturedCollectedDataEpoch = 0,
		acquiredAtMs = 1,
		quality = SourceQuality(),
		payloadVersion = 1,
		payload = ActivityTransitionPayload(1, 1, 1),
	)
}
