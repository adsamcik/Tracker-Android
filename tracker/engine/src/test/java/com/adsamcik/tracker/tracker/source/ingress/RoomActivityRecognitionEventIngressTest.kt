package com.adsamcik.tracker.tracker.source.ingress

import android.content.Context
import com.adsamcik.tracker.activity.ActivityTransitionData
import com.adsamcik.tracker.activity.ActivityTransitionType
import com.adsamcik.tracker.activity.api.ingress.ActivityIngressStartContext
import com.adsamcik.tracker.activity.api.ingress.ActivityRecognitionEvidence
import com.adsamcik.tracker.activity.api.ingress.ActivityRecognitionEvidenceBatch
import com.adsamcik.tracker.activity.api.ingress.ActivityTransitionEvidence
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationIdentity
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.SourceBrokerDao
import com.adsamcik.tracker.shared.base.database.data.ActivityAutomationEpochEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.startup.TrackingAdmissionStartupResult
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupStage
import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.tracker.source.coordinator.CoordinatorDrainResult
import com.adsamcik.tracker.tracker.source.coordinator.SourcePipelineRecovery
import com.adsamcik.tracker.tracker.source.coordinator.SourceRecoveryResult
import com.adsamcik.tracker.tracker.source.control.CollectionMotionController
import com.adsamcik.tracker.tracker.source.model.AdmittedSourceEvent
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryCandidate
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationEpochAuthority
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import javax.inject.Provider

class RoomActivityRecognitionEventIngressTest {
	private lateinit var deliveryIngress: DurableSourceDeliveryIngress
	private lateinit var committedIngress: DurableSourceIngress
	private lateinit var recovery: SourcePipelineRecovery
	private lateinit var motionController: CollectionMotionController
	private lateinit var context: Context
	private lateinit var startupGate: TrackingStartupGate
	private lateinit var database: AppDatabase
	private lateinit var sourceBrokerDao: SourceBrokerDao
	private lateinit var automationEpochAuthority: ActivityAutomationEpochAuthority
	private lateinit var subject: RoomActivityRecognitionEventIngress
	private val capturedDelivery = slot<SourceDeliveryCandidate>()

	@Before
	fun setUp() {
		deliveryIngress = mockk()
		committedIngress = mockk()
		recovery = mockk(relaxed = true)
		coEvery { recovery.drainCommittedActivityCallbackWork(any(), any(), any()) } returns
			completedRecovery()
		coEvery { recovery.drainCommittedWork() } returns completedRecovery()
		motionController = mockk(relaxed = true)
		context = mockk(relaxed = true)
		startupGate = mockk()
		coEvery { startupGate.reconcileAdmission(any()) } returns
			TrackingAdmissionStartupResult.Ready
		database = mockk()
		sourceBrokerDao = mockk()
		every { database.sourceBrokerDao() } returns sourceBrokerDao
		coEvery { sourceBrokerDao.registration(SourceKind.ACTIVITY.stableCode, 1L) } returns
			registration()
		automationEpochAuthority = mockk()
		coEvery { automationEpochAuthority.epochForCallbackAdmission() } returns
			activityAutomationAuthority()
		subject = RoomActivityRecognitionEventIngress(
			ActivitySourceDeliveryFactory(),
			Provider { startupGate },
			database,
			automationEpochAuthority,
			deliveryIngress,
			committedIngress,
			recovery,
			motionController,
			context,
		)
	}

	@Test
	fun `exact callback reserved during synchronous provider apply remains retryable`() = runTest {
		coEvery { sourceBrokerDao.registration(SourceKind.ACTIVITY.stableCode, 1L) } returns
			registration(ProviderRegistrationGenerationEntity.STATUS_RESERVED)

		val result = subject.admit(batch(recognitions = listOf(recognition(30L))))

		result.isDurable shouldBe false
		result.failureCode shouldBe "ACTIVITY_REGISTRATION_ACTIVATION_PENDING"
		coVerify(exactly = 0) { automationEpochAuthority.epochForCallbackAdmission() }
		coVerify(exactly = 0) { deliveryIngress.admit(any()) }
	}

	@Test
	fun `callback for failed provider registration is permanently rejected`() = runTest {
		coEvery { sourceBrokerDao.registration(SourceKind.ACTIVITY.stableCode, 1L) } returns
			registration(ProviderRegistrationGenerationEntity.STATUS_FAILED)

		val result = subject.admit(batch(recognitions = listOf(recognition(30L))))

		result.isDurable shouldBe false
		result.failureCode shouldBe "ACTIVITY_REGISTRATION_NOT_ACTIVE"
		coVerify(exactly = 0) { automationEpochAuthority.epochForCallbackAdmission() }
		coVerify(exactly = 0) { deliveryIngress.admit(any()) }
	}

