package com.adsamcik.tracker.tracker.source.ingress

import com.adsamcik.tracker.tracker.source.coordinator.SourcePipelineRecovery
import com.adsamcik.tracker.tracker.source.control.CollectionMotionController
import com.adsamcik.tracker.tracker.source.model.ActivityTransitionPayload
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryCandidate
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryUnit
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePayload
import com.adsamcik.tracker.tracker.source.model.SourceQuality
import com.adsamcik.tracker.tracker.source.model.StepCounterWindowPayload
import com.adsamcik.tracker.tracker.source.model.sourceDeliveryIdentity
import com.adsamcik.tracker.tracker.source.runtime.SourceAdmissionHandoff
import com.adsamcik.tracker.tracker.source.runtime.SourceDeliveryAdmissionHandoff
import com.adsamcik.tracker.tracker.source.runtime.RuntimeCheckpointLifecycle
import com.adsamcik.tracker.tracker.source.runtime.SensorAdmissionCheckpoint
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DurableSourceEventSinkTest {
	@Test
	fun `atomic checkpoint admission is forwarded and drains only after the transaction handoff`() = runTest {
		val ingress = mockk<DurableSourceIngress>()
		val recovery = mockk<SourcePipelineRecovery>(relaxed = true)
		val motionController = mockk<CollectionMotionController>(relaxed = true)
		val checkpoint = atomicCheckpoint()
		coEvery { ingress.admit(any(), checkpoint) } returns
			AdmissionResult.Admitted(SourceEventId("event"), 7L)
		val subject = DurableSourceEventSinkFactory(ingress, recovery, motionController)

		subject.unbound.admit(candidate(), checkpoint)
			.shouldBeInstanceOf<SourceAdmissionHandoff.Durable>()

		coVerify(exactly = 1) { ingress.admit(any(), checkpoint) }
		verify(exactly = 1) { motionController.onDurableEvidence(any()) }
		verify(exactly = 1) { recovery.requestCommittedWorkDrain() }
	}

	@Test
	fun `durable manual Steps admission does not register or schedule Activity automation work`() = runTest {
		val ingress = mockk<DurableSourceIngress>()
		val recovery = mockk<SourcePipelineRecovery>(relaxed = true)
		val checkpoint = atomicCheckpoint()
		coEvery { ingress.admit(any(), checkpoint) } returns
			AdmissionResult.Admitted(SourceEventId("steps-event"), 7L)
		val subject = DurableSourceEventSinkFactory(ingress, recovery)

		subject.unbound.admit(stepsCandidate(), checkpoint)
			.shouldBeInstanceOf<SourceAdmissionHandoff.Durable>()

		coVerify(exactly = 1) { ingress.admit(any(), checkpoint) }
		verify(exactly = 0) { recovery.requestCommittedWorkDrain() }
		verify(exactly = 1) { recovery.requestStepsSessionFactDrain() }
	}

	@Test
	fun `duplicate Steps admission re-kicks its durable source lane`() = runTest {
		val ingress = mockk<DurableSourceIngress>()
		val recovery = mockk<SourcePipelineRecovery>(relaxed = true)
		coEvery { ingress.admit(any()) } returns AdmissionResult.Duplicate(
			SourceEventId("steps-event"),
			7L,
		)
		val subject = DurableSourceEventSinkFactory(ingress, recovery)

		subject.unbound.admit(stepsCandidate())
			.shouldBeInstanceOf<SourceAdmissionHandoff.Duplicate>()

		verify(exactly = 1) { recovery.requestStepsSessionFactDrain() }
		verify(exactly = 0) { recovery.requestCommittedWorkDrain() }
	}

	@Test
	fun `atomic delivery admission requests exactly one projection drain`() = runTest {
		val ingress = mockk<DurableSourceIngress>()
		val deliveryIngress = mockk<DurableSourceDeliveryIngress>()
		val recovery = mockk<SourcePipelineRecovery>(relaxed = true)
		coEvery { deliveryIngress.admit(any()) } returns DeliveryAdmissionResult.Admitted(
			listOf(
				DeliveryAdmissionResult.AdmittedUnit(0, SourceEventId("event-0"), 7L),
				DeliveryAdmissionResult.AdmittedUnit(1, SourceEventId("event-1"), 8L),
			),
		)
		val subject = DurableSourceEventSinkFactory(ingress, deliveryIngress, recovery)

		val handoff = subject.unbound.admit(delivery())

		handoff.shouldBeInstanceOf<SourceDeliveryAdmissionHandoff.Durable>()
		coVerify(exactly = 1) { deliveryIngress.admit(any()) }
		verify(exactly = 1) { recovery.requestCommittedWorkDrain() }
	}

	@Test
	fun `delivery checkpoint is forwarded only through the atomic delivery seam`() = runTest {
		val ingress = mockk<DurableSourceIngress>()
		val deliveryIngress = mockk<DurableSourceDeliveryIngress>()
		val recovery = mockk<SourcePipelineRecovery>(relaxed = true)
		val delivery = delivery()
		val checkpoint = mockk<SensorAdmissionCheckpoint>()
		coEvery { deliveryIngress.admit(delivery, checkpoint) } returns
			DeliveryAdmissionResult.Duplicate(
				delivery.units.mapIndexed { index, _ ->
					DeliveryAdmissionResult.AdmittedUnit(
						unitIndex = index,
						eventId = SourceEventId("event-$index"),
						admissionOrdinal = index + 7L,
					)
				},
			)
		val subject = DurableSourceEventSinkFactory(ingress, deliveryIngress, recovery)

		subject.unbound.admit(delivery, checkpoint)
			.shouldBeInstanceOf<SourceDeliveryAdmissionHandoff.Duplicate>()

		coVerify(exactly = 1) { deliveryIngress.admit(delivery, checkpoint) }
		coVerify(exactly = 0) { deliveryIngress.admit(delivery) }
		verify(exactly = 1) { recovery.requestCommittedWorkDrain() }
	}

	@Test
	fun `atomic delivery runs motion only for sparsely admitted unit indexes`() = runTest {
		val ingress = mockk<DurableSourceIngress>()
		val deliveryIngress = mockk<DurableSourceDeliveryIngress>()
		val recovery = mockk<SourcePipelineRecovery>(relaxed = true)
		val motionController = mockk<CollectionMotionController>(relaxed = true)
		val delivery = delivery()
		coEvery { deliveryIngress.admit(any()) } returns DeliveryAdmissionResult.Admitted(
			listOf(DeliveryAdmissionResult.AdmittedUnit(1, SourceEventId("event-1"), 8L)),
		)
		val subject = DurableSourceEventSinkFactory(
			ingress,
			deliveryIngress,
			recovery,
			motionController,
		)

		subject.unbound.admit(delivery).shouldBeInstanceOf<SourceDeliveryAdmissionHandoff.Durable>()

		verify(exactly = 0) { motionController.onDurableEvidence(delivery.units[0].evidence) }
		verify(exactly = 1) { motionController.onDurableEvidence(delivery.units[1].evidence) }
		verify(exactly = 1) { recovery.requestCommittedWorkDrain() }
	}

	@Test
	fun `duplicate delivery replay still requests one projection drain`() = runTest {
		val ingress = mockk<DurableSourceIngress>()
		val deliveryIngress = mockk<DurableSourceDeliveryIngress>()
		val recovery = mockk<SourcePipelineRecovery>(relaxed = true)
		val motionController = mockk<CollectionMotionController>(relaxed = true)
		coEvery { deliveryIngress.admit(any()) } returns DeliveryAdmissionResult.Duplicate(
			listOf(
				DeliveryAdmissionResult.AdmittedUnit(0, SourceEventId("event-0"), 7L),
				DeliveryAdmissionResult.AdmittedUnit(1, SourceEventId("event-1"), 8L),
			),
		)
		val subject = DurableSourceEventSinkFactory(
			ingress,
			deliveryIngress,
			recovery,
			motionController,
		)

		val handoff = subject.unbound.admit(delivery())

		handoff.shouldBeInstanceOf<SourceDeliveryAdmissionHandoff.Duplicate>()
		coVerify(exactly = 1) { deliveryIngress.admit(any()) }
		verify(exactly = 0) { motionController.onDurableEvidence(any()) }
		verify(exactly = 1) { recovery.requestCommittedWorkDrain() }
	}

	@Test
	fun `durable live admission requests projection drain before returning handoff`() = runTest {
		val ingress = mockk<DurableSourceIngress>()
		val recovery = mockk<SourcePipelineRecovery>(relaxed = true)
		val motionController = mockk<CollectionMotionController>(relaxed = true)
		coEvery { ingress.admit(any()) } returns AdmissionResult.Admitted(SourceEventId("event"), 7L)
		val subject = DurableSourceEventSinkFactory(ingress, recovery, motionController)

		subject.unbound.admit(candidate()).shouldBeInstanceOf<SourceAdmissionHandoff.Durable>()

		verify(exactly = 1) { motionController.onDurableEvidence(any()) }
		verify(exactly = 1) { recovery.requestCommittedWorkDrain() }
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

		verify(exactly = 0) { recovery.requestCommittedWorkDrain() }
		verify(exactly = 0) { recovery.requestStepsSessionFactDrain() }
	}

	@Test
	fun `older single admission replay cannot regress live motion evidence`() = runTest {
		val ingress = mockk<DurableSourceIngress>()
		val recovery = mockk<SourcePipelineRecovery>(relaxed = true)
		val motionController = mockk<CollectionMotionController>(relaxed = true)
		coEvery { ingress.admit(any()) } returnsMany listOf(
			AdmissionResult.Admitted(SourceEventId("newer"), 8L),
			AdmissionResult.Duplicate(SourceEventId("older"), 7L),
		)
		val subject = DurableSourceEventSinkFactory(ingress, recovery, motionController)
		val newer = candidate().copy(
			observedElapsedRealtimeNanos = 200L,
			receivedElapsedRealtimeNanos = 201L,
		)
		val older = candidate().copy(
			observedElapsedRealtimeNanos = 100L,
			receivedElapsedRealtimeNanos = 101L,
		)

		subject.unbound.admit(newer)
		subject.unbound.admit(older)

		verify(exactly = 1) { motionController.onDurableEvidence(newer) }
		verify(exactly = 0) { motionController.onDurableEvidence(older) }
	}

	@Test
	fun `first duplicate after a lost handoff still restores live motion evidence`() = runTest {
		val ingress = mockk<DurableSourceIngress>()
		val recovery = mockk<SourcePipelineRecovery>(relaxed = true)
		val motionController = mockk<CollectionMotionController>(relaxed = true)
		coEvery { ingress.admit(any()) } returns AdmissionResult.Duplicate(
			SourceEventId("existing"),
			7L,
		)
		val subject = DurableSourceEventSinkFactory(ingress, recovery, motionController)
		val evidence = candidate()

		subject.unbound.admit(evidence)
		subject.unbound.admit(evidence)

		verify(exactly = 1) { motionController.onDurableEvidence(evidence) }
	}

	private fun candidate(): SourceEvidenceCandidate<SourcePayload> = SourceEvidenceCandidate(
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

	private fun stepsCandidate() = candidate().copy(
		source = SourceKind.STEPS,
		sourceInstanceId = SourceInstanceId("steps"),
		payload = StepCounterWindowPayload(
			bootClockDomainId = "boot",
			firstCumulativeCount = 10L,
			lastCumulativeCount = 12L,
			deltaCount = 2L,
			windowStartElapsedRealtimeNanos = 1L,
			windowEndElapsedRealtimeNanos = 2L,
			firstProviderSequence = 1L,
			lastProviderSequence = 2L,
			baselineReset = false,
		),
	)

	private fun delivery(): SourceDeliveryCandidate = SourceDeliveryCandidate(
		identity = sourceDeliveryIdentity("delivery".encodeToByteArray()),
		units = listOf(
			SourceDeliveryUnit(0, candidate().copy(sourceSequence = 0L)),
			SourceDeliveryUnit(
				1,
				candidate().copy(
					sourceSequence = 0L,
					payload = ActivityTransitionPayload(2, 2, 2),
				),
			),
		),
	)

	private fun atomicCheckpoint() = SensorAdmissionCheckpoint(
		source = SourceKind.STEPS,
		ownerScope = "source-broker:${SourceKind.STEPS.stableCode}",
		sourceInstanceId = "step-instance",
		clockDomainId = "boot",
		registrationGeneration = 1L,
		providerSequenceThrough = 1L,
		lifecycle = RuntimeCheckpointLifecycle.ACTIVE,
		failedAdmissionCount = 0L,
		unresolvedSequenceStart = null,
		unresolvedSequenceEndInclusive = null,
		gapClassifications = emptySet(),
		componentStateVersion = 1,
		componentPayload = byteArrayOf(1),
		updatedAtMs = 1L,
	)
}