	@Test
	fun `cold callback reaches startup recovery before automation Room authority`() = runTest {
		coEvery { startupGate.reconcileAdmission(any()) } returns
			TrackingAdmissionStartupResult.RetryableFailure(
				TrackingStartupStage.PREVIOUS_EXIT,
				"RECOVERY_PENDING",
			)

		val result = subject.admit(batch(recognitions = listOf(recognition(30L))))

		result.isDurable shouldBe false
		result.failureCode shouldBe "STARTUP_RECOVERY_NOT_READY"
		coVerify(exactly = 1) { startupGate.reconcileAdmission(any()) }
		coVerify(exactly = 0) { automationEpochAuthority.epochForCallbackAdmission() }
		coVerify(exactly = 0) { deliveryIngress.admit(any()) }
	}

	@Test
	fun `one atomic call maps sparse canonical units to selected original events`() = runTest {
		val units = listOf(
			DeliveryAdmissionResult.AdmittedUnit(2, SourceEventId("event-2"), 12L),
			DeliveryAdmissionResult.AdmittedUnit(0, SourceEventId("event-0"), 10L),
		)
		coEvery { deliveryIngress.admit(capture(capturedDelivery)) } returns
			DeliveryAdmissionResult.Admitted(units)
		coEvery { committedIngress.committedBatch(any(), 1) } answers {
			val ordinal = firstArg<Long>() + 1L
			val unitIndex = if (ordinal == 10L) 0 else 2
			listOf(
				AdmittedSourceEvent(
					SourceEventId("event-$unitIndex"),
					ordinal,
					capturedDelivery.captured.units[unitIndex].evidence,
				),
			)
		}
		val batch = batch(
			recognitions = listOf(recognition(30L)),
			transitions = listOf(transition(10L), transition(20L)),
		)

		val result = subject.admit(batch)

		result.isDurable shouldBe true
		result.admittedCount shouldBe 2
		result.durableSelection.recognitionIndexes shouldBe setOf(0)
		result.durableSelection.transitionIndexes shouldBe setOf(0)
		coVerify(exactly = 1) { deliveryIngress.admit(any()) }
		coVerify { committedIngress.committedBatch(9L, 1) }
		coVerify { committedIngress.committedBatch(11L, 1) }
		coVerify(exactly = 0) {
			recovery.drainCommittedActivityCallbackWork(any(), any(), any())
		}
		coVerify(exactly = 1) { recovery.drainCommittedWork() }
		verify(exactly = 2) { motionController.onDurableEvidence(any()) }
	}

	@Test
	fun `all callback transitions remain durable while only newest configured ordinal gets start permit`() = runTest {
		val units = listOf(
			DeliveryAdmissionResult.AdmittedUnit(0, SourceEventId("event-0"), 10L),
			DeliveryAdmissionResult.AdmittedUnit(1, SourceEventId("event-1"), 11L),
		)
		coEvery { deliveryIngress.admit(capture(capturedDelivery)) } returns
			DeliveryAdmissionResult.Admitted(units)
		coEvery { committedIngress.committedBatch(any(), 1) } answers {
			val ordinal = firstArg<Long>() + 1L
			val unitIndex = (ordinal - 10L).toInt()
			listOf(
				AdmittedSourceEvent(
					SourceEventId("event-$unitIndex"),
					ordinal,
					capturedDelivery.captured.units[unitIndex].evidence,
				),
			)
		}

		val result = subject.admit(
			batch(
				transitions = listOf(
					transition(10L, DetectedActivityType.WALKING),
					transition(20L, DetectedActivityType.STILL),
				),
			),
		)

		result.isDurable shouldBe true
		result.admittedCount shouldBe 2
		result.durableSelection.transitionIndexes shouldBe setOf(0, 1)
		capturedDelivery.captured.units.map { it.evidence.activityAutomationEpoch } shouldBe
			listOf(null, 17L)
		coVerify(exactly = 1) {
			recovery.drainCommittedActivityCallbackWork(setOf(11L), 11L, any())
		}
		verify(exactly = 2) { motionController.onDurableEvidence(any()) }
	}

	@Test
	fun `durable replay persists transition without borrowing live callback start permit`() = runTest {
		val unit = DeliveryAdmissionResult.AdmittedUnit(0, SourceEventId("event-0"), 10L)
		coEvery { deliveryIngress.admit(capture(capturedDelivery)) } returns
			DeliveryAdmissionResult.Admitted(listOf(unit))
		coEvery { committedIngress.committedBatch(9L, 1) } answers {
			listOf(
				AdmittedSourceEvent(
					unit.eventId,
					unit.admissionOrdinal,
					capturedDelivery.captured.units.single().evidence,
				),
			)
		}

		val result = subject.admit(
			batch(
				transitions = listOf(transition(10L)),
				startContext = ActivityIngressStartContext.DURABLE_REPLAY,
			),
		)

		result.isDurable shouldBe true
		coVerify(exactly = 1) { recovery.drainCommittedWork() }
		coVerify(exactly = 0) {
			recovery.drainCommittedActivityCallbackWork(any(), any(), any())
		}
	}

	@Test
	fun `writer reloaded evidence rather than adapter candidate drives motion`() = runTest {
		val unit = DeliveryAdmissionResult.AdmittedUnit(0, SourceEventId("writer-event"), 4L)
		coEvery { deliveryIngress.admit(capture(capturedDelivery)) } returns
			DeliveryAdmissionResult.Admitted(listOf(unit))
		val writerEvidence = slot<com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate<*>>()
		coEvery { committedIngress.committedBatch(3L, 1) } answers {
			val stamped = capturedDelivery.captured.units.single().evidence
				.let { evidence ->
					@Suppress("UNCHECKED_CAST")
					(evidence as com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate<
						com.adsamcik.tracker.tracker.source.model.SourcePayload
					>).copy(
						authorizationRevision = 7L,
						registrationPurposeEligibilityMask = 1L,
						registrationEligibilityFingerprint = "writer-authorization",
					)
				}
			listOf(AdmittedSourceEvent(unit.eventId, unit.admissionOrdinal, stamped))
		}
		every { motionController.onDurableEvidence(capture(writerEvidence)) } returns Unit

		subject.admit(batch(recognitions = listOf(recognition(30L))))

		writerEvidence.captured.authorizationRevision shouldBe 7L
		writerEvidence.captured.registrationEligibilityFingerprint shouldBe "writer-authorization"
	}

	@Test
	fun `duplicate result is durable but produces no repeated transient effect`() = runTest {
		val unit = DeliveryAdmissionResult.AdmittedUnit(0, SourceEventId("existing"), 8L)
		coEvery { deliveryIngress.admit(capture(capturedDelivery)) } returns
			DeliveryAdmissionResult.Duplicate(listOf(unit))
		coEvery { committedIngress.committedBatch(7L, 1) } answers {
			listOf(AdmittedSourceEvent(unit.eventId, 8L, capturedDelivery.captured.units.single().evidence))
		}

		val result = subject.admit(batch(transitions = listOf(transition(30L))))

		result.admittedCount shouldBe 0
		result.duplicateCount shouldBe 1
		result.durableSelection.isEmpty shouldBe true
		coVerify(exactly = 1) { deliveryIngress.admit(any()) }
		coVerify(exactly = 1) {
			recovery.drainCommittedActivityCallbackWork(setOf(8L), 8L, any())
		}
		verify(exactly = 0) { motionController.onDurableEvidence(any()) }
	}

	@Test
	fun `non-complete recovery withholds every transient effect`() = runTest {
		val unit = DeliveryAdmissionResult.AdmittedUnit(0, SourceEventId("event"), 8L)
		coEvery { deliveryIngress.admit(capture(capturedDelivery)) } returns
			DeliveryAdmissionResult.Admitted(listOf(unit))
		coEvery { committedIngress.committedBatch(7L, 1) } answers {
			listOf(AdmittedSourceEvent(unit.eventId, 8L, capturedDelivery.captured.units.single().evidence))
		}
		val failures = listOf(
			CoordinatorDrainResult.LeaseUnavailable to "PIPELINE_LEASE_UNAVAILABLE",
			CoordinatorDrainResult.LeaseLost(7L, 0) to "PIPELINE_LEASE_LOST",
			CoordinatorDrainResult.ProjectionFailed(7L, 0, "activity", 8L) to
				"PIPELINE_PROJECTION_FAILED",
			CoordinatorDrainResult.Complete(7L, 1) to "PIPELINE_DRAIN_INCOMPLETE",
		)

		failures.forEach { (drain, expectedCode) ->
			coEvery { recovery.drainCommittedWork() } returns
				SourceRecoveryResult(drain, 0, 0)

			val result = subject.admit(
				batch(recognitions = listOf(recognition(9L), recognition(30L))),
			)

			result.isDurable shouldBe false
			result.admittedCount shouldBe 1
			result.duplicateCount shouldBe 0
			result.discardedCount shouldBe 1
			result.settledCount shouldBe 2
			result.failureCode shouldBe expectedCode
			result.durableSelection.isEmpty shouldBe true
		}

		coVerify(exactly = failures.size) {
			recovery.drainCommittedWork()
		}
		verify(exactly = 0) { motionController.onDurableEvidence(any()) }
	}

	@Test
	fun `unsupported rejected empty and mismatched reloads select nothing`() = runTest {
		val empty = subject.admit(batch())
		empty.durableSelection.isEmpty shouldBe true
		coVerify(exactly = 0) { deliveryIngress.admit(any()) }

		coEvery { deliveryIngress.admit(any()) } returns DeliveryAdmissionResult.PermanentFailure(
			AdmissionFailureCode.UNSUPPORTED_PAYLOAD,
		)
		val rejected = subject.admit(batch(recognitions = listOf(recognition(30L))))
		rejected.isDurable shouldBe false
		rejected.durableSelection.isEmpty shouldBe true

		val unit = DeliveryAdmissionResult.AdmittedUnit(0, SourceEventId("expected"), 2L)
		coEvery { deliveryIngress.admit(capture(capturedDelivery)) } returns
			DeliveryAdmissionResult.Admitted(listOf(unit))
		coEvery { committedIngress.committedBatch(1L, 1) } answers {
			listOf(
				AdmittedSourceEvent(
					SourceEventId("wrong"),
					2L,
					capturedDelivery.captured.units.single().evidence,
				),
			)
		}
		val mismatch = subject.admit(batch(recognitions = listOf(recognition(30L))))
		mismatch.isDurable shouldBe false
		mismatch.durableSelection.isEmpty shouldBe true
		coVerify(exactly = 0) { recovery.drainCommittedActivityCallbackWork(any(), any(), any()) }
		verify(exactly = 0) { motionController.onDurableEvidence(any()) }
	}

	@Test
	fun `mixed pre acceptance and future members retain valid siblings and original indexes`() = runTest {
		val units = listOf(
			DeliveryAdmissionResult.AdmittedUnit(0, SourceEventId("event-0"), 10L),
			DeliveryAdmissionResult.AdmittedUnit(1, SourceEventId("event-1"), 11L),
		)
		coEvery { deliveryIngress.admit(capture(capturedDelivery)) } returns
			DeliveryAdmissionResult.Admitted(units)
		coEvery { committedIngress.committedBatch(any(), 1) } answers {
			val ordinal = firstArg<Long>() + 1L
			val unitIndex = (ordinal - 10L).toInt()
			listOf(
				AdmittedSourceEvent(
					SourceEventId("event-$unitIndex"),
					ordinal,
					capturedDelivery.captured.units[unitIndex].evidence,
				),
			)
		}
		val mixed = batch(
			recognitions = listOf(recognition(9L), recognition(10L), recognition(101L)),
			transitions = listOf(transition(8L), transition(20L), transition(102L)),
		)

		val result = subject.admit(mixed)

		result.isDurable shouldBe true
		result.admittedCount shouldBe 2
		result.discardedCount shouldBe 4
		result.settledCount shouldBe mixed.eventCount
		result.durableSelection.recognitionIndexes shouldBe setOf(1)
		result.durableSelection.transitionIndexes shouldBe setOf(1)
		capturedDelivery.captured.units.map { it.evidence.observedElapsedRealtimeNanos } shouldBe
			listOf(10L, 20L)
		capturedDelivery.captured.units.single {
			it.evidence.payload is com.adsamcik.tracker.tracker.source.model.ActivityTransitionPayload
		}.evidence.activityAutomationEpoch shouldBe 17L

		val allFuture = subject.admit(batch(recognitions = listOf(recognition(101L))))

		allFuture.isDurable shouldBe true
		allFuture.admittedCount shouldBe 0
		allFuture.discardedCount shouldBe 1
		allFuture.durableSelection.isEmpty shouldBe true
		coVerify(exactly = 1) { deliveryIngress.admit(any()) }
		verify(exactly = 2) { motionController.onDurableEvidence(any()) }
	}

	@Test
	fun `durable session cutoff rejects Activity atomically without transient effects`() = runTest {
		coEvery { deliveryIngress.admit(capture(capturedDelivery)) } returns
			DeliveryAdmissionResult.SessionCutoff(8L)
		val mixed = batch(
			recognitions = listOf(recognition(9L), recognition(10L), recognition(101L)),
			transitions = listOf(transition(8L), transition(20L), transition(102L)),
		)

		val result = subject.admit(mixed)

		result.isDurable shouldBe false
		result.admittedCount shouldBe 0
		result.duplicateCount shouldBe 0
		result.discardedCount shouldBe 4
		result.settledCount shouldBe 4
		result.durableSelection.isEmpty shouldBe true
		result.failureCode shouldBe "SESSION_CUTOFF_REQUIRES_SOURCE_PARTITION"
		capturedDelivery.captured.units.map { it.evidence.observedElapsedRealtimeNanos } shouldBe
			listOf(10L, 20L)
		verify(exactly = 0) { motionController.onDurableEvidence(any()) }
		coVerify(exactly = 0) { recovery.drainCommittedWork() }
		coVerify(exactly = 0) { recovery.drainCommittedActivityCallbackWork(any(), any(), any()) }
	}

	@Test
	fun `cancellation propagates and selects nothing`() = runTest {
		coEvery { deliveryIngress.admit(any()) } throws CancellationException("cancelled")

		shouldThrow<CancellationException> {
			subject.admit(batch(recognitions = listOf(recognition(30L))))
		}

		coVerify(exactly = 0) { committedIngress.committedBatch(any(), any()) }
		verify(exactly = 0) { motionController.onDurableEvidence(any()) }
	}

	private fun batch(
		recognitions: List<ActivityRecognitionEvidence> = emptyList(),
		transitions: List<ActivityTransitionEvidence> = emptyList(),
		automaticRecognitionEligible: Boolean = true,
		automaticTransitions: Set<ActivityTransitionData>? = null,
		startContext: ActivityIngressStartContext = ActivityIngressStartContext.LIVE_PROVIDER_CALLBACK,
	) = ActivityRecognitionEvidenceBatch(
		receivedElapsedRealtimeNanos = 100L,
		receivedWallTimeMs = 1_000L,
		registrationIdentity = identity(),
		startContext = startContext,
		automaticRecognitionEligible = automaticRecognitionEligible,
		automaticTransitions = automaticTransitions ?: transitions.map { evidence ->
			ActivityTransitionData(evidence.activityType, evidence.transitionType)
		}.toSet(),
		recognitions = recognitions,
		transitions = transitions,
	)

	private fun recognition(at: Long) = ActivityRecognitionEvidence(
		DetectedActivityType.WALKING,
		90,
		at,
	)

	private fun transition(
		at: Long,
		type: DetectedActivityType = DetectedActivityType.WALKING,
	) = ActivityTransitionEvidence(
		type,
		ActivityTransitionType.ENTER,
		at,
	)

	private fun identity() = ActivityRegistrationIdentity(
		sourceInstanceId = "activity-instance",
		registrationGeneration = 1L,
		collectedDataEpoch = 1L,
		clockDomainId = "boot-1",
		physicalConfigurationFingerprint = "physical-config",
	)

	private fun registration(
		status: String = ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
	) = ProviderRegistrationGenerationEntity(
		sourceKind = SourceKind.ACTIVITY.stableCode,
		registrationGeneration = 1L,
		sourceInstanceId = "activity-instance",
		ownerScope = "activity-provider",
		clockDomainId = "boot-1",
		physicalConfigurationFingerprint = "physical-config",
		collectedDataEpoch = 1L,
		providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE,
		providerProcessIncarnationId = null,
		status = status,
		reservedAtMs = 900L,
		reservedElapsedRealtimeNanos = 10L,
		acceptedAtMs = if (status == ProviderRegistrationGenerationEntity.STATUS_ACTIVE) 900L else null,
		acceptedElapsedRealtimeNanos =
			if (status == ProviderRegistrationGenerationEntity.STATUS_ACTIVE) 10L else null,
		retiredAtMs = null,
		retiredElapsedRealtimeNanos = null,
		failureCode = if (status == ProviderRegistrationGenerationEntity.STATUS_FAILED) "FAILED" else null,
	)

	private fun activityAutomationAuthority() = ActivityAutomationEpochEntity(
		epoch = 17L,
		automaticControlEnabled = true,
		bootClockDomainId = "boot-1",
		effectiveElapsedRealtimeNanos = 0L,
	)

	private fun completedRecovery() = SourceRecoveryResult(
		CoordinatorDrainResult.Complete(Long.MAX_VALUE, 0),
		0,
		0,
	)
}
